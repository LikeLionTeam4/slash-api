// 이력 조회 부하 — 외부 의존이 없는 순수 읽기 경로.
// 인증(사용자 조회) + tasks 커서 페이징만 탄다. 서버·DB 자체의 한계를 본다.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8080';
const VUS = parseInt(__ENV.VUS || '10');
const DUR = __ENV.DUR || '30s';

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: VUS, duration: DUR } },
  // 기본 임계값을 두지 않는다 — 여기서는 끊는 것이 아니라 재는 것이 목적이다.
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const user = 'load' + String((__VU % 20) + 1).padStart(2, '0');
  const res = http.get(`${BASE}/api/v1/tasks?limit=20`, {
    headers: { Authorization: `Bearer ${user}` },
  });
  check(res, { '200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const out = {
    vus: parseInt(__ENV.VUS || '10'),
    반복수: m.iterations ? m.iterations.values.count : 0,
    초당요청: m.http_reqs ? +m.http_reqs.values.rate.toFixed(1) : 0,
    실패율: m.http_req_failed ? +(m.http_req_failed.values.rate * 100).toFixed(2) : 0,
    avg: m.http_req_duration ? +m.http_req_duration.values.avg.toFixed(1) : 0,
    med: m.http_req_duration ? +m.http_req_duration.values.med.toFixed(1) : 0,
    p95: m.http_req_duration ? +m.http_req_duration.values['p(95)'].toFixed(1) : 0,
    p99: m.http_req_duration ? +m.http_req_duration.values['p(99)'].toFixed(1) : 0,
    max: m.http_req_duration ? +m.http_req_duration.values.max.toFixed(1) : 0,
  };
  return { [`${__ENV.OUT || '/tmp/k6'}.json`]: JSON.stringify(out) };
}
