package com.example.driving.reservation.repository;

import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.Optional;

public interface ReservationRepository extends CrudRepository<Reservation, Long> {

    Optional<Reservation> findByOrderId(String orderId);

    /**
     * 같은 회원이 같은 스케줄에 활성 예약을 이미 보유 중인지 확인.
     * capacity≥2 스케줄에서 한 회원의 잔여석 중복 점유를 막는다.
     */
    boolean existsByMemberIdxAndScheduleIdxAndStatusIn(
            Long memberIdx, Long scheduleIdx, Collection<ReservationStatus> statuses);
}
