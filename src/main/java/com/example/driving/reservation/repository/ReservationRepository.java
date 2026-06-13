package com.example.driving.reservation.repository;

import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends CrudRepository<Reservation, Long> {

    Optional<Reservation> findByOrderId(String orderId);

    /**
     * 같은 회원이 같은 스케줄에 활성 예약을 이미 보유 중인지 확인.
     * capacity≥2 스케줄에서 한 회원의 잔여석 중복 점유를 막는다.
     */
    boolean existsByMemberIdxAndScheduleIdxAndStatusIn(
            Long memberIdx, Long scheduleIdx, Collection<ReservationStatus> statuses);

    /**
     * 미결제 만료 스케줄러용 — 만료 임계 시각 이전에 신청됐는데 아직 PAYMENT_PENDING 인 좀비 예약 조회.
     */
    List<Reservation> findByStatusAndReservedAtBefore(ReservationStatus status, LocalDateTime threshold);

    /**
     * 예약을 원자적으로 EXPIRED 로 전이한다(PAYMENT_PENDING 인 경우만).
     * 웹훅(1차)과 스케줄러(2차)가 같은 건을 동시에 처리해도, 단 하나의 호출만 1을 반환(나머지는 0)하므로
     * 재고 중복 복구를 방지하는 멱등키 역할을 한다.
     *
     * @return 영향받은 행 수. 1이면 이번 호출이 만료시킴(→ 재고 복구 진행), 0이면 이미 처리됨.
     */
    @Modifying
    @Query("UPDATE reservations SET status = 'EXPIRED', updated_at = NOW() " +
            "WHERE order_id = :orderId AND status = 'PAYMENT_PENDING'")
    int expireIfPending(@Param("orderId") String orderId);
}
