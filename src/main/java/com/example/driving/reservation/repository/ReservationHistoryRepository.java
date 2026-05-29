package com.example.driving.reservation.repository;

import com.example.driving.reservation.domain.ReservationHistory;
import org.springframework.data.repository.CrudRepository;

public interface ReservationHistoryRepository extends CrudRepository<ReservationHistory, Long> {
}
