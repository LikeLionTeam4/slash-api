package com.likelion.slash.pairing;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 등록 코드 대조 시도 횟수 제한.
 *
 * <p><b>왜 필요한가</b> — 등록 코드는 6자리 숫자다. 경우의 수가 백만뿐이라 5분 안에도
 * 전부 시도해 볼 수 있다. 맞히면 남의 PC 가 자기 계정에 붙는 것이 아니라
 * <b>자기 PC 가 남의 계정에 등록</b>되므로, 그 사람의 작업이 공격자 PC 로 나간다.
 * 해시 저장은 DB 유출에 대한 방어이지 이 공격을 막지 못한다. 방어선은 시도 횟수 제한이다.
 *
 * <p>Pod 마다 세면 한도가 Pod 수만큼 늘어나므로 Valkey 에 모아 센다.
 *
 * <p>막는 기준은 <b>실패</b> 횟수다. 성공한 등록까지 세면 PC 를 여러 대 등록하는
 * 정상 사용자가 막힌다.
 */
@Component
public class PairingAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(PairingAttemptLimiter.class);

    private static final String KEY_PREFIX = "pairing:attempt:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration window;

    public PairingAttemptLimiter(StringRedisTemplate redis,
                                 @Value("${slash.pairing.max-attempts}") int maxAttempts,
                                 @Value("${slash.pairing.attempt-window}") Duration window) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    /** 한도를 넘었는지. 넘었으면 코드를 대조하지 않고 거절한다. */
    public boolean isBlocked(String client) {
        try {
            String count = redis.opsForValue().get(key(client));
            return count != null && Integer.parseInt(count) >= maxAttempts;

        } catch (Exception e) {
            // Valkey 가 끊겼다고 등록 자체를 막지는 않는다.
            // 여기서 막으면 장애가 곧바로 "PC 를 등록할 수 없음"으로 번진다.
            log.warn("페어링 시도 횟수를 확인하지 못했습니다: {}", e.getMessage());
            return false;
        }
    }

    /** 코드가 틀렸다. 창(window)은 첫 실패를 기준으로 시작한다. */
    public void recordFailure(String client) {
        try {
            Long count = redis.opsForValue().increment(key(client));
            if (count != null && count == 1L) {
                redis.expire(key(client), window);
            }
        } catch (Exception e) {
            log.warn("페어링 실패를 기록하지 못했습니다: {}", e.getMessage());
        }
    }

    /** 등록에 성공했으니 기록을 지운다. */
    public void reset(String client) {
        try {
            redis.delete(key(client));
        } catch (Exception e) {
            log.warn("페어링 시도 기록을 지우지 못했습니다: {}", e.getMessage());
        }
    }

    private static String key(String client) {
        return KEY_PREFIX + client;
    }
}
