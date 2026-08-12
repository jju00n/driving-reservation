# CLAUDE.md - AI 어시스턴트를 위한 프로젝트 가이드

## 프로젝트 개요

**이름:** 드라이빙 프로그램 예약 시스템
**주제:** BMW 드라이빙 센터 같은 유료 체험 프로그램 예약 서비스
**목적:** 멘토링 사이드 프로젝트 (면접 스토리 + 기술 학습)

## 기술 스택

- **Java:** 25
- **Spring Boot:** 4.0.4
- **데이터 접근:** Spring Data JDBC (JPA 미사용)
- **데이터베이스:** MySQL 8.x
- **캐시/분산락:** Redis (Lettuce)
- **보안:** Spring Security + JWT (jjwt 0.12.6)
- **결제:** 토스페이먼츠 API
- **문서:** springdoc-openapi 2.8.6 (Swagger UI)
- **복원력:** Resilience4j 2.3.0
- **메시징:** Kafka (spring-boot-starter-kafka, KRaft 단일 노드) — 웹훅 수신/처리 분리
- **빌드:** Gradle (Kotlin DSL)

## 핵심 학습 목표

1. **Redis 분산락** — 예약 시 재고 동시성 제어
2. **Resilience4j** — 결제 API 서킷브레이커
3. **Kafka** — 웹훅 수신/처리 분리, 재시도·DLT, 컨슈머 멱등성
4. **통합 테스트** — TestContainers (MySQL + Redis + Kafka)
5. **단위 테스트** — JUnit 5, Mockito
6. **CI/CD** — Docker + EKS (추후)

## 도메인 구성

```
member      → users 테이블
program     → vehicles, programs, schedules 테이블
reservation → reservations, reservation_histories 테이블
payment     → payments, payment_histories, webhook_events 테이블
```

## 패키지 구조

```
src/main/java/com/example/driving/
├── member/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/        # Member.java
│   ├── enums/         # Role.java
│   └── dto/
├── program/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/        # Vehicle.java, Program.java, Schedule.java
│   ├── enums/         # ProgramStatus.java, ScheduleStatus.java
│   └── dto/
├── reservation/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/        # Reservation.java, ReservationHistory.java
│   ├── enums/         # ReservationStatus.java
│   └── dto/
├── payment/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── kafka/         # WebhookEventProducer/Consumer, WebhookDltListener, WebhookMessage
│   ├── scheduler/     # WebhookRepublishScheduler (발행 유실 회수)
│   ├── domain/        # Payment.java, PaymentHistory.java, WebhookEvent.java
│   ├── enums/         # PaymentStatus.java, WebhookEventStatus.java
│   └── dto/
└── common/
    ├── config/        # SecurityConfig, RedisConfig, SwaggerConfig, KafkaConfig
    ├── exception/     # BusinessException, GlobalExceptionHandler
    ├── response/      # ApiResponse
    ├── security/      # JwtProvider, JwtFilter, CustomUserDetailsService
    └── util/          # AesEncryptUtil
```

## 상태 정의

### 예약 상태 (ReservationStatus)
| 상태 | 설명 | 트리거 |
|------|------|--------|
| PAYMENT_PENDING | 결제 대기 | 예약 신청 시 (재고 차감) |
| CONFIRMED | 예약 확정 | 결제 완료 시 |
| PAYMENT_FAILED | 결제 실패 | 결제 실패 시 → 재고 복구 |
| CANCELLED | 예약 취소 | 고객 취소 시 → 재고 복구 + 환불 |
| EXPIRED | 예약 만료 | 미결제 만료 → 재고 복구 (토스 만료 정책 연동, 아래 참고) |

### 결제 상태 (PaymentStatus)
| 상태 | 설명 |
|------|------|
| PENDING | 결제 대기 |
| REQUESTED | 결제 요청 |
| COMPLETED | 결제 완료 |
| REFUND_REQUESTED | 환불 요청 |
| REFUNDED | 환불 완료 |
| FAILED | 결제 실패 |

