-- 부하시험용 시드. 개발용 자료와 섞이지 않게 `load` 로 시작하는 사용자만 쓴다.
--
--   docker exec -i slash-postgres psql -U slash -d slash < docs/load-test/seed.sql
--
-- 되돌리려면 맨 아래 정리 문장을 쓴다.

INSERT INTO users (cognito_sub, email, display_name)
SELECT 'load' || lpad(n::text, 2, '0'),
       'load' || lpad(n::text, 2, '0') || '@local.test',
       '부하시험 ' || n
FROM generate_series(1, 20) n
ON CONFLICT (cognito_sub) DO NOTHING;

-- 이력 조회 시나리오는 읽을 것이 있어야 의미가 있다. 사용자당 500건.
INSERT INTO tasks (user_id, input_text, request_summary, task_type, processing_route,
                   execution_target, status, result, completed_at, created_at)
SELECT u.id,
       '/summary 부하시험용 원문 ' || s.i,
       '부하시험용 원문 ' || s.i,
       'TEXT_SUMMARY', 'LLM_SERVICE', 'BACKEND', 'SUCCEEDED',
       jsonb_build_object('summary', '요약 결과 ' || s.i, 'engine', 'EXTRACTIVE'),
       now() - (s.i || ' minutes')::interval,
       now() - (s.i || ' minutes')::interval
FROM users u CROSS JOIN generate_series(1, 500) s(i)
WHERE u.cognito_sub LIKE 'load%';

-- 정리 (필요할 때만)
-- DELETE FROM tasks  WHERE user_id IN (SELECT id FROM users WHERE cognito_sub LIKE 'load%');
-- DELETE FROM users  WHERE cognito_sub LIKE 'load%';
