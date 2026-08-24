// 접수 부하 — /summary 성공 경로. NLU 분석 + CPU 추출 요약 + 결과 저장까지 간다.
// 실사용에 가장 가까운 쓰기 경로다.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8080';
const VUS = parseInt(__ENV.VUS || '10');
const DUR = __ENV.DUR || '30s';

// 요약은 공백 제외 150자 이상이어야 한다. 매번 같은 문장이면 캐시·중복 효과가 섞이므로
// VU·반복마다 끝을 다르게 한다.
const 원문 = '슬래시는 사용자가 브라우저에서 자연어로 요청하면 서버가 그 뜻을 해석해 등록된 PC 로 작업을 보내고 결과를 다시 화면으로 돌려주는 서비스입니다. '.repeat(3);

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: VUS, duration: DUR } },
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const user = 'load' + String((__VU % 20) + 1).padStart(2, '0');
  const res = http.post(`${BASE}/api/v1/requests`,
    JSON.stringify({ text: '/summary ' + 원문 + ' 일련번호 ' + __VU + '-' + __ITER }), {
      headers: { Authorization: `Bearer ${user}`, 'Content-Type': 'application/json' },
    });
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
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
