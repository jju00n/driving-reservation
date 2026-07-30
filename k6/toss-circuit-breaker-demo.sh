#!/usr/bin/env bash
# ------------------------------------------------------------------
# tossPayments 서킷브레이커 블랙박스 실측 데모.
#
# WireMock을 실제 토스 API 대역으로 세워 앱이 여기에 붙게 하고,
# POST /reservations → POST /payments/confirm 을 진짜 HTTP로 반복 호출해
# ① 실패율 임계값(50%, 최소 5콜)을 넘기면 서킷이 OPEN 되어
#    이후 요청이 WireMock까지 가지도 않고 즉시 차단되는지,
# ② wait-duration-in-open-state(5초) 후 HALF_OPEN 으로 전환돼
#    성공 응답이 오면 다시 CLOSED 되는지를 실측으로 보여준다.
#
# 사전조건: docker compose up -d 로 MySQL/Redis 떠 있어야 함.
# 이 스크립트가 앱을 (재)기동하므로 기존에 로컬 프로파일로 떠 있던
# bootRun 프로세스가 있다면 먼저 종료할 것.
# ------------------------------------------------------------------
set -euo pipefail

WIREMOCK_JAR="$(find ~/.gradle -name 'wiremock-standalone-*.jar' 2>/dev/null | head -1)"
WIREMOCK_PORT=9999
APP_PORT=8080
BASE_URL="http://localhost:${APP_PORT}"
WM_URL="http://localhost:${WIREMOCK_PORT}"
PROGRAM_IDX=1
SCHEDULE_IDX=1

if [ -z "$WIREMOCK_JAR" ]; then
  echo "wiremock-standalone jar를 찾을 수 없습니다. ./gradlew testClasses 로 한 번 받아두세요." >&2
  exit 1
fi

echo "==> 1) WireMock standalone 기동 (port ${WIREMOCK_PORT})"
java -jar "$WIREMOCK_JAR" --port "$WIREMOCK_PORT" > /tmp/wiremock.log 2>&1 &
WIREMOCK_PID=$!
sleep 2

echo "==> 2) /v1/payments/confirm 을 500으로 응답하도록 스텁"
curl -s -X POST "${WM_URL}/__admin/mappings" -H 'Content-Type: application/json' -d '{
  "request": { "method": "POST", "urlPath": "/v1/payments/confirm" },
  "response": { "status": 500, "jsonBody": { "code": "INTERNAL_SERVER_ERROR", "message": "wiremock 강제 500" }, "headers": { "Content-Type": "application/json" } }
}' > /dev/null
echo "    스텁 등록 완료"

echo "==> 3) 앱 재기동 (TOSS_BASE_URL=${WM_URL})"
pkill -f "DrivingReservationApplication" 2>/dev/null || true
sleep 2
cd /Users/wonjun/Desktop/project/driving-reservation
TOSS_BASE_URL="${WM_URL}" ./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/driving-reservation-cb-demo.log 2>&1 &
APP_GRADLE_PID=$!

i=0
until curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1 || [ $i -ge 60 ]; do sleep 3; i=$((i+1)); done
if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
  echo "앱이 기동하지 않았습니다. /tmp/driving-reservation-cb-demo.log 확인" >&2
  exit 1
fi
echo "    앱 기동 완료"

echo "==> 4) 테스트 회원 가입 + 로그인"
RUN_ID=$(date +%s)
EMAIL="cbdemo_${RUN_ID}@example.com"
MEMBER_PW="Cb${RUN_ID}Aa1!"
PHONE="0109999${RUN_ID: -4}"

curl -s -X POST "${BASE_URL}/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${MEMBER_PW}\",\"name\":\"서킷데모\",\"phone\":\"${PHONE}\"}" > /dev/null

TOKEN=$(curl -s -X POST "${BASE_URL}/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${MEMBER_PW}\"}" | jq -r '.data.accessToken')
echo "    로그인 완료, accessToken 확보"

echo "==> 5) 예약 생성 (programIdx=${PROGRAM_IDX}, scheduleIdx=${SCHEDULE_IDX})"
RESV=$(curl -s -X POST "${BASE_URL}/reservations" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"programIdx\":${PROGRAM_IDX},\"scheduleIdx\":${SCHEDULE_IDX}}")
ORDER_ID=$(echo "$RESV" | jq -r '.data.orderId')
AMOUNT=$(echo "$RESV" | jq -r '.data.amount')
echo "    orderId=${ORDER_ID} amount=${AMOUNT}"

echo ""
echo "==> 6) 동일 예약에 대해 결제 승인을 반복 호출 — 서킷 OPEN 관찰"
echo "    (sliding-window=10, minimum-calls=5, failure-rate-threshold=50%)"
printf "%-6s %-10s %-10s %-25s\n" "시도" "HTTP상태" "소요(ms)" "WireMock누적수신건수"
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

for n in $(seq 1 8); do
  START=$(now_ms)
  STATUS=$(curl -s -o /tmp/confirm-resp.json -w '%{http_code}' -X POST "${BASE_URL}/payments/confirm" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d "{\"paymentKey\":\"demo-key-${n}\",\"orderId\":\"${ORDER_ID}\",\"amount\":${AMOUNT}}")
  END=$(now_ms)
  ELAPSED=$((END - START))
  WM_COUNT=$(curl -s -X POST "${WM_URL}/__admin/requests/count" -H 'Content-Type: application/json' \
    -d '{"method":"POST","urlPath":"/v1/payments/confirm"}' | jq -r '.count')
  printf "%-6s %-10s %-10s %-25s\n" "$n" "$STATUS" "$ELAPSED" "$WM_COUNT"
done

echo ""
echo "==> 7) wait-duration-in-open-state(5초) 대기 후 성공 응답으로 스텁 교체"
sleep 6
curl -s -X POST "${WM_URL}/__admin/mappings" -H 'Content-Type: application/json' -d "{
  \"request\": { \"method\": \"POST\", \"urlPath\": \"/v1/payments/confirm\" },
  \"response\": { \"status\": 200, \"jsonBody\": { \"paymentKey\": \"demo-key-success\", \"orderId\": \"${ORDER_ID}\", \"status\": \"DONE\", \"approvedAt\": \"2026-07-31T00:00:00+09:00\" }, \"headers\": { \"Content-Type\": \"application/json\" } }
}" > /dev/null
echo "    스텁을 200 성공으로 교체 완료"

echo "==> 8) HALF_OPEN 상태에서 재시도 → 성공 시 CLOSED 복귀 확인"
RECOVER_STATUS=$(curl -s -o /tmp/confirm-recover.json -w '%{http_code}' -X POST "${BASE_URL}/payments/confirm" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"paymentKey\":\"demo-key-recover\",\"orderId\":\"${ORDER_ID}\",\"amount\":${AMOUNT}}")
echo "    복구 시도 HTTP 상태: ${RECOVER_STATUS}"
cat /tmp/confirm-recover.json
echo ""

echo "==> 9) 예약 상태 확인 (CONFIRMED 여야 정상)"
RESV_IDX=$(echo "$RESV" | jq -r '.data.reservationIdx')
curl -s "${BASE_URL}/reservations/${RESV_IDX}" -H "Authorization: Bearer ${TOKEN}" | jq '.data.status'

echo ""
echo "==> 정리: WireMock 종료"
kill "$WIREMOCK_PID" 2>/dev/null || true
echo "완료. 앱 프로세스는 계속 떠 있습니다(TOSS_BASE_URL이 WireMock을 가리키는 상태이니, 이후 실사용 테스트는 앱을 다시 기동하세요)."
