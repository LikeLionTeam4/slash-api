-- ---------------------------------------------------------------------------
-- V008 : 기기 Token (W1-02)
--
--   Agent 가 페어링을 마치면 받는 접속 자격이다. WSS 접속과 세션 재발급에 사용한다.
--
--   원문은 저장하지 않는다. 등록 코드(code_hash)와 같은 이유다.
--   DB 가 유출돼도 그 값으로 남의 PC 에 연결할 수 없어야 한다.
--   대조는 받은 Token 을 해시해서 비교하는 방식으로 한다.
-- ---------------------------------------------------------------------------

ALTER TABLE devices
    -- SHA-256 을 hex 로 적으면 64자다.
    ADD COLUMN device_token_hash       varchar(64),
    ADD COLUMN device_token_expires_at timestamptz;

-- Token 으로 기기를 찾는 것이 WSS 접속마다 일어난다.
-- UNIQUE 로 두어 조회를 빠르게 하면서 서로 다른 기기가 같은 Token 을 갖는 것도 막는다.
CREATE UNIQUE INDEX uk_devices_token_hash
    ON devices (device_token_hash)
    WHERE device_token_hash IS NOT NULL;

-- 만료 시각 없이 Token 만 있는 상태는 만료를 판정할 수 없어 영구 Token 이 된다.
-- 둘은 항상 함께 있거나 함께 없어야 한다.
ALTER TABLE devices
    ADD CONSTRAINT ck_devices_token_pair
        CHECK ((device_token_hash IS NULL) = (device_token_expires_at IS NULL));

COMMENT ON COLUMN devices.device_token_hash
    IS '기기 Token 의 SHA-256(hex). 원문은 어떤 경우에도 저장하지 않는다.';
COMMENT ON COLUMN devices.device_token_expires_at
    IS '기기 Token 만료 시각. 재발급은 POST /api/v1/agent/sessions/refresh 가 처리한다.';

-- ---------------------------------------------------------------------------
-- 등록 코드 조회 인덱스
--
--   Agent 가 보낸 코드로 등록 요청을 찾는 것이 페어링의 첫 단계인데,
--   code_hash 에 인덱스가 없어 표 전체를 훑고 있었다. 활성 건만 찾으면 되므로
--   부분 인덱스로 둔다.
-- ---------------------------------------------------------------------------
CREATE INDEX idx_pairing_code_hash
    ON device_pairing_requests (code_hash)
    WHERE status = 'PENDING';
