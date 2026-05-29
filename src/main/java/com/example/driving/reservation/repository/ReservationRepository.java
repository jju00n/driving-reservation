package com.example.driving.reservation.repository;

import com.example.driving.reservation.domain.Reservation;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ReservationRepository extends CrudRepository<Reservation, Long> {

    Optional<Reservation> findByOrderId(String orderId);
}
