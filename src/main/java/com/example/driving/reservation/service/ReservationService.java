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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String LOCK_KEY_PREFIX = "lock:schedule:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final ScheduleRepository scheduleRepository;
    private final ProgramRepository programRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;

    public CreateReservationResponse create(Long memberIdx, Long programIdx, Long scheduleIdx) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + scheduleIdx);

        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("예약 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해주세요.",
                        HttpStatus.CONFLICT);
            }

            return transactionTemplate.execute(_ -> doCreate(memberIdx, programIdx, scheduleIdx));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("예약 처리 중 인터럽트가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private CreateReservationResponse doCreate(Long memberIdx, Long programIdx, Long scheduleIdx) {
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
            throw new BusinessException("이미 해당 스케줄에 예약이 있습니다.");
        }

        // 재고 원자 차감 (remaining > 0 조건). Redis 락 장애 시에도 DB가 음수 방지.
        int decreased = scheduleRepository.decreaseRemainingIfAvailable(scheduleIdx);
        if (decreased == 0) {
            throw new BusinessException("잔여석이 없습니다.");
        }

        String orderId = UUID.randomUUID().toString();
        Reservation reservation = Reservation.create(
                memberIdx, scheduleIdx, program.getProgramIdx(), program.getAmount(), orderId);
        Reservation saved = reservationRepository.save(reservation);

        reservationHistoryRepository.save(ReservationHistory.of(saved.getReservationIdx(), saved.getStatus()));

        log.info("예약 신청 완료 - memberIdx={}, programIdx={}, scheduleIdx={}, orderId={}",
                memberIdx, programIdx, scheduleIdx, orderId);
        return CreateReservationResponse.from(saved);
    }
}
