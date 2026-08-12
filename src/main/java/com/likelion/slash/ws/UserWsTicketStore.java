package com.likelion.slash.ws;

import com.likelion.slash.common.Sha256;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자 WSS 접속표 보관소. (Valkey · 메시지 스펙 §4.4 · WBS W1-06)
 *
 * <p><b>왜 Access Token 을 그냥 쓰지 않는가</b> — 브라우저 {@code WebSocket} API 는
 * {@code Authorization} 헤더를 붙일 수 없어 자격을 URL 질의 문자열에 실어야 한다. URL 은
 * 프록시·로드밸런서의 접근 로그에 그대로 남는다. 몇 시간짜리 Access Token 이 로그에 남으면
 * 그 로그를 읽을 수 있는 사람이 그 시간 내내 사용자 행세를 할 수 있다. 그래서 <b>30초·1회용</b>
 * 접속표를 따로 발급한다. 새어 나가도 이미 만료됐거나 이미 쓰인 값이다.
 *
 * <p><b>Pod 메모리에 둘 수 없다.</b> 발급은 REST(Pod A), 접속은 WSS(Pod B)로 갈라진다.
 * 페어링 도전값과 같은 이유이고, 문서 3.8 이 허용하는 짧게 사는 공유 상태다.
 *
 * <p><b>원문을 저장하지 않는다.</b> 해시를 키로 쓴다. Valkey 가 통째로 새더라도 그것만으로는
 * 접속할 수 없다. 기기 Token 을 {@code Sha256.hex} 로 저장하는 것과 같은 이유다.
 */
@Component
public class UserWsTicketStore {

    private static final Logger log = LoggerFactory.getLogger(UserWsTicketStore.class);

    private static final String KEY_PREFIX = "ws:ticket:";

    private static final int TICKET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public UserWsTicketStore(StringRedisTemplate redis,
                             @Value("${slash.websocket.ticket-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** 계약(§7)이 약속한 유효 시간. 프론트가 재발급 주기를 잡는 데 쓰도록 응답에 함께 담는다. */
    public Duration ttl() {
        return ttl;
    }

    /**
     * 접속표를 발급한다.
     *
     * @return 원문. <b>이 반환값에서만 볼 수 있고 저장되지 않는다.</b>
     */
    public String issue(long userId) {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        try {
            redis.opsForValue().set(key(ticket), Long.toString(userId), ttl);
        } catch (Exception e) {
            // 저장하지 못하면 뒤이은 접속이 반드시 실패한다. 조용히 넘기면 프론트는
            // "인증 실패"만 보고 원인을 알 수 없다.
            throw new IllegalStateException("접속표를 저장하지 못했습니다.", e);
        }
        return ticket;
    }

    /**
     * 접속표를 쓰고 지운다.
     *
     * <p><b>1회용이다.</b> 지우지 않으면 접근 로그에서 주운 값으로 30초 안에 여러 번 붙을 수 있다.
     * {@code getAndDelete} 는 원자적이라 같은 표로 동시에 들어온 두 접속 중 하나만 성공한다.
     *
     * @return 표의 주인. 비어 있으면 만료됐거나 이미 쓴 표다. 둘을 구분해 알리지 않는다.
     */
    public Optional<Long> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            String userId = redis.opsForValue().getAndDelete(key(ticket));
            return userId == null ? Optional.empty() : Optional.of(Long.valueOf(userId));

        } catch (Exception e) {
            log.warn("접속표를 읽지 못했습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(String ticket) {
        return KEY_PREFIX + Sha256.hex(ticket);
    }
}
