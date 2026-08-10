package com.likelion.slash.pairing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 페어링 도전값 보관소. (Valkey)
 *
 * <p>pair 와 verify 가 서로 다른 Pod 으로 갈 수 있으므로 Pod 메모리에 둘 수 없다.
 * 대기열이 아니라 짧게 사는 공유 상태이므로 문서 3.8 이 허용하는 용도다.
 *
 * <p><b>소비하면 지운다.</b> 같은 도전값으로 두 번 인증하는 것을 막는다.
 * 지우지 않으면 서명 한 번을 가로챈 쪽이 30초 안에 같은 값을 재생할 수 있다.
 */
@Component
public class PairingSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PairingSessionStore.class);

    private static final String KEY_PREFIX = "pairing:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PairingSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(PairingSession session, Duration ttl) {
        try {
            redis.opsForValue().set(key(session.pairingSessionId()), objectMapper.writeValueAsString(session), ttl);
        } catch (Exception e) {
            // 저장하지 못하면 뒤이은 verify 가 반드시 실패한다. 조용히 넘기면
            // Agent 는 "세션을 찾을 수 없음"만 보고 원인을 알 수 없다.
            throw new IllegalStateException("페어링 세션을 저장하지 못했습니다.", e);
        }
    }

    /** 찾아서 지운다. 없으면 만료됐거나 이미 쓴 것이다. */
    public Optional<PairingSession> consume(UUID pairingSessionId) {
        try {
            String raw = redis.opsForValue().getAndDelete(key(pairingSessionId));
            return raw == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(raw, PairingSession.class));

        } catch (Exception e) {
            log.warn("페어링 세션을 읽지 못했습니다 sessionId={}: {}", pairingSessionId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(UUID pairingSessionId) {
        return KEY_PREFIX + pairingSessionId;
    }
}
