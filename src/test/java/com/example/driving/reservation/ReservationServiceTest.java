package com.example.driving.reservation;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.reservation.dto.CreateReservationResponse;
import com.example.driving.reservation.service.ReservationService;
import com.example.driving.reservation.service.ReservationTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 예약 신청 오케스트레이션(분산락) 단위 테스트.
 *
 * <p>검증/재고/저장 등 DB 작업 로직은 {@link ReservationTxService} 의 책임이므로 여기서는 mock 으로
 * 두고, 락 획득/실패/해제와 트랜잭션 빈 위임만 검증한다. (DB 작업 로직은 {@link ReservationTxServiceTest})
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("예약 신청 오케스트레이션 단위 테스트")
class ReservationServiceTest {

    @InjectMocks
    private ReservationService reservationService;

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private ReservationTxService reservationTxService;

    private static final Long MEMBER_IDX = 100L;
    private static final Long SCHEDULE_IDX = 10L;
    private static final Long PROGRAM_IDX = 1L;

    @BeforeEach
    void setUp() {
        given(redissonClient.getLock(anyString())).willReturn(rLock);
    }

    @Test
    @DisplayName("예약 신청 성공 - 락 획득 후 트랜잭션 빈에 위임하고 락을 해제한다")
    void create_success() throws InterruptedException {
        CreateReservationResponse expected = new CreateReservationResponse(999L, "order-1", 50_000L);

        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        given(reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .willReturn(expected);

        CreateReservationResponse response = reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX);

        assertThat(response).isEqualTo(expected);
        verify(reservationTxService).createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 - CONFLICT 예외, 트랜잭션 빈 호출/언락 없음")
    void create_fail_lockNotAcquired() throws InterruptedException {
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잠시 후 다시 시도")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(reservationTxService, never()).createReservation(anyLong(), anyLong(), anyLong());
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("트랜잭션 빈 예외 전파 - 예외가 올라와도 락은 해제된다")
    void create_propagatesException_andReleasesLock() throws InterruptedException {
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        given(reservationTxService.createReservation(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .willThrow(new BusinessException("잔여석이 없습니다."));

        assertThatThrownBy(() -> reservationService.create(MEMBER_IDX, PROGRAM_IDX, SCHEDULE_IDX))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잔여석이 없습니다");

        verify(rLock).unlock();
    }
}
