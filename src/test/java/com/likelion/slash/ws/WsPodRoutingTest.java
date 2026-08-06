package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pod 간 WSS 라우팅 확인. (WBS W1-06)
 *
 * <p>Valkey Pub/Sub 으로 실제 발행하고 구독해서, 봉투가 벗겨진 프레임이 연결로 나가는지 본다.
 * 이 시험은 Valkey 가 떠 있어야 한다. ({@code docker compose up -d})
 *
 * <p><b>한계</b> — 한 JVM 안에서는 "다른 Pod 이 발행하고 이 Pod 이 내보내는" 상황을
 * 그대로 만들 수 없다. 발행과 구독이 같은 채널을 지나는 것까지만 확인한다.
 * 진짜 멀티 Pod 확인은 포트를 달리해 두 개를 띄워야 한다.
 * (docs/w1-06-wss-routing.md 5.6 — 이 시험이 통과해도 그 확인을 건너뛰면 안 된다)
 */
@SpringBootTest
class WsPodRoutingTest {

    /** 다른 시험이 쓰는 값과 겹치지 않도록 실행마다 다른 대상을 쓴다. */
    private final long 기기 = System.nanoTime();
    private final long 연결이_없는_기기 = 기기 + 1;

    @Autowired
    private WsMessagePublisher publisher;

    @Autowired
    private WsSessionRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            registry.unregister(WsTarget.DEVICE, 기기, session.getId());
        }
    }

    @Test
    @DisplayName("발행한 프레임이 연결을 보유한 Pod 의 소켓으로 나간다")
    void 발행하면_연결로_나간다() throws Exception {
        session = 세션();
        registry.register(WsTarget.DEVICE, 기기, session);

        UUID dispatchId = UUID.randomUUID();
        publisher.send(WsTarget.DEVICE, 기기, 작업_프레임(dispatchId));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(5_000)).sendMessage(captor.capture());

        // 봉투(target·originPod 등)는 벗겨지고 프레임만 나가야 한다.
        var delivered = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(delivered.path("type").asText()).isEqualTo("TASK");
        assertThat(delivered.path("dispatchId").asText()).isEqualTo(dispatchId.toString());
        assertThat(delivered.has("targetId")).isFalse();
        assertThat(delivered.has("originPod")).isFalse();
    }

    @Test
    @DisplayName("연결을 들고 있지 않은 대상의 프레임은 버린다")
    void 남의_대상은_버린다() throws Exception {
        session = 세션();
        registry.register(WsTarget.DEVICE, 기기, session);

        UUID 버려질_전달 = UUID.randomUUID();
        UUID 전달될_전달 = UUID.randomUUID();

        // 같은 채널에 순서대로 발행한다. 뒤엣것이 도착했다면 앞엣것도 이미 지나갔다.
        publisher.send(WsTarget.DEVICE, 연결이_없는_기기, 작업_프레임(버려질_전달));
        publisher.send(WsTarget.DEVICE, 기기, 작업_프레임(전달될_전달));

        // 전송이 한 번뿐이어야 한다. 두 번이면 남의 대상 프레임까지 나간 것이다.
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(5_000)).sendMessage(captor.capture());

        assertThat(objectMapper.readTree(captor.getValue().getPayload()).path("dispatchId").asText())
                .isEqualTo(전달될_전달.toString());
        assertThat(registry.holds(WsTarget.DEVICE, 연결이_없는_기기)).isFalse();
    }

    private static Map<String, Object> 작업_프레임(UUID dispatchId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "TASK");
        frame.put("dispatchId", dispatchId.toString());
        return frame;
    }

    private static WebSocketSession 세션() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
