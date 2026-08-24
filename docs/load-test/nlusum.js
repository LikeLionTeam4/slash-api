// CPU 추출 요약 엔드포인트 직접 부하 — 쓰기 경로의 진짜 병목을 가르는 대조군.
import http from 'k6/http';
import { check } from 'k6';
const VUS = parseInt(__ENV.VUS || '10');
const 원문 = '슬래시는 사용자가 브라우저에서 자연어로 요청하면 서버가 그 뜻을 해석해 등록된 PC 로 작업을 보내고 결과를 다시 화면으로 돌려주는 서비스입니다. '.repeat(3);
export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: VUS, duration: __ENV.DUR || '20s' } },
  thresholds: {},
  summaryTrendStats: ['avg','min','med','p(95)','p(99)','max'],
};
export default function () {
  const res = http.post('http://127.0.0.1:8001/internal/v1/nlu/summaries/extractive',
    JSON.stringify({ requestId: 'r'+__VU+'-'+__ITER, taskId: 't'+__VU+'-'+__ITER, text: 원문 }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { '200': (r) => r.status === 200 });
}
export function handleSummary(data) {
  const m = data.metrics;
  return { [`${__ENV.OUT}.json`]: JSON.stringify({
    vus: parseInt(__ENV.VUS||'10'),
    초당요청: +m.http_reqs.values.rate.toFixed(1),
    실패율: +(m.http_req_failed.values.rate*100).toFixed(2),
    avg: +m.http_req_duration.values.avg.toFixed(1),
    p95: +m.http_req_duration.values['p(95)'].toFixed(1),
    max: +m.http_req_duration.values.max.toFixed(1) }) };
}
