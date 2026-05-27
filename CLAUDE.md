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
- **빌드:** Gradle (Kotlin DSL)

## 핵심 학습 목표

1. **Redis 분산락** — 예약 시 재고 동시성 제어
2. **Resilience4j** — 결제 API 서킷브레이커
3. **통합 테스트** — TestContainers (MySQL + Redis)
4. **단위 테스트** — JUnit 5, Mockito
5. **CI/CD** — Docker + EKS (추후)

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
│   ├── domain/        # Payment.java, PaymentHistory.java
│   ├── enums/         # PaymentStatus.java
│   └── dto/
└── common/
    ├── config/        # SecurityConfig, RedisConfig, SwaggerConfig
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
| EXPIRED | 예약 만료 | 1시간 초과 미결제 → 재고 복구 |

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

## 현재 진행 상황

| 주차 | 항목 | 상태 |
|------|------|------|
| 1주차 | 프로젝트 초기 설정 | 완료 |
| 1주차 | 회원가입 / 로그인 구현 + 테스트 | 완료 |
| 1주차 | 프로그램 조회 | 미완료 |
| 1주차 | PR 제출 (feature/member) | 미완료 |
| 2주차~ | 예약, 결제, 이후 기능 | 미착수 |

**현재 브랜치:** `feature/member` (회원가입/로그인 커밋 완료, push 완료, PR 미제출)

## ERD 컬럼 정의

### users
| 컬럼 | 타입 | 비고 |
|------|------|------|
| member_idx | BIGINT | PK, AUTO_INCREMENT |
| email | VARCHAR(100) | 평문 (INDEX) |
| password | VARCHAR(60) | BCrypt 단방향 |
| name | VARCHAR(255) | 평문 저장 가능 (멘토 확인 — AES 암호화는 선택사항) |
| phone | VARCHAR(255) | 평문 저장 가능 (멘토 확인 — AES 암호화는 선택사항) |
| role | VARCHAR(20) | CUSTOMER / ADMIN |
| created_at | DATETIME | |
| updated_at | DATETIME | |
| deleted_at | DATETIME | nullable |

### vehicles
| 컬럼 | 타입 | 비고 |
|------|------|------|
| vehicle_idx | BIGINT | PK, AUTO_INCREMENT |
| name | VARCHAR(100) | |
| model | VARCHAR(100) | |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### programs
| 컬럼 | 타입 | 비고 |
|------|------|------|
| program_idx | BIGINT | PK, AUTO_INCREMENT |
| vehicle_idx | BIGINT | FK → vehicles |
| name | VARCHAR(100) | |
| duration | INT | 진행시간(분) |
| amount | BIGINT | 금액 |
| status | VARCHAR(20) | ACTIVE / INACTIVE |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### schedules
| 컬럼 | 타입 | 비고 |
|------|------|------|
| schedule_idx | BIGINT | PK, AUTO_INCREMENT |
| program_idx | BIGINT | FK → programs |
| start_at | DATETIME | |
| end_at | DATETIME | |
| capacity | INT | 정원 |
| remaining | INT | 잔여석 (분산락으로 차감) |
| status | VARCHAR(20) | OPEN / CLOSED / CANCELLED |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### reservations
| 컬럼 | 타입 | 비고 |
|------|------|------|
| reservation_idx | BIGINT | PK, AUTO_INCREMENT |
| member_idx | BIGINT | FK → users |
| program_idx | BIGINT | FK → programs |
| schedule_idx | BIGINT | FK → schedules |
| order_id | VARCHAR(64) | UNIQUE (토스 orderId) |
| amount | BIGINT | |
| status | VARCHAR(20) | ReservationStatus |
| reserved_at | DATETIME | NOT NULL |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### payments
| 컬럼 | 타입 | 비고 |
|------|------|------|
| payment_idx | BIGINT | PK, AUTO_INCREMENT |
| reservation_idx | BIGINT | FK, UNIQUE → reservations |
| payment_key | VARCHAR(200) | UNIQUE (토스 paymentKey) |
| order_id | VARCHAR(64) | UNIQUE |
| amount | BIGINT | |
| status | VARCHAR(20) | PaymentStatus |
| failure_code | VARCHAR(50) | nullable |
| failure_message | VARCHAR(255) | nullable |
| requested_at | DATETIME | nullable |
| approved_at | DATETIME | nullable |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### reservation_histories / payment_histories
| 컬럼 | 타입 | 비고 |
|------|------|------|
| history_idx | BIGINT | PK, AUTO_INCREMENT |
| reservation_idx / payment_idx | BIGINT | FK |
| status | VARCHAR(20) | 상태 스냅샷 |
| created_at | DATETIME | |

### webhook_events
| 컬럼 | 타입 | 비고 |
|------|------|------|
| webhook_event_idx | BIGINT | PK, AUTO_INCREMENT |
| event_type | VARCHAR(50) | PAYMENT_STATUS_CHANGED / CANCEL_STATUS_CHANGED |
| order_id | VARCHAR(64) | nullable, INDEX (payments/reservations 조회용) |
| raw_payload | TEXT | 토스 웹훅 원문 JSON |
| status | VARCHAR(20) | RECEIVED / PROCESSED / FAILED |
| created_at | DATETIME | |

> FK 없음 — 웹훅은 예외 상황 대비 안전망이므로 참조 무결성보다 유연성 우선. order_id로 payments/reservations 조회.

## 인덱스 설계 (멘토 피드백 반영)

| 테이블 | 인덱스 컬럼 | 용도 |
|--------|------------|------|
| users | `email` | 로그인 시 이메일 조회 |
| schedules | `program_idx`, `start_at` | 프로그램별 일정 목록 조회 |
| reservations | `member_idx` | 회원별 예약 목록 조회 |
| reservations | `order_id` | 결제 연동 시 orderId 조회 |
| payments | `order_id` | 결제 확인/웹훅 처리 |
| payments | `payment_key` | 결제 취소 시 paymentKey 조회 |
| webhook_events | `order_id` | 웹훅 이벤트 중복 처리 방지 |

---

**생성:** 멘토링 10회차 설계 기반 (2026-05-18)
**최종 업데이트:** 2026-05-19 (멘토 피드백 반영: webhook_events 테이블 추가, 인덱스 설계 추가)