**핵심 결정:** 재고 복구는 예약 상태 변경 시점에서만 처리 (결제 상태에서는 처리 안 함)

## API 목록

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | /auth/signup | 회원가입 | 불필요 |
| POST | /auth/login | 로그인 | 불필요 |
| POST | /auth/logout | 로그아웃 | 필요 |
| GET | /programs | 프로그램 리스트 | 불필요 |
| GET | /programs/{id} | 프로그램 상세 | 불필요 |
| POST | /reservations | 예약 신청 (분산락) | 필요 |
| GET | /reservations/{id} | 예약 조회 | 필요 |
| PUT | /reservations/{id} | 예약 변경 | 필요 |
| DELETE | /reservations/{id} | 예약 취소 | 필요 |
| POST | /payments/confirm | 결제 승인 | 필요 |
| POST | /payments/webhook | 토스 웹훅 수신 | 불필요 |

## 토스페이먼츠 결제 프로세스

```
[1] 예약 신청 → 재고 차감(분산락) → orderId/amount 반환
[2] 프론트 → 토스 SDK 결제창 오픈 → 고객 결제
[3] 토스 → successUrl 리다이렉트 (paymentKey, orderId, amount)
[4] 프론트 → POST /payments/confirm
[5] 백엔드 → amount 위변조 검증 → 토스 confirm API 호출
[6] 성공: CONFIRMED, 실패: PAYMENT_FAILED + 재고 복구
```

## 미결제 만료 정책 (EXPIRED)

토스 결제 유효시간과 우리 예약 만료 정책을 **30분 기준으로 일치**시킨다.

**토스 만료 메커니즘 (2단계 타이머):**
- ① 결제창 인증: **30분** 안에 고객이 결제창에서 인증하지 않으면 `EXPIRED`
- ② 승인 대기: 인증 후(IN_PROGRESS) **10분** 안에 상점이 confirm API를 호출하지 않으면 `EXPIRED`
- 만료 시 토스가 `PAYMENT_STATUS_CHANGED` 웹훅 발송 (`data.status == EXPIRED`)

**핵심:** 토스가 결제를 EXPIRED 처리해도 **우리 DB의 예약/재고는 자동으로 풀리지 않는다.** 우리 쪽 만료 처리가 반드시 필요.

**처리 전략 (웹훅 1차 + 스케줄러 안전망):**
| 경로 | 동작 |
|------|------|
| 1차: 토스 웹훅 | `PAYMENT_STATUS_CHANGED` 수신 → RECEIVED 저장 + Kafka 발행 → 즉시 200. 컨슈머가 `data.status == EXPIRED` 판정 후 `expire()` + 재고 복구 |
| 2차: 스케줄러 (안전망) | `reservedAt + 40분`(토스 30+10분보다 여유) 초과 PAYMENT_PENDING 건 청소. 웹훅 유실/서버 다운 중 미수신 대비 |

**멱등성:** `webhook_events.order_id` 중복 체크 + `Reservation.expire()`의 상태 가드(`isPaymentPending()`)로 중복 재고 복구 방지. Kafka 는 at-least-once 라 중복 소비가 필연인데, 토스 재전송용으로 만든 이 가드가 그대로 쓰인다.

## 웹훅 처리 파이프라인 (Kafka)

수신과 처리를 나눈 이유는 성능이 아니라 **실패한 이벤트를 다시 볼 방법이 없었기 때문**이다. 이전 구조는
처리 중 예외를 catch 로 삼키고 토스에 200 을 줬다 — 토스는 재전송하지 않고 만료 스케줄러는
PAYMENT_PENDING 만 훑으므로, 환불 보정 실패 건(`REFUND_REQUESTED` 잔류)은 아무도 다시 집지 않았다.

