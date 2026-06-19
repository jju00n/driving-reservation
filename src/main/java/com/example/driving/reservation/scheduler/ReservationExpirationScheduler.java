package com.example.driving.reservation.scheduler;

import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.enums.ReservationStatus;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.reservation.service.ReservationTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 미결제 만료 안전망(2차) — 웹훅 유실/서버 다운 중 미수신 대비.
 *
 * <p>{@code reservedAt + overdueMinutes}(기본 40분, 토스 30+10분보다 여유) 초과인데 아직
 * PAYMENT_PENDING 인 좀비 예약을 주기적으로 만료 처리한다. 실제 만료/재고복구는
 * {@link ReservationTxService#expireReservation}(원자 UPDATE 멱등)에 위임하므로, 웹훅과 겹쳐도 안전.
 *
 * <p><b>멀티 인스턴스 주의:</b> 여러 인스턴스로 띄우면 스케줄러가 동시에 돌 수 있다. 현재는
 * expireReservation 의 멱등성으로 중복 복구는 막히지만, 중복 스캔 비용을 줄이려면
 * ShedLock/Redisson 분산락으로 단일 실행을 보장하는 것이 좋다(후속).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirationScheduler {

    private final ReservationRepository reservationRepository;
    private final ReservationTxService reservationTxService;

    @Value("${reservation.expiration.overdue-minutes:40}")
    private long overdueMinutes;

    @Scheduled(fixedDelayString = "${reservation.expiration.scan-interval-ms:60000}")
    public void expireOverduePendingReservations() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(overdueMinutes);
        List<Reservation> targets = reservationRepository.findByStatusAndReservedAtBefore(
                ReservationStatus.PAYMENT_PENDING, threshold);
        if (targets.isEmpty()) {
            return;
        }
        for (Reservation reservation : targets) {
            try {
                reservationTxService.expireReservation(reservation.getOrderId());
            } catch (Exception e) {
                log.error("만료 스케줄러 처리 실패 - orderId={}", reservation.getOrderId(), e);
            }
        }
        log.info("미결제 만료 스케줄러 - {}건 처리(임계 {}분 초과)", targets.size(), overdueMinutes);
    }
}
