// NLU 직접 부하 — 위 두 시나리오에서 병목이 서버인지 NLU 인지 가르기 위한 대조군.
import http from 'k6/http';
import { check } from 'k6';

const VUS = parseInt(__ENV.VUS || '10');
const DUR = __ENV.DUR || '30s';
const 원문 = '슬래시는 사용자가 브라우저에서 자연어로 요청하면 서버가 그 뜻을 해석해 등록된 PC 로 작업을 보내고 결과를 다시 화면으로 돌려주는 서비스입니다. '.repeat(3);

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: VUS, duration: DUR } },
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.post('http://127.0.0.1:8001/internal/v1/nlu/analyze',
    JSON.stringify({ requestId: 'load-' + __VU + '-' + __ITER,
                     command: { path: ['summary'], operands: [원문] } }),
    { headers: { 'Content-Type': 'application/json' } });
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
