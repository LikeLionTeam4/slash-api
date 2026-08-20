-- ---------------------------------------------------------------------------
-- V011 : FILE_OPEN 작업 유형을 허용한다. (P0-B "파일 열기와 Explorer·Finder 위치 표시")
--
--   PC 실행기는 이미 이 작업을 지원한다(SUPPORTED_TASK_TYPES). 서버가 CHECK 제약으로
--   막고 있어서 저장도 보고도 되지 않았다.
--
--   두 곳을 함께 푼다.
--     tasks.task_type               작업을 저장할 수 있게
--     device_capabilities.task_type Agent 가 READY 로 보고한 것을 저장할 수 있게
-- ---------------------------------------------------------------------------

ALTER TABLE tasks
    DROP CONSTRAINT ck_tasks_task_type;

ALTER TABLE tasks
    ADD CONSTRAINT ck_tasks_task_type
        CHECK (task_type IS NULL OR task_type IN ('WEATHER_LOOKUP', 'FILE_SEARCH', 'FILE_OPEN',
                                                  'SYSTEM_STATUS', 'TEXT_SUMMARY',
                                                  'CODE_ANALYSIS', 'AI_AGENT_USAGE'));

ALTER TABLE device_capabilities
    DROP CONSTRAINT ck_device_capabilities_task_type;

ALTER TABLE device_capabilities
    ADD CONSTRAINT ck_device_capabilities_task_type
        CHECK (task_type IN ('FILE_SEARCH', 'FILE_OPEN', 'SYSTEM_STATUS',
                             'CODE_ANALYSIS', 'AI_AGENT_USAGE'));
