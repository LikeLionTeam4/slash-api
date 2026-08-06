package com.likelion.slash.pairing;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 세션 재발급 nonce 의 1회 사용을 강제한다. (메시지 스펙 §8.1 3단계)
 *
 * <p>서명을 한 번 가로챈 쪽이 같은 요청을 그대로 재생해 Token 을 계속 받아 가는 것을 막는다.
 * {@code requestedAt} 허용 오차와 짝이다 — 오차 범위 안에서는 재생이 가능하므로
 * nonce 를 기억해 두 번째를 거부해야 한다.
 *
 * <p>보관 기간은 허용 오차보다 넉넉하게 둔다. 오차 범위를 벗어난 요청은 어차피 시각 검사에서
 * 걸리므로 그보다 오래 기억할 이유가 없다.
 */
@Component
public class RefreshNonceStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshNonceStore.class);

    private static final String KEY_PREFIX = "pairing:refresh-nonce:";

    /** 허용 오차의 몇 배까지 기억할지. 시계가 조금 어긋난 환경까지 감안한 여유다. */
    private static final int RETENTION_MULTIPLIER = 5;

    private final StringRedisTemplate redis;
    private final Duration retention;

    public RefreshNonceStore(StringRedisTemplate redis,
                             @Value("${slash.pairing.refresh-skew}") Duration refreshSkew) {
        this.redis = redis;
        this.retention = refreshSkew.multipliedBy(RETENTION_MULTIPLIER);
    }

    /**
     * 이 nonce 를 처음 쓰는 것이면 기록하고 true 를 준다.
     *
     * <p>Valkey 가 끊기면 <b>거절한다.</b> 확인하지 못한 채 통과시키면 재전송 공격이 그대로
     * 열린다. 여기서만은 장애 시 막는 쪽을 고른다 — 재발급은 실패해도 Agent 가 다시 시도하면 되고,
     * 기존 Token 은 만료 전까지 살아 있어 연결이 끊기지 않는다.
     */
    public boolean useOnce(UUID deviceId, UUID nonce) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(key(deviceId, nonce), "1", retention));

        } catch (Exception e) {
            log.warn("재발급 nonce 를 확인하지 못했습니다 deviceId={}: {}", deviceId, e.getMessage());
            return false;
        }
    }

    private static String key(UUID deviceId, UUID nonce) {
        return KEY_PREFIX + deviceId + ":" + nonce;
    }
}
