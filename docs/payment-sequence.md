# 토스페이먼츠 결제 흐름 시퀀스 다이어그램

> 3주차 결제 연동 설계 문서 (멘토링 준비용)
> 작성: 2026-05-29

## 등장 요소

| 구분 | 설명 |
|------|------|
| **고객(Browser)** | 프론트 결제 화면, 토스 결제 SDK 위젯 |
| **Backend** | Spring Boot 서버 (ReservationService, PaymentService) |
| **Redis** | 분산락 (`lock:schedule:{scheduleIdx}`) |
| **MySQL** | reservations / schedules / payments 등 |
| **토스페이먼츠** | 외부 결제 PG (결제창, confirm API, 웹훅 발송) |

상태 표기는 CLAUDE.md 기준:
- 예약: `PAYMENT_PENDING → CONFIRMED / PAYMENT_FAILED`
- 결제: `PENDING → REQUESTED → COMPLETED / FAILED`

---

## 1. 예약 신청 → 결제 → 확정 (정상 흐름)

```mermaid
sequenceDiagram
    autonumber
    actor C as 고객(Browser)
    participant FE as 프론트 결제화면
    participant BE as Backend
    participant R as Redis(분산락)
    participant DB as MySQL
    participant TOSS as 토스페이먼츠

    Note over C,DB: [1] 예약 신청 (재고 차감)
    C->>BE: POST /reservations<br/>(programIdx, scheduleIdx) + JWT
    BE->>R: tryLock(lock:schedule:{id})<br/>wait 3s / lease 5s
    R-->>BE: 락 획득
    BE->>DB: 스케줄/프로그램 검증<br/>(programIdx 일치, OPEN, ACTIVE)
    BE->>DB: schedule.remaining -= 1
    BE->>DB: Reservation 저장<br/>(PAYMENT_PENDING, orderId=UUID)
    BE->>DB: ReservationHistory 저장
    BE->>R: unlock
    BE-->>FE: { reservationIdx, orderId, amount }

    Note over C,TOSS: [2] 토스 결제창
    FE->>C: 토스 SDK 결제창 오픈<br/>(orderId, amount)
    C->>TOSS: 카드/간편결제 인증
    TOSS-->>FE: successUrl 리다이렉트<br/>(paymentKey, orderId, amount)

    Note over FE,TOSS: [3] 결제 승인
    FE->>BE: POST /payments/confirm<br/>(paymentKey, orderId, amount)
    BE->>DB: orderId로 Reservation 조회
    BE->>BE: amount 위변조 검증<br/>(요청 amount == 예약 amount)
    BE->>DB: Payment 저장/갱신 (REQUESTED)
    BE->>TOSS: POST /v1/payments/confirm<br/>(서킷브레이커 적용)
    TOSS-->>BE: 200 OK (승인 완료)
    BE->>DB: Reservation.confirm() → CONFIRMED
    BE->>DB: Payment → COMPLETED (approvedAt)
    BE->>DB: 이력 저장 (Reservation/Payment History)
    BE-->>FE: 결제 완료 응답
    FE->>C: 예약 확정 화면
```

---

## 2. 결제 실패 흐름 (재고 복구)

> **핵심 결정:** 재고 복구는 **예약 상태 변경 시점에서만** 처리 (결제 상태에서는 안 함).

```mermaid
sequenceDiagram
    autonumber
    actor C as 고객(Browser)
    participant FE as 프론트 결제화면
    participant BE as Backend
    participant DB as MySQL
    participant TOSS as 토스페이먼츠

    FE->>BE: POST /payments/confirm<br/>(paymentKey, orderId, amount)
    BE->>DB: orderId로 Reservation 조회 (PAYMENT_PENDING)
    BE->>BE: amount 위변조 검증

    alt amount 위변조 감지
        BE-->>FE: 400 결제 금액 불일치
    else 토스 승인 실패
        BE->>TOSS: POST /v1/payments/confirm
        TOSS-->>BE: 4xx (카드 한도/거절 등)
        BE->>DB: Reservation.fail() → PAYMENT_FAILED
        BE->>DB: schedule.remaining += 1 (재고 복구)
        BE->>DB: Payment → FAILED (failureCode/Message)
        BE->>DB: 이력 저장
        BE-->>FE: 결제 실패 응답
        FE->>C: 결제 실패 안내 (재시도 유도)
    end
```

### 미결제 만료 (웹훅 1차 + 스케줄러 안전망)

> **토스 만료 메커니즘 (2단계 타이머):**
> - ① 결제창 인증 **30분** 미인증 → `EXPIRED`
> - ② 인증 후(IN_PROGRESS) **10분** 내 confirm 미호출 → `EXPIRED`
>
> 토스가 결제를 EXPIRED 처리해도 **우리 예약/재고는 자동으로 안 풀림.** 우리 쪽 처리가 반드시 필요.

