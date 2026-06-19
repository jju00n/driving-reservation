package com.example.driving.reservation.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.reservation.dto.CreateReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 예약 신청 오케스트레이션 — 분산락 획득/해제만 담당한다.
 *
 * <p>DB 작업(검증·재고 차감·저장)은 {@link ReservationTxService#createReservation} 의
 * {@code @Transactional} 메서드로 위임한다. 별도 빈으로 둬야 Spring AOP 프록시를 경유해 트랜잭션이
 * 적용되며(self-invocation 회피), <b>락 획득 → (프록시 경유) 트랜잭션 커밋 → 락 해제</b> 순서가
 * 보장된다 — 커밋 전에 락을 풀어 다른 스레드가 미반영 재고를 읽는 동시성 결함을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String LOCK_KEY_PREFIX = "lock:schedule:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final RedissonClient redissonClient;
    private final ReservationTxService reservationTxService;

    public CreateReservationResponse create(Long memberIdx, Long programIdx, Long scheduleIdx) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + scheduleIdx);

        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("예약 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해주세요.",
                        HttpStatus.CONFLICT);
            }

            return reservationTxService.createReservation(memberIdx, programIdx, scheduleIdx);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("예약 처리 중 인터럽트가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