```
[톰캣]   컨트롤러 → RECEIVED 저장 → Kafka 발행 → 200 ack
[컨슈머] poll → process() → 상태 전이 → 성공 시에만 오프셋 커밋
                    ↓ 실패
              1s → 2s → 4s 재시도 → 소진 시 DLT + FAILED 마킹
[스케줄러] RECEIVED 로 5분 이상 잔류 = 발행 유실 → 재발행 (아웃박스 역할)
```

| 항목 | 값 | 이유 |
|------|-----|------|
| 토픽 / 파티션 | `payment.webhook.received` / 3 | |
| 파티션 키 | `orderId` | 같은 주문만 순서 보장, 다른 주문은 병렬 처리 |
| `enable-auto-commit` | false + `ack-mode: record` | 자동 커밋은 처리 전에 커밋돼 유실 |
| 재시도 | 3회 (1s→2s→4s) | DB 순간 장애 정도는 회복. 무한 재시도는 파티션을 막음 |
| DLT | `payment.webhook.received.DLT` | 자동 회복 불가 건 격리 — 재고/예약은 건드리지 않고 사람이 확인 |

**Kafka 를 쓰지 않는 곳:** 예약 재고 차감(분산락 안에서 동기·원자적으로 — 비동기로 빼면 오버셀),
결제 confirm(사용자가 결과를 기다림), 재고 복구 자체(트랜잭션 내부).

> 상세 흐름은 `docs/payment-sequence.md` 참고.

## 분산락 전략

```java
// 예약 신청 시 schedule_idx 단위로 락
String lockKey = "lock:schedule:" + scheduleIdx;
// 타임아웃: 5초, 대기: 3초
// 락 획득 실패 시 → 잠시 후 재시도 안내 응답
```

## 개발 환경 실행

```bash
# Docker로 MySQL + Redis 실행
docker-compose up -d

# 로컬 프로파일로 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 빌드
./gradlew build

# 테스트
./gradlew test
```

## 코드 컨벤션

### Java
- Lombok: `@Getter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- 엔티티 생성자는 PROTECTED, Builder로만 생성
- 메서드: camelCase, 동사 접두사 (`find`, `create`, `update`, `cancel`)
- 도메인 로직은 도메인 객체 안에 (서비스는 오케스트레이션만)

### API 응답
```java
// 성공
ApiResponse.ok(data)

// 실패
ApiResponse.fail("에러 메시지")
```

### 테스트
- 단위 테스트: Mockito로 의존성 Mock
- 통합 테스트: @SpringBootTest + TestContainers
- 분산락 테스트: 멀티스레드로 동시성 검증

## PR 규칙

- 브랜치: `feature/{기능명}` (예: `feature/member-signup`)
- PR 단위: 기능 단위로 분리 (회원가입 / 로그인 / 예약 등)
- PR 머지 전 테스트 통과 필수

## 6주 스코핑

| 주차 | 내용 | 공수 |
|------|------|------|
| 1주차 | 프로젝트 초기 설정 + 회원가입 + 로그인/로그아웃 + 프로그램 조회 (각 테스트 포함) | 5일 |
| 2주차 | 예약 신청 + Redis 분산락 + 테스트 | 5일 |
| 3주차 | 결제 토스페이먼츠 연동 + 테스트 | 5일 |
| 4주차 | 결제 마무리(1일) + 결제 웹훅 + 테스트 | 5일 |
| 5주차 | 예약 확인 + 테스트 (1.5일) + 예약 취소 + 테스트 (3.5일) | 5일 |
| 6주차 | 예약 취소 마무리(0.5일) + 어드민 or 버퍼(4.5일) | 5일 |

**총 25.5일 / 버퍼 4.5일**

## ERD / 데이터 모델

상세 스키마(테이블별 컬럼 정의 + 인덱스 설계)는 @docs/erd.md 참고.

> 진행 상황은 이 문서에 두지 않음 — AI 메모리(`progress-next`)로 일원화하여 stale/중복 방지.

---

**생성:** 멘토링 10회차 설계 기반 (2026-05-18)
**최종 업데이트:** 2026-08-12 (웹훅 수신/처리 Kafka 분리)
