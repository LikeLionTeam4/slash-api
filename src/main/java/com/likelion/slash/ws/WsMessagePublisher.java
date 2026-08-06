package com.likelion.slash.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.ws.dto.WsEnvelope;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * WSS 프레임을 전체 Pod 에 발행한다.
 *
 * <p>이벤트를 만든 Pod 이 연결을 갖고 있지 않을 수 있으므로 소켓에 직접 쓰지 않고 여기로 보낸다.
 * 연결을 보유한 Pod 의 {@link WsMessageListener} 가 받아서 내보낸다.
 * 호출부는 어느 Pod 에 연결이 있는지 알 필요가 없다.
 *
 * <p><b>전달을 보장하지 않는다.</b> Pub/Sub 은 구독자가 없으면 메시지를 버린다.
 * 대상 Pod 이 재시작 중이면 그대로 사라진다. 이것이 허용되는 이유는 {@code agent_dispatches} 가
 * 원장이고, 놓친 전달은 PENDING 으로 남아 재연결 재전송과 스윕이 복구하기 때문이다.
 * WSS 는 빠른 길이고 최종 원장은 DB 다. (문서 7.3.1 · docs/w1-06-wss-routing.md)
 *
 * <p>관련 문서: 3.4.2 · 3.6 · WBS W1-06
 */
@Component
public class WsMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(WsMessagePublisher.class);

    /**
     * 발행 Pod 표시. 추적용이며 라우팅에는 쓰지 않는다.
     *
     * <p>Kubernetes 에서는 Downward API 로 넣은 Pod 이름을 쓰고, 없으면(로컬) 임의 값을 만든다.
     * 값이 없어도 동작에는 영향이 없으므로 주입을 기다리지 않는다.
     */
    private final String podName = resolvePodName();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public WsMessagePublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 대상에게 프레임을 보낸다.
     *
     * @param targetId 대상의 내부 PK. 외부 노출용 {@code public_id} 가 아니다.
     * @param frame    소켓으로 나갈 프레임 객체. 봉투는 여기서 씌우고 수신 Pod 이 벗긴다.
     */
    public void send(WsTarget target, long targetId, Object frame) {
        JsonNode frameNode = objectMapper.valueToTree(frame);
        WsEnvelope envelope = new WsEnvelope(target, targetId, frameNode, SlashTime.now(), podName);

        try {
            redis.convertAndSend(target.channel(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // 발행 실패를 호출부로 올리지 않는다.
            //
            // 호출부는 대개 REST 요청 처리 중이고, 전달 기록은 이미 DB 에 PENDING 으로 남아 있다.
            // 여기서 예외를 던지면 "작업은 만들어졌는데 요청은 실패"로 보이고, 사용자가 재시도하면
            // 같은 작업이 다시 만들어진다. 화면 반영이 늦어지는 편이 낫다.
            log.error("WSS 프레임 발행 실패 target={} targetId={}: {}", target, targetId, e.getMessage());
        }
    }

    private static String resolvePodName() {
        String fromEnv = System.getenv("POD_NAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return "local-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
