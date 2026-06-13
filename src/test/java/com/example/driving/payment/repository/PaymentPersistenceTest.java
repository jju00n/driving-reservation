package com.example.driving.payment.repository;

import com.example.driving.member.domain.Member;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.payment.domain.Payment;
import com.example.driving.payment.enums.PaymentStatus;
import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Schedule;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
import com.example.driving.program.enums.ScheduleStatus;
import com.example.driving.program.repository.ProgramRepository;
import com.example.driving.program.repository.ScheduleRepository;
import com.example.driving.program.repository.VehicleRepository;
import com.example.driving.reservation.domain.Reservation;
import com.example.driving.reservation.repository.ReservationRepository;
import com.example.driving.support.AbstractRepositoryTest;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.BuilderArbitraryIntrospector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment 저장 round-trip 테스트 - Fixture Monkey 의 null 제어(setNull/setNotNull) 활용.
 *
 * <p>Payment 는 nullable 필드(paymentKey/failureCode/failureMessage/approvedAt 등)가 있어
 * "상태에 따라 어떤 필드가 채워지고 어떤 필드가 비어야 하는가"라는 도메인 규칙을 가진다.
 * <ul>
 *   <li>성공(COMPLETED): approvedAt 존재, failureCode/failureMessage 는 NULL</li>
 *   <li>실패(FAILED): failureCode/failureMessage 존재, approvedAt 은 NULL</li>
 * </ul>
 * 값 자체를 일일이 {@code set} 으로 고정하는 대신, 비어있어야 할 자리는 {@code setNull},
 * 반드시 채워져야 할 자리는 {@code setNotNull} 로 "null 여부"만 제어해 의도를 드러낸다.
 * (실패 코드/메시지처럼 특정 의미값이 중요한 곳만 {@code set} 사용 — 멘토 기준)
 */
@DisplayName("Payment 저장 round-trip - Fixture Monkey null 제어")
class PaymentPersistenceTest extends AbstractRepositoryTest {

    // nullable 필드를 FM 기본 생성에 맡기지 않고(setNull/setNotNull 로 직접 제어),
    // NOT NULL 컬럼은 아래에서 모두 명시적으로 채운다.
    private static final FixtureMonkey fixtureMonkey = FixtureMonkey.builder()
            .objectIntrospector(BuilderArbitraryIntrospector.INSTANCE)
            .build();

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private ProgramRepository programRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;

    /** payments.reservation_idx FK 를 충족할 예약 1건을 FK 체인째 만들어 reservationIdx 를 반환한다. */
    private Long savedReservationIdx() {
        LocalDateTime now = LocalDateTime.now();
        Long vehicleIdx = vehicleRepository.save(Vehicle.builder()
                .name("BMW").model("M3").createdAt(now).updatedAt(now).build()).getVehicleIdx();
        Long programIdx = programRepository.save(Program.builder()
                .vehicleIdx(vehicleIdx).name("M3 드라이빙 체험").duration(60).amount(150_000L)
                .status(ProgramStatus.ACTIVE).createdAt(now).updatedAt(now).build()).getProgramIdx();
        Long scheduleIdx = scheduleRepository.save(Schedule.builder()
                .programIdx(programIdx).startAt(now.plusDays(1)).endAt(now.plusDays(1).plusHours(1))
                .capacity(5).remaining(5).status(ScheduleStatus.OPEN)
                .createdAt(now).updatedAt(now).build()).getScheduleIdx();
        Long memberIdx = memberRepository.save(Member.builder()
                .email("payer@test.com").password("encoded").name("payer").phone("01000000000")
                .role(Role.CUSTOMER).createdAt(now).updatedAt(now).build()).getMemberIdx();
        return reservationRepository.save(
                        Reservation.create(memberIdx, scheduleIdx, programIdx, 150_000L, "order-res-" + memberIdx))
                .getReservationIdx();
    }

    @Test
    @DisplayName("결제 성공(COMPLETED) - 승인시각은 채워지고 실패정보는 NULL 로 보존된다")
    void payment_success_roundTrip() {
        Long reservationIdx = savedReservationIdx();
        LocalDateTime now = LocalDateTime.now();

        Payment payment = fixtureMonkey.giveMeBuilder(Payment.class)
                .set("paymentIdx", null)
                .set("reservationIdx", reservationIdx)
                .set("orderId", "order-success-1")
                .set("paymentKey", "tossPaymentKey-success-1")
                .setNotNull("amount")     // setNotNull 시연: 금액은 반드시 존재(FM 랜덤 비-null Long)
                .set("status", PaymentStatus.COMPLETED)
                .set("approvedAt", now)   // 성공 → 승인 시각 존재(DATETIME 유효 범위 위해 고정)
                .setNull("failureCode")   // setNull 시연: 성공이면 실패 정보 없음
                .setNull("failureMessage")
                .set("requestedAt", now)
                .set("createdAt", now)
                .set("updatedAt", now)
                .sample();

        Payment saved = paymentRepository.save(payment);
        Payment found = paymentRepository.findById(saved.getPaymentIdx()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(found.getApprovedAt()).as("성공 결제는 승인시각 존재").isNotNull();
        assertThat(found.getFailureCode()).as("성공 결제는 실패코드 NULL").isNull();
        assertThat(found.getFailureMessage()).as("성공 결제는 실패메시지 NULL").isNull();
        assertThat(found.getAmount()).isEqualTo(saved.getAmount());
        assertThat(found.getPaymentKey()).isEqualTo("tossPaymentKey-success-1");
    }

    @Test
    @DisplayName("결제 실패(FAILED) - 실패정보는 채워지고 승인시각/paymentKey는 NULL 로 보존된다")
    void payment_failure_roundTrip() {
        Long reservationIdx = savedReservationIdx();
        LocalDateTime now = LocalDateTime.now();

        Payment payment = fixtureMonkey.giveMeBuilder(Payment.class)
                .set("paymentIdx", null)
                .set("reservationIdx", reservationIdx)
                .set("orderId", "order-fail-1")
                .setNull("paymentKey")    // setNull 시연: 실패 → 결제키 미발급
                .setNotNull("amount")     // setNotNull 시연: 금액은 반드시 존재
                .set("status", PaymentStatus.FAILED)
                .set("failureCode", "REJECT_CARD_COMPANY")          // 실패 코드는 의미 있는 특정값 → set 정당
                .set("failureMessage", "카드사에서 결제를 거절했습니다.")
                .set("requestedAt", now)
                .setNull("approvedAt")    // 실패 → 승인되지 않음
                .set("createdAt", now)
                .set("updatedAt", now)
                .sample();

        Payment saved = paymentRepository.save(payment);
        Payment found = paymentRepository.findById(saved.getPaymentIdx()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(found.getApprovedAt()).as("실패 결제는 승인시각 NULL").isNull();
        assertThat(found.getPaymentKey()).as("실패 결제는 paymentKey NULL").isNull();
        assertThat(found.getFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(found.getFailureMessage()).isNotNull();
    }
}
