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
| **Kafka** | 웹훅 수신/처리 분리 (`payment.webhook.received`, 파티션 키 = orderId) |

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

수신과 처리를 Kafka로 나눈다. 토스가 응답을 기다리는 구간에서는 원문 보관과 발행만 하고,
상태 전이는 컨슈머가 별도 스레드에서 수행한다.

```mermaid
sequenceDiagram
    autonumber
    participant TOSS as 토스페이먼츠
    participant BE as Backend(수신)
    participant DB as MySQL
    participant K as Kafka
    participant C as Consumer(처리)

    Note over TOSS,C: 결제창 30분 / 인증 후 10분 미결제 → 토스가 EXPIRED 처리
    TOSS->>BE: POST /payments/webhook<br/>PAYMENT_STATUS_CHANGED (data.status=EXPIRED)
    BE->>DB: webhook_events 저장 (RECEIVED, raw_payload)
    BE->>K: 발행 (key = orderId → 같은 주문은 같은 파티션)
    BE-->>TOSS: 200 OK (여기까지 수십 ms — 처리를 기다리지 않음)

    Note over K,C: 컨슈머는 기동 때부터 poll() 루프로 대기 중
    K->>C: 레코드 전달
    C->>DB: 멱등 체크 (이 레코드 PROCESSED? 같은 orderId PROCESSED?)

    alt 이미 처리됨 (멱등)
        C->>DB: webhook_events → PROCESSED (상태 변경 없음)
    else PAYMENT_PENDING
        C->>DB: Reservation.expire() → EXPIRED (원자 UPDATE)
        C->>DB: schedule.remaining += 1 (재고 복구)
        C->>DB: 이력 + webhook_events → PROCESSED
    end

    C->>K: 오프셋 커밋 (예외 없이 끝났을 때만)
```

> **중복이거나 의미 처리 대상이 아닌 이벤트도 PROCESSED 로 마킹한다.** RECEIVED 로 두면 아래 3차
> 재발행 스케줄러가 계속 다시 발행해 순환에 빠진다.

#### 1차-실패: 재시도 → DLT 격리

```mermaid
sequenceDiagram
    autonumber
    participant C as Consumer
    participant DB as MySQL
    participant K as Kafka
    participant DLT as DLT 토픽

    C->>DB: 상태 전이 시도
    DB--xC: 예외 (DB 순간 장애 등)
    Note over C,K: 예외가 리스너 밖으로 나가면 오프셋 커밋 안 함 → 재소비

    loop 최대 3회 (1s → 2s → 4s)
        C->>DB: 재시도
        DB--xC: 계속 실패
    end

    C->>DLT: 레코드 격리 (원본 파티션 번호 유지)
    DLT->>C: DLT 리스너 수신
    C->>DB: webhook_events → FAILED + 에러 로그(사람이 확인)
```

> DLT 리스너는 **재고나 예약 상태를 임의로 건드리지 않는다.** 실패 원인을 모른 채 상태를 바꾸면
> 돈과 재고가 어긋난 채 덮인다. 격리해두고 원인을 확인한 뒤 재처리하는 편이 안전하다.
> 무한 재시도로 두지 않는 이유는 파티션이 순서대로 소비되기 때문 — 한 건이 계속 실패하면 뒤가 전부 밀린다.

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

#### 3차: 발행 유실 회수 (아웃박스 재발행)

수신 단계의 "DB 저장 → Kafka 발행"은 한 트랜잭션으로 묶을 수 없다(외부 시스템). 저장 직후 브로커가
죽거나 앱이 종료되면 이벤트가 RECEIVED 로 남은 채 아무도 처리하지 않는다. 그 흔적을 주워 재발행한다.

```mermaid
sequenceDiagram
    autonumber
    participant SCH as 재발행 스케줄러
    participant DB as MySQL
    participant K as Kafka

    Note over SCH,K: 정상 건은 컨슈머가 초 단위로 PROCESSED 로 넘김<br/>→ RECEIVED 로 5분 이상 잔류 = 발행 유실로 판정
    SCH->>DB: status=RECEIVED AND created_at < now-5분 조회
    loop 대상 건마다
        SCH->>DB: raw_payload 재파싱
        SCH->>K: 재발행 (key = orderId)
    end
```

> `webhook_events` 테이블이 아웃박스 역할을 겸한다 — 감사 목적으로 원문을 보관하던 테이블을 재사용했다.
> 원래 발행이 뒤늦게 성공했다면 같은 이벤트가 두 번 소비되지만, 멱등 가드가 막는다. **유실보다 중복이 낫다.**

> **멱등성:** `webhook_events.order_id` 중복 체크 + `expire()`의 `isPaymentPending()` 가드로, 웹훅과 스케줄러가 같은 건을 처리해도 재고 중복 복구가 발생하지 않음. Kafka 는 at-least-once 라 중복 소비가 필연인데, 토스 재전송용으로 만든 이 가드가 그대로 쓰인다.
> **멀티 인스턴스 주의:** 스케줄러를 여러 인스턴스로 띄우면 분산락(Redisson)/ShedLock으로 단일 실행 보장.

---

## 3. 토스 웹훅 수신 (안전망)

> 프론트 confirm 호출이 누락/실패해도 결제 상태를 보정하기 위한 비동기 안전망.
> `webhook_events`에 원문 저장 후 멱등 처리 (order_id로 중복 방지).
> 상세 파이프라인(재시도·DLT·재발행)은 위 "미결제 만료" 절 참고 — 같은 경로를 공유한다.