#### 1차: 토스 웹훅 (주 경로, 실시간)

```mermaid
sequenceDiagram
    autonumber
    participant TOSS as 토스페이먼츠
    participant BE as Backend
    participant DB as MySQL

    Note over TOSS,DB: 결제창 30분 / 인증 후 10분 미결제 → 토스가 EXPIRED 처리
    TOSS->>BE: POST /payments/webhook<br/>PAYMENT_STATUS_CHANGED (data.status=EXPIRED)
    BE->>DB: webhook_events 저장 (RECEIVED, raw_payload)
    BE->>DB: data.orderId로 Reservation 조회

    alt 이미 처리됨 (멱등)
        BE->>DB: webhook_events → PROCESSED (변경 없음)
        BE-->>TOSS: 200 OK
    else PAYMENT_PENDING
        BE->>DB: Reservation.expire() → EXPIRED
        BE->>DB: schedule.remaining += 1 (재고 복구)
        BE->>DB: 이력 + webhook_events → PROCESSED
        BE-->>TOSS: 200 OK
    end
```

#### 2차: 스케줄러 (안전망 — 웹훅 유실/미수신 대비)

```mermaid
sequenceDiagram
    autonumber
    participant SCH as 스케줄러
    participant BE as Backend
    participant DB as MySQL

    Note over SCH,DB: reservedAt + 40분 초과인데 아직 PAYMENT_PENDING (좀비 예약)
    SCH->>BE: 만료 처리 트리거 (주기 실행)
    BE->>DB: 만료 대상 Reservation 조회<br/>(PAYMENT_PENDING & reservedAt < now-40분)
    loop 대상 건마다
        BE->>DB: Reservation.expire() → EXPIRED
        BE->>DB: schedule.remaining += 1 (재고 복구)
        BE->>DB: 이력 저장
    end
```

> **멱등성:** `webhook_events.order_id` 중복 체크 + `expire()`의 `isPaymentPending()` 가드로, 웹훅과 스케줄러가 같은 건을 처리해도 재고 중복 복구가 발생하지 않음.
> **멀티 인스턴스 주의:** 스케줄러를 여러 인스턴스로 띄우면 분산락(Redisson)/ShedLock으로 단일 실행 보장.

---

## 3. 토스 웹훅 수신 (안전망)

> 프론트 confirm 호출이 누락/실패해도 결제 상태를 보정하기 위한 비동기 안전망.
> `webhook_events`에 원문 저장 후 멱등 처리 (order_id로 중복 방지).

```mermaid
sequenceDiagram
    autonumber
    participant TOSS as 토스페이먼츠
    participant BE as Backend
    participant DB as MySQL

    TOSS->>BE: POST /payments/webhook<br/>(PAYMENT_STATUS_CHANGED)
    BE->>DB: webhook_events 저장 (RECEIVED, raw_payload)
    BE->>DB: orderId로 Payment/Reservation 조회

    alt 이미 처리된 상태 (멱등)
        BE->>DB: webhook_events → PROCESSED (변경 없음)
        BE-->>TOSS: 200 OK
    else 상태 보정 필요
        BE->>DB: Reservation/Payment 상태 동기화<br/>(필요 시 confirm/fail + 재고 처리)
        BE->>DB: webhook_events → PROCESSED
        BE-->>TOSS: 200 OK
    end
```

---

## 설계 포인트 (멘토 설명용)

1. **orderId는 예약 신청 시 서버가 UUID로 생성** — 프론트가 임의 생성하지 않음(위변조 방어).
2. **amount 이중 검증** — confirm 요청의 amount와 DB에 저장된 예약 amount를 비교한 뒤 토스 confirm API 호출.
3. **재고 복구 단일 책임** — 결제 상태가 아니라 **예약 상태 전이(`fail`/`cancel`/`expire`)** 에서만 `remaining += 1`. 복구 로직이 한 곳에 모여 중복 복구를 방지.
4. **서킷브레이커(Resilience4j)** — 토스 confirm API 장애 시 빠른 실패 + 폴백 (3주차 적용 예정).
5. **웹훅 멱등성** — `webhook_events.order_id` 기준으로 동일 이벤트 중복 처리 방지.
6. **만료 정책 = 토스와 30분 일치 + 이중 처리** — 토스 결제창 30분/인증 후 10분 만료에 맞춰 우리 예약 만료도 30분 기준. 토스가 EXPIRED를 자동 처리해도 우리 예약/재고는 안 풀리므로, **웹훅(1차) + 스케줄러(안전망)** 로 처리. 외부 API의 보장되지 않는 동작(웹훅 유실 가능성)에 대한 방어적 설계.
</content>
</invoke>
