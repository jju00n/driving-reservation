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
payment     → payments, payment_histories 테이블
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

---

**생성:** 멘토링 10회차 설계 기반 (2026-05-18)
