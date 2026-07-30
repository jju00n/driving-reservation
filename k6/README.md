# 예약 동시성 부하테스트 (k6)

`reservation-concurrency-test.js`는 `POST /reservations`에 서로 다른 회원으로
동시에 요청을 쏴서, Redis 분산락(schedule 단위)이 재고(capacity)보다 많은
예약을 절대 허용하지 않는지 실측으로 검증한다.

## 사전 준비

```bash
brew install k6   # 최초 1회
docker-compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 실행 전 매번 재고 초기화

`data.sql`은 `INSERT IGNORE`라 재실행해도 이미 줄어든 `remaining`은 복구되지 않는다.
매 실행 전에 아래로 리셋할 것:

```bash
docker exec -it driving-mysql mysql -uroot -proot driving_reservation \
  -e "UPDATE schedules SET remaining = capacity WHERE schedule_idx = 4;"
```

## 실행

```bash
k6 run k6/reservation-concurrency-test.js
```

기본값: `scheduleIdx=4`(capacity=5)에 5×3=15명이 동시에 예약 신청.
환경변수로 조정 가능:

```bash
k6 run -e CAPACITY=5 -e OVERSUBSCRIBE=5 -e SCHEDULE_IDX=4 -e PROGRAM_IDX=3 \
  k6/reservation-concurrency-test.js
```

## 판정 기준

- **성공(201) 건수가 capacity를 절대 넘지 않아야 한다** — 넘으면 k6 자체가
  threshold 실패로 종료 코드를 non-zero로 반환한다(CI에 그대로 연결 가능).
- 나머지는 400(재고소진) 또는 409(락 대기 3초 초과/중복예약)로 떨어지는 게 정상.
- 예상 밖 상태코드(5xx 등)가 하나라도 나오면 threshold 실패.

## 결과를 Grafana로 보고 싶다면

`docker-compose.yml`에 이미 떠 있는 Prometheus/Grafana(`localhost:3000`)에
서킷브레이커 대시보드가 있다. k6 자체 메트릭까지 같이 보려면
[xk6-output-prometheus-remote](https://github.com/grafana/xk6-output-prometheus-remote)
확장을 추가로 빌드해야 한다 — 지금 단계에서는 k6 콘솔 요약만으로 충분하다.
