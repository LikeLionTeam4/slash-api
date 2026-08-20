-- ---------------------------------------------------------------------------
-- V012 : 기기 프로젝트 폴더 (P0-B · CODE_ANALYSIS)
--
--   Agent 가 READY 프레임의 projectWorkspaces 로 보고한 목록이다.
--   CODE_ANALYSIS(/code) 의 필수 인자 workspaceId 를 서버가 여기서 고른다.
--
--   보고 형태 (slash-runner 의 agent.py _build_ready):
--     {"workspaceId": "ws-…", "displayName": "slash-api", "workspaceType": "GIT_REPOSITORY",
--      "availableCodeAdapters": ["CLAUDE_CODE", "CODEX"]}
--
--   device_search_folders(V009) 와 같은 설계다. 실제 경로 열이 없는 것도 같은 이유다 —
--   Agent 는 경로를 자기만 들고 있고 서버에는 식별자와 사람용 이름만 보낸다.
-- ---------------------------------------------------------------------------

CREATE TABLE device_project_workspaces (
    device_id                bigint       NOT NULL,
    -- Agent 가 발급한 식별자를 그대로 저장한다. 서버가 만들지 않는다.
    workspace_id             varchar(100) NOT NULL,
    display_name             varchar(200) NOT NULL,
    workspace_type           varchar(20)  NOT NULL,

    -- 그 폴더에서 지금 쓸 수 있는 도구. Agent 가 CLI 설치 여부를 보고 채운다.
    --
    -- 배열로 두는 이유 — 값이 두 개짜리 고정 집합의 부분집합이고, 폴더를 벗어나 홀로 뜻을
    -- 가지지 않는다. 표를 따로 두면 폴더 하나를 읽을 때마다 조인이 붙는데 그만한 값이 없다.
    -- 빈 배열이면 그 폴더로는 분석할 수 없다 (Agent 가 CODE_AGENT_NOT_CONFIGURED 로 거절한다).
    available_code_adapters  text[]       NOT NULL DEFAULT '{}',

    reported_at              timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_device_project_workspaces PRIMARY KEY (device_id, workspace_id),

    CONSTRAINT fk_device_project_workspaces_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE,

    -- slash-runner 의 ProjectWorkspaceConfig.from_root_path 가 정하는 두 값이다.
    CONSTRAINT ck_device_project_workspaces_type
        CHECK (workspace_type IN ('GIT_REPOSITORY', 'DIRECTORY')),

    -- slash-runner 의 code_adapters.RUNNERS 열쇠와 같아야 한다.
    -- 목록 밖의 값이 섞이면 Agent 가 그 폴더를 쓰지 못한다.
    CONSTRAINT ck_device_project_workspaces_adapters
        CHECK (available_code_adapters <@ ARRAY['CLAUDE_CODE', 'CODEX']::text[])
);

-- 조회는 언제나 device_id 로 먼저 좁힌다. PK 선두 열이라 그 인덱스가 그대로 쓰인다.
-- 기기 한 대의 프로젝트 폴더는 많아야 수십 개다. (device_search_folders 와 같은 판단)

COMMENT ON TABLE  device_project_workspaces
    IS 'Agent 가 READY 프레임의 projectWorkspaces 로 보고한 프로젝트 폴더. 실제 경로는 받지 않는다.';
COMMENT ON COLUMN device_project_workspaces.workspace_id
    IS 'Agent 가 발급한 식별자. 서버는 만들지 않고 받은 값을 TASK 파라미터로 그대로 돌려준다.';
COMMENT ON COLUMN device_project_workspaces.available_code_adapters
    IS '그 폴더에서 쓸 수 있는 도구. 비어 있으면 분석할 수 없어 서버가 고르지 않는다.';
