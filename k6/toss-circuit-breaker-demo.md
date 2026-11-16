# tossPayments 서킷브레이커 블랙박스 실측 데모

`toss-circuit-breaker-demo.sh`는 WireMock standalone을 실제 토스 API 대역에 세우고,
앱을 그 WireMock을 보도록 재기동한 뒤, 진짜 HTTP로 `POST /reservations` →
`POST /payments/confirm`을 반복 호출해서 Resilience4j 서킷브레이커(`tossPayments`)가
설정값대로 실제 동작하는지 검증한다.

## 사전조건

```bash
docker compose up -d   # MySQL/Redis
brew install jq        # 없으면
```

WireMock standalone jar는 `testImplementation("org.wiremock:wiremock-standalone:...")`
의존성 덕분에 Gradle 캐시에 이미 받아져 있다(스크립트가 자동으로 찾음).

## 실행

```bash
bash k6/toss-circuit-breaker-demo.sh
```

스크립트가 기존에 떠 있는 앱을 종료하고 `TOSS_BASE_URL=http://localhost:9999`로
재기동하므로, 끝난 뒤 실제 결제 연동 테스트를 하려면 앱을 다시 기본 설정으로 띄울 것.

## 실측 결과 (2026-07-31)

설정값: `sliding-window-size=10`, `minimum-number-of-calls=5`, `failure-rate-threshold=50%`,
`wait-duration-in-open-state=5s`.

| 시도 | HTTP 상태 | 소요(ms) | WireMock 누적 수신건수 |
|---|---|---|---|
| 1 | 409 | 63 | 0 (Redis 락 콜드스타트 — 서킷 카운트에는 안 잡힘) |
| 2 | 503 | 92 | 1 |
| 3 | 503 | 55 | 2 |
| 4 | 503 | 45 | 3 |
| 5 | 503 | 42 | 4 |
| 6 | 503 | 44 | **5** ← 여기서 실패율 100%(5/5)로 임계값(50%) 초과, 서킷 OPEN |
| 7 | 503 | 40 | 5 (그대로 — WireMock까지 요청이 아예 안 감) |
| 8 | 503 | 41 | 5 (그대로) |

→ 5초(`wait-duration-in-open-state`) 대기 후 WireMock 스텁을 200 성공으로 교체,
HALF_OPEN 상태에서 재시도하니 `HTTP 200` + 예약 상태 `CONFIRMED`로 정상 복구 확인.

**결론**: 6번째 호출부터 실패율 임계값 초과로 서킷이 OPEN되어 이후 요청이 실제
네트워크 호출 없이(WireMock 수신건수가 5에서 고정) 즉시 차단됐고, wait-duration
경과 후 HALF_OPEN → 성공 → CLOSED 복귀까지 실제 HTTP 트래픽으로 확인했다.
`application.yml`의 서킷브레이커 설정값이 이론이 아니라 실측으로 검증된 것.
