-- ---------------------------------------------------------------------------
-- V009 : 기기 검색 폴더 (W1-03)
--
--   Agent 가 READY 프레임의 searchFolders 로 보고한 폴더 목록이다.
--   FILE_SEARCH(/file) 의 필수 인자 searchFolderId 를 서버가 여기서 고른다.
--
--   보고 형태 (slash-agent 의 file_index.py list_search_folders):
--     {"searchFolderId": "sf-…", "displayName": "문서", "indexStatus": "INDEXED"}
--
--   실제 파일 경로 열이 없는 것은 빠뜨린 것이 아니다.
--   Agent 는 경로를 자기만 들고 있고 서버에 보내지 않는다. 서버는 사용자 PC 의
--   디렉터리 구조를 영영 알지 못하며, 식별자만 받아 그대로 돌려준다.
--   경로 열을 두면 채울 값이 없을 뿐 아니라 이 설계 의도를 깨뜨린다.
-- ---------------------------------------------------------------------------

CREATE TABLE device_search_folders (
    device_id        bigint       NOT NULL,
    -- Agent 가 발급한 식별자를 그대로 저장한다. 서버가 만들지 않는다.
    -- TASK 파라미터로 이 값을 돌려주면 Agent 가 자기 폴더로 되찾는다.
    search_folder_id varchar(100) NOT NULL,
    display_name     varchar(200) NOT NULL,
    index_status     varchar(20)  NOT NULL,
    reported_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT pk_device_search_folders PRIMARY KEY (device_id, search_folder_id),

    CONSTRAINT fk_device_search_folders_device
        FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE,

    -- 메시지 스펙 §6.2 의 indexStatus 세 값. (slash-agent protocol.py INDEX_STATUSES)
    -- UNAVAILABLE 만 검색에서 빠진다. INDEXING 은 Agent 가 검색해 준다
    -- (file_index.py is_searchable — UNAVAILABLE 만 거른다).
    CONSTRAINT ck_device_search_folders_index_status
        CHECK (index_status IN ('INDEXED', 'INDEXING', 'UNAVAILABLE'))
);

-- 조회는 언제나 device_id 로 먼저 좁힌다. PK (device_id, search_folder_id) 의 선두 열이라
-- 그 인덱스가 그대로 쓰인다. 기기 한 대의 폴더는 많아야 수십 개라 정렬은 그 위에서 끝난다.
-- 인덱스를 따로 두지 않는 이유다.

COMMENT ON TABLE  device_search_folders
    IS 'Agent 가 READY 프레임의 searchFolders 로 보고한 검색 폴더. 실제 경로는 받지 않는다.';
COMMENT ON COLUMN device_search_folders.search_folder_id
    IS 'Agent 가 발급한 식별자. 서버는 만들지 않고 받은 값을 TASK 파라미터로 그대로 돌려준다.';
COMMENT ON COLUMN device_search_folders.display_name
    IS '사용자에게 보여줄 폴더 이름. 서버는 경로를 모르므로 사람이 알아볼 수 있는 유일한 값이다.';
COMMENT ON COLUMN device_search_folders.index_status
    IS 'UNAVAILABLE 만 검색에서 뺀다. INDEXING 은 결과가 불완전할 뿐 Agent 가 검색해 준다.';
