package com.example.driving.reservation.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.domain.ReservationHistory;
import com.example.driving.reservation.dto.CreateReservationResponse;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationHistoryRepository;
import com.example.driving.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 예약 신청/만료의 <b>트랜잭션 경계</b>를 담당하는 빈.
 *
 * <p>오케스트레이션({@link ReservationService})과 <b>다른 빈</b>으로 두는 이유는 {@code @Transactional} 이
 * Spring AOP 프록시로 동작하기 때문 — 같은 클래스 내부 호출(self-invocation)은 프록시를 거치지 않아
 * 트랜잭션이 적용되지 않는다. 빈을 나눠 프록시를 경유하게 만들어야 어노테이션이 발동한다.
 * (결제의 {@code PaymentService}/{@code PaymentTxService} 와 동일한 구조.)
 *
 * <p><b>분산락과의 순서:</b> {@code createReservation} 은 {@link ReservationService#create} 가
 * <b>락을 잡은 상태에서</b> 호출한다. 프록시 경유로 트랜잭션이 시작되고 메서드가 정상 반환되면 커밋이
 * 완료된 뒤 호출자가 락을 해제하므로, <b>락 획득 → 트랜잭션 커밋 → 락 해제</b> 순서가 보장된다
 * (커밋 전 락 해제로 인한 동시성 결함 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTxService {

    private final ScheduleRepository scheduleRepository;
    private final ProgramRepository programRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;

    /** 예약 신청 — 스케줄/프로그램/중복 검증 후 재고 원자 차감 + 예약/이력 저장. 호출자가 분산락 안에서 부른다. */
    @Transactional
    public CreateReservationResponse createReservation(Long memberIdx, Long programIdx, Long scheduleIdx) {
        Schedule schedule = scheduleRepository.findById(scheduleIdx)
                .orElseThrow(() -> new BusinessException("스케줄을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!schedule.getProgramIdx().equals(programIdx)) {
            throw new BusinessException("스케줄과 프로그램이 일치하지 않습니다.");
        }

        if (!schedule.isOpen()) {
            throw new BusinessException("예약 가능한 스케줄이 아닙니다.");
        }

        Program program = programRepository.findById(programIdx)
                .orElseThrow(() -> new BusinessException("프로그램을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!program.isActive()) {
            throw new BusinessException("종료된 프로그램입니다.");
        }

        if (reservationRepository.existsByMemberIdxAndScheduleIdxAndStatusIn(
                memberIdx, scheduleIdx, ReservationStatus.activeStatuses())) {
            throw new BusinessException("이미 해당 스케줄에 예약이 있습니다.", HttpStatus.CONFLICT);
        }

        // 재고 원자 차감 (remaining > 0 조건). Redis 락 장애 시에도 DB가 음수 방지.
        int decreased = scheduleRepository.decreaseRemainingIfAvailable(scheduleIdx);
        if (decreased == 0) {
            throw new BusinessException("잔여석이 없습니다.");
        }

        String orderId = UUID.randomUUID().toString();
        Reservation reservation = Reservation.create(
                memberIdx, scheduleIdx, program.getProgramIdx(), program.getAmount(), orderId);
        Reservation saved = saveOrConflict(reservation);

        reservationHistoryRepository.save(ReservationHistory.of(saved.getReservationIdx(), saved.getStatus()));

        log.info("예약 신청 완료 - memberIdx={}, programIdx={}, scheduleIdx={}, orderId={}",
                memberIdx, programIdx, scheduleIdx, orderId);
        return CreateReservationResponse.from(saved);
    }

    /**
     * 예약을 저장하되, DB 유니크 제약(uk_reservations_active_member_schedule) 위반은 중복 예약 409 로 변환한다.
     *
     * <p>정상 흐름에서는 위쪽 {@code existsBy...} 사전 조회가 먼저 막아 여기 도달하지 않는다.
     * 이 경로는 <b>분산락 리스(5초) 만료 등으로 두 트랜잭션이 사전 조회를 동시에 통과한 극단 경합</b>의
     * 최종 방어선이다. Spring Data JDBC(JdbcTemplate 예외 번역)는 유니크 위반을
     * {@link DuplicateKeyException} 으로 던지므로, 이를 잡아 사전 조회와 동일한 409 계약으로 맞춘다.
     * (BusinessException 전파 → 트랜잭션 롤백 → 직전 재고 차감도 자동 복구)
     */
    private Reservation saveOrConflict(Reservation reservation) {
        try {
            return reservationRepository.save(reservation);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("이미 해당 스케줄에 예약이 있습니다.", HttpStatus.CONFLICT);
        }
    }

    /**
     * 미결제 예약을 만료 처리하고 재고를 복구한다. 웹훅(1차)·만료 스케줄러(2차)가 공유한다.
     *
     * <p>멱등: {@code expireIfPending} 원자 UPDATE 가 PAYMENT_PENDING 인 경우에만 1을 반환하므로,
     * 웹훅과 스케줄러가 같은 건을 동시에/중복으로 호출해도 재고 복구는 단 한 번만 일어난다.
     * 재고 복구를 예약 상태전이(만료) 시점에 묶는 "재고 복구 단일 책임" 원칙은 confirm 실패 경로와 동일.
     */
    @Transactional
    public void expireReservation(String orderId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElse(null);
        if (reservation == null) {
            log.warn("만료 대상 예약을 찾을 수 없음 - orderId={}", orderId);
            return;
        }
        int expired = reservationRepository.expireIfPending(orderId);
        if (expired == 0) {
            return; // 이미 만료/확정 등으로 처리됨 — 중복 복구 방지(멱등)
        }
        scheduleRepository.increaseRemaining(reservation.getScheduleIdx());
        reservationHistoryRepository.save(
                ReservationHistory.of(reservation.getReservationIdx(), ReservationStatus.EXPIRED));
        log.info("예약 만료 + 재고 복구 - orderId={}, scheduleIdx={}", orderId, reservation.getScheduleIdx());
    }
}
