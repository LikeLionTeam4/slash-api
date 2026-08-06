package com.likelion.slash.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.dispatch.DispatchDeliveryRecorder;
import com.likelion.slash.ws.dto.WsEnvelope;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 다른 Pod 이 발행한 WSS 프레임을 받아, 이 Pod 이 연결을 들고 있을 때만 내보낸다.
 *
 * <p>브로드캐스트라 모든 Pod 이 모든 메시지를 받는다. 대부분은 자기 것이 아니므로 그냥 버린다.
 * 버리는 것이 정상 동작이라 로그를 남기지 않는다. (Pod 수만큼 로그가 불어난다)
 *
 * <p>자기가 발행한 메시지도 되돌아오지만 거르지 않는다. 발행한 Pod 이 연결을 들고 있다면
 * 그 Pod 이 보내는 것이 맞기 때문이다.
 *
 * <p>관련 문서: 3.4.2 · WBS W1-06
 */
@Component
public class WsMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WsMessageListener.class);

    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final DispatchDeliveryRecorder dispatchDeliveryRecorder;

    public WsMessageListener(WsSessionRegistry registry,
                             ObjectMapper objectMapper,
                             DispatchDeliveryRecorder dispatchDeliveryRecorder) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.dispatchDeliveryRecorder = dispatchDeliveryRecorder;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            WsEnvelope envelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), WsEnvelope.class);

            if (!registry.holds(envelope.target(), envelope.targetId())) {
                return;
            }

            // 봉투를 벗기고 프레임만 내보낸다. 라우팅 정보는 클라이언트가 알 필요가 없다.
            int sent = registry.deliver(
                    envelope.target(), envelope.targetId(), objectMapper.writeValueAsString(envelope.frame()));

            log.debug("WSS 프레임 전달 target={} targetId={} 연결={} 발행Pod={}",
                    envelope.target(), envelope.targetId(), sent, envelope.originPod());

            // 실제로 나간 뒤에 원장을 갱신한다. 발행만으로 갱신하면 아무도 받지 못한 전달이
            // 보낸 것으로 남아 스윕 대상에서 빠진다.
            if (sent > 0) {
                dispatchDeliveryRecorder.recordDelivered(envelope.frame());
            }

        } catch (Exception e) {
            // 구독 스레드에서 예외가 나가면 컨테이너가 구독을 재설정하며 그 사이 메시지를 놓친다.
            // 한 건의 문제로 Pod 전체의 전달이 끊기지 않도록 여기서 막는다.
            log.error("WSS 프레임 수신 처리 실패: {}", e.getMessage());
        }
    }
}
