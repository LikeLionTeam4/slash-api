-- 이력 조회 경로의 DB 자체 상한을 재는 pgbench 스크립트.
--
--   docker cp docs/load-test/history.sql slash-postgres:/tmp/history.sql
--   docker exec slash-postgres pgbench -U slash -d slash -f /tmp/history.sql -c 40 -j 4 -T 20 -n
--
-- API 한 요청과 **같은 쿼리 두 개**를 낸다. 앱을 거치지 않으므로 이 값이 DB 쪽 천장이다.
--   1) 인증 — AuthenticatedUserService.current() 의 findByCognitoSub
--   2) 목록 — TaskRepository.findRecent 의 HISTORY_COLUMNS 조회 (커서 없음 · limit 20)
--
-- seed.sql 로 만든 load01~load20 을 쓴다. 먼저 시드해야 한다.
-- 요약(tps 줄)은 stderr 로 나온다 — 2>/dev/null 로 버리지 말 것.

\set n random(1, 20)

SELECT * FROM users WHERE cognito_sub = 'load' || lpad(:n::text, 2, '0') \gset

SELECT id, public_id, device_id, input_text, request_summary,
       task_type, execution_target, status, error_code, created_at, completed_at
  FROM tasks
 WHERE user_id = :id
 ORDER BY created_at DESC, id DESC
 LIMIT 20;
