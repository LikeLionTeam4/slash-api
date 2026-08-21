-- ---------------------------------------------------------------------------
-- V013 : 실제 실행 위치를 기록할 자리를 만든다. (slash-docs#3 · LLM 실행 구조 전환)
--
--   지금까지 실행 위치는 작업 유형에서 파생된 상수였다. TEXT_SUMMARY 하나가
--   브라우저·PC·서버 셋 중 하나에서 실행되기 시작하면 그 방식으로는 표현할 수 없다.
--
--   processing_route 를 재정의하지 않고 새 열을 두는 이유
--     그 열은 이미 GET /api/v1/tasks 로 나가고 있다. 뜻을 바꾸면 같은 열에서
--     과거 행은 "유형에서 파생된 상수", 새 행은 "실제로 실행된 곳"이 되어
--     화면이 과거 이력에 틀린 답을 하게 된다.
--
--   과거 행을 채우지 않는 이유
--     LOCAL_AGENT·BACKEND_SERVICE 는 그대로 옮길 수 있지만 LLM_SERVICE 는 아니다.
--     그것은 GPU EC2 의 Gemma 이고 새 BACKEND 는 slash-nlu 의 CPU 추출 요약이다.
--     같은 값으로 채우면 둘을 구분할 수 없게 된다. 비워 두면 "모른다"로 읽힌다.
-- ---------------------------------------------------------------------------

ALTER TABLE tasks
    ADD COLUMN execution_target varchar(20);

COMMENT ON COLUMN tasks.execution_target IS
    '작업을 실제로 실행한 주체. slash-api 가 정한다. V013 이전 행은 비어 있다.';

ALTER TABLE tasks
    ADD CONSTRAINT ck_tasks_execution_target
        CHECK (execution_target IS NULL OR execution_target IN ('BROWSER', 'RUNNER', 'BACKEND'));

-- 실행 위치가 RUNNER 이면 대상 기기가 반드시 있어야 한다.
-- ck_tasks_local_agent_requires_device 가 processing_route 로 하던 것과 같은 보호다.
-- 두 열이 함께 채워지는 동안에는 둘 다 둔다.
ALTER TABLE tasks
    ADD CONSTRAINT ck_tasks_runner_requires_device
        CHECK (execution_target IS DISTINCT FROM 'RUNNER' OR device_id IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 실행기가 TEXT_SUMMARY 를 지원한다고 보고할 수 있게 한다.
--
--   지금 실행기(SUPPORTED_TASK_TYPES)는 아직 이 유형을 보고하지 않는다. 보고하기
--   시작했을 때 저장 단계에서 막히지 않도록 제약을 먼저 푼다. 서버가 능력 목록에
--   넣을지는 TaskType.isAgentCapability() 가 정한다.
-- ---------------------------------------------------------------------------

ALTER TABLE device_capabilities
    DROP CONSTRAINT ck_device_capabilities_task_type;

ALTER TABLE device_capabilities
    ADD CONSTRAINT ck_device_capabilities_task_type
        CHECK (task_type IN ('FILE_SEARCH', 'FILE_OPEN', 'SYSTEM_STATUS',
                             'CODE_ANALYSIS', 'AI_AGENT_USAGE', 'TEXT_SUMMARY'));
