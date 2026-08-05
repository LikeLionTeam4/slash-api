package com.likelion.slash.health;

import com.likelion.slash.health.dto.DependencyHealthResponse;
import com.likelion.slash.health.dto.DependencyHealthResponse.Status;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

/**
 * RDS·Valkey 연결 상태를 점검한다.
 *
 * <p>한쪽이 끊겨도 나머지 상태는 확인할 수 있도록 각각 독립적으로 검사한다.
 */
@Service
public class DependencyHealthService {

    private static final Logger log = LoggerFactory.getLogger(DependencyHealthService.class);

    private final DSLContext dsl;
    private final RedisConnectionFactory redisConnectionFactory;

    public DependencyHealthService(DSLContext dsl, RedisConnectionFactory redisConnectionFactory) {
        this.dsl = dsl;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public DependencyHealthResponse check() {
        return DependencyHealthResponse.of(checkDatabase(), checkValkey());
    }

    private Status checkDatabase() {
        try {
            dsl.selectOne().fetch();
            return Status.UP;
        } catch (Exception e) {
            // 점검 결과로 응답하므로 예외를 밖으로 던지지 않는다.
            log.warn("데이터베이스 연결 점검 실패: {}", e.getMessage());
            return Status.DOWN;
        }
    }

    private Status checkValkey() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.ping();
            return Status.UP;
        } catch (Exception e) {
            log.warn("Valkey 연결 점검 실패: {}", e.getMessage());
            return Status.DOWN;
        }
    }
}
