-- ---------------------------------------------------------------------------
-- V002 : updated_at 자동 갱신 함수
--
--   DEFAULT now() 는 INSERT 에만 적용되므로 UPDATE 시에는 값이 그대로 남는다.
--   갱신을 애플리케이션에 맡기면 빠뜨리기 쉬우므로 트리거로 강제한다.
--
--   updated_at 을 가진 표에 아래처럼 트리거를 붙여 재사용한다.
--
--     CREATE TRIGGER trg_{표이름}_set_updated_at
--         BEFORE UPDATE ON {표이름}
--         FOR EACH ROW
--         EXECUTE FUNCTION set_updated_at();
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- 값이 실제로 바뀐 경우에만 갱신해 불필요한 쓰기를 줄인다.
    IF NEW IS DISTINCT FROM OLD THEN
        NEW.updated_at = now();
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION set_updated_at() IS 'UPDATE 시 updated_at 을 현재 시각으로 갱신한다.';

-- users 에 적용
CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
