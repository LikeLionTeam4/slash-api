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
 *
 * <p><b>두 겹으로 센다.</b>
 * <ul>
 *   <li>호출자별 — 한 곳에서 반복해 찌르는 것을 막는다. 판정 기준은
 *       {@link com.likelion.slash.common.ClientAddressResolver} 다</li>
 *   <li>서비스 전체 — 주소를 바꿔 가며 찌르는 것을 막는다</li>
 * </ul>
 *
 * <p>호출자별 제한만 두면 주소를 바꿀 수 있는 공격자에게는 없는 것과 같다.
 * 전체 제한은 그걸 받치는 마지막 그물이라 한도를 넉넉히 잡는다 — 평상시 실패는 사용자가
 * 코드를 잘못 옮겨 적는 정도라 몇 건에 그친다. 한도에 닿았다는 것 자체가 공격 신호다.
 *
 * <p>대신 전체 제한이 걸리면 그동안 <b>정상 등록도 함께 막힌다.</b> 공격자가 일부러
 * 한도를 채워 등록을 막을 수 있다는 뜻이다. 등록이 몇 분 늦는 쪽이 남의 계정에 PC 가
 * 붙는 쪽보다 낫다고 보고 받아들인 절충이다. {@code global-max-attempts} 로 조절한다.
 */
@Component
public class PairingAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(PairingAttemptLimiter.class);

    private static final String KEY_PREFIX = "pairing:attempt:";

    /** 서비스 전체 실패를 모으는 자리. 호출자 주소가 들어가는 자리와 겹치지 않는 이름이다. */
    private static final String GLOBAL_KEY = KEY_PREFIX + "@global";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final int globalMaxAttempts;
    private final Duration window;

    public PairingAttemptLimiter(StringRedisTemplate redis,
                                 @Value("${slash.pairing.max-attempts}") int maxAttempts,
                                 @Value("${slash.pairing.global-max-attempts}") int globalMaxAttempts,
                                 @Value("${slash.pairing.attempt-window}") Duration window) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.globalMaxAttempts = globalMaxAttempts;
        this.window = window;
    }

    /** 한도를 넘었는지. 넘었으면 코드를 대조하지 않고 거절한다. */
    public boolean isBlocked(String client) {
        try {
            if (countOf(key(client)) >= maxAttempts) {
                return true;
            }

            if (countOf(GLOBAL_KEY) >= globalMaxAttempts) {
                // 주소를 바꿔 가며 찌르고 있다는 뜻이다. 조사할 수 있도록 남긴다.
                log.warn("페어링 실패가 서비스 전체 한도에 닿았습니다. 무차별 대입일 수 있습니다.");
                return true;
            }

            return false;

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
            increment(key(client));
            increment(GLOBAL_KEY);
        } catch (Exception e) {
            log.warn("페어링 실패를 기록하지 못했습니다: {}", e.getMessage());
        }
    }

    /**
     * 등록에 성공했으니 이 호출자의 기록을 지운다.
     *
     * <p>전체 집계는 지우지 않는다. 공격자가 코드 하나만 맞혀도 그때까지 쌓인 실패가
     * 사라지면 전체 제한이 의미를 잃는다.
     */
    public void reset(String client) {
        try {
            redis.delete(key(client));
        } catch (Exception e) {
            log.warn("페어링 시도 기록을 지우지 못했습니다: {}", e.getMessage());
        }
    }

    private long countOf(String key) {
        String count = redis.opsForValue().get(key);
        return count == null ? 0L : Long.parseLong(count);
    }

    private void increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }

    private static String key(String client) {
        return KEY_PREFIX + client;
    }
}