의미 처리하는 이벤트는 `PAYMENT_STATUS_CHANGED` 두 가지다.

| data.status | 처리 |
|-------------|------|
| `EXPIRED` | 예약 만료 + 재고 복구 |
| `CANCELED` | cancel 호출에서 5xx(미확정)를 받아 `REFUND_REQUESTED` 로 잔류시킨 건을 확정 (CANCELLED + REFUNDED + 재고 복구) |

```mermaid
sequenceDiagram
    autonumber
    participant TOSS as 토스페이먼츠
    participant BE as Backend(수신)
    participant DB as MySQL
    participant K as Kafka
    participant C as Consumer(처리)

    TOSS->>BE: POST /payments/webhook<br/>(PAYMENT_STATUS_CHANGED)
    BE->>DB: webhook_events 저장 (RECEIVED, raw_payload)
    BE->>K: 발행 (key = orderId)
    BE-->>TOSS: 200 OK

    K->>C: 레코드 전달
    C->>DB: orderId로 Payment/Reservation 조회

    alt 이미 처리된 상태 (멱등)
        C->>DB: webhook_events → PROCESSED (변경 없음)
    else EXPIRED
        C->>DB: Reservation.expire() + 재고 복구
        C->>DB: webhook_events → PROCESSED
    else CANCELED (REFUND_REQUESTED 잔류 건만)
        C->>DB: Reservation CANCELLED + Payment REFUNDED + 재고 복구
        C->>DB: webhook_events → PROCESSED
    end

    C->>K: 오프셋 커밋
```

> **이 구조로 바꾼 이유:** 이전에는 처리 중 예외를 catch 로 삼키고 토스에 200 을 줬다. 토스는 재전송하지
> 않고, 만료 스케줄러는 PAYMENT_PENDING 예약만 훑는다. 그래서 **환불 보정에 실패한 건(`REFUND_REQUESTED`
> 잔류)은 어느 그물에도 걸리지 않았다** — 고객 돈은 환불됐는데 예약은 살아 있는 상태로 영구 잔류.
> 성능이 아니라 이 구멍을 막으려고 Kafka 를 넣었다.

---

## 설계 포인트 (멘토 설명용)

1. **orderId는 예약 신청 시 서버가 UUID로 생성** — 프론트가 임의 생성하지 않음(위변조 방어).
2. **amount 이중 검증** — confirm 요청의 amount와 DB에 저장된 예약 amount를 비교한 뒤 토스 confirm API 호출.
3. **재고 복구 단일 책임** — 결제 상태가 아니라 **예약 상태 전이(`fail`/`cancel`/`expire`)** 에서만 `remaining += 1`. 복구 로직이 한 곳에 모여 중복 복구를 방지.
4. **서킷브레이커(Resilience4j)** — 토스 confirm API 장애 시 빠른 실패 + 폴백 (3주차 적용 예정).
5. **웹훅 멱등성** — `webhook_events.order_id` 기준으로 동일 이벤트 중복 처리 방지.
6. **만료 정책 = 토스와 30분 일치 + 이중 처리** — 토스 결제창 30분/인증 후 10분 만료에 맞춰 우리 예약 만료도 30분 기준. 토스가 EXPIRED를 자동 처리해도 우리 예약/재고는 안 풀리므로, **웹훅(1차) + 스케줄러(안전망)** 로 처리. 외부 API의 보장되지 않는 동작(웹훅 유실 가능성)에 대한 방어적 설계.
7. **웹훅 수신/처리 분리(Kafka)** — 비동기화가 목적이 아니라 **실패한 이벤트를 다시 볼 곳을 만드는 것**이 목적. 처리에 실패하면 오프셋이 커밋되지 않아 재소비되고, 백오프 재시도(1s→2s→4s)를 소진하면 DLT 로 격리된다. `@Async` 로는 안 되는 이유는 메모리 큐라서 — 서버가 죽으면 처리 대기 중이던 건이 통째로 사라진다.
8. **파티션 키 = orderId** — 같은 주문의 이벤트(EXPIRED → CANCELED)는 순서가 뒤집히면 안 되고, 주문끼리는 순서를 지킬 이유가 없다. 지켜야 할 순서만 지키고 나머지는 병렬로.
9. **컨슈머 멱등성은 새로 만들지 않았다** — Kafka 는 at-least-once 라 중복 소비가 필연인데, 토스 재전송을 막으려고 만들어둔 `webhook_events.order_id` 체크 + 원자 UPDATE 가드가 그대로 작동한다. 같은 문제라서.

## 검증 (실측)

| 항목 | 결과 |
|------|------|
| 웹훅 응답시간 | 53ms (처리를 기다리지 않음) |
| 파티션 키 | orderId 7건이 파티션별로 해싱 분산, 같은 orderId 는 항상 같은 파티션 |
| 멱등성 | 같은 웹훅 5회 수신 → 전부 PROCESSED, 재고는 1회만 복구, 이력 1건 |
| 재시도 백오프 | 1s → 2.07s → 4.03s (설정값과 일치) |
| DLT | 4번째 실패 후 격리 + FAILED 마킹, 재고/예약은 원상태 유지 |
| 테스트 | 통합 125건 통과 (`WebhookRetryDltIntegrationTest`, `WebhookRepublishSchedulerIntegrationTest` 포함) |
