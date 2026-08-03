import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// ── 설정값 (환경변수로 override 가능) ──────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PROGRAM_IDX = Number(__ENV.PROGRAM_IDX || 3);
const SCHEDULE_IDX = Number(__ENV.SCHEDULE_IDX || 4);
const CAPACITY = Number(__ENV.CAPACITY || 5);            // schedules.capacity와 반드시 일치시킬 것
const OVERSUBSCRIBE = Number(__ENV.OVERSUBSCRIBE || 3);  // capacity의 몇 배로 동시 요청을 쏠지
const VUS = CAPACITY * OVERSUBSCRIBE;

const successCounter = new Counter('reservation_success');
const conflictCounter = new Counter('reservation_conflict_409');
const noStockCounter = new Counter('reservation_nostock_400');
const unexpectedCounter = new Counter('reservation_unexpected');

export const options = {
  scenarios: {
    concurrent_reservation: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // capacity보다 많은 201이 나오면 임계값 실패 처리 — 오버셀 회귀를 자동 감지
    reservation_success: [`count<=${CAPACITY}`],
    reservation_unexpected: ['count==0'],
  },
};

// 실행할 때마다 새로 생성되는 값 — 소스에 박아두는 고정 비밀번호가 아니라
// 이 테스트 러닝 안에서만 쓰고 버리는 임시 회원가입용 값이다.
function generateRunPassword(runId) {
  return `Lt${runId}Aa1!`;
}

// setup()은 1회만 실행 — VU 수만큼 회원가입+로그인해서 서로 다른 토큰을 미리 확보한다.
// (동일 스케줄에 같은 회원이 두 번 예약하면 409 "중복 예약"으로 막히므로,
//  분산락 자체를 테스트하려면 VU마다 반드시 다른 회원이어야 한다.)
export function setup() {
  const tokens = [];
  const runId = Date.now();
  const password = generateRunPassword(runId);

  for (let i = 0; i < VUS; i++) {
    const email = `loadtest_${runId}_${i}@example.com`;
    const phone = `010${String(10000000 + i).padStart(8, '0')}`;

    const signupRes = http.post(
      `${BASE_URL}/auth/signup`,
      JSON.stringify({ email, password, name: `부하테스트${i}`, phone }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    check(signupRes, { [`signup ${i} -> 201`]: (r) => r.status === 201 });

    const loginRes = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    check(loginRes, { [`login ${i} -> 200`]: (r) => r.status === 200 });

    tokens.push(loginRes.json('data.accessToken'));
  }

  return { tokens };
}

// 각 VU는 서로 다른 회원 토큰으로 정확히 1번씩 동시에 예약을 신청한다.
export default function (data) {
  const token = data.tokens[__VU - 1];

  const res = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({ programIdx: PROGRAM_IDX, scheduleIdx: SCHEDULE_IDX }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    }
  );

  if (res.status === 201) {
    successCounter.add(1);
  } else if (res.status === 409) {
    conflictCounter.add(1);
  } else if (res.status === 400) {
    noStockCounter.add(1);
  } else {
    unexpectedCounter.add(1);
    console.error(`예상 못한 응답: VU=${__VU} status=${res.status} body=${res.body}`);
  }

  check(res, {
    '201(성공) 또는 400/409(정상적인 재고소진·락경합)만 허용': (r) =>
      r.status === 201 || r.status === 400 || r.status === 409,
  });
}

export function handleSummary(data) {
  const success = data.metrics.reservation_success ? data.metrics.reservation_success.values.count : 0;
  const conflict = data.metrics.reservation_conflict_409 ? data.metrics.reservation_conflict_409.values.count : 0;
  const noStock = data.metrics.reservation_nostock_400 ? data.metrics.reservation_nostock_400.values.count : 0;
  const unexpected = data.metrics.reservation_unexpected ? data.metrics.reservation_unexpected.values.count : 0;

  const verdict =
    success <= CAPACITY && unexpected === 0
      ? '✅ 오버셀 없음 (분산락 정상 동작)'
      : '❌ 오버셀 또는 예상 밖 응답 발생 — 분산락 회귀 의심';

  console.log(`
====================================================
동시 예약 부하테스트 결과 (capacity=${CAPACITY}, 동시요청=${VUS})
----------------------------------------------------
성공(201):           ${success}
재고소진(400):        ${noStock}
락경합/중복(409):      ${conflict}
예상 못한 응답:        ${unexpected}
----------------------------------------------------
판정: ${verdict}
====================================================
`);

  return {};
}
