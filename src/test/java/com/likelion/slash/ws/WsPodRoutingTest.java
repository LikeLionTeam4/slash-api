package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
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

    /**
     * Pod 간 구독을 거는 유일한 경로다. 평소에는 주기 작업이 부르지만 시험에서는 그것을 꺼 두어
     * ({@code slash.scheduling.enabled=false} · 이슈 #31) 여기서 직접 부른다.
     *
     * <p>스윕과 달리 <b>상태를 바꾸는 작업이 아니라 초기화</b>라, 꺼 둔 채로 두면 구독이 영영
     * 걸리지 않아 이 시험이 확인하려는 것 자체가 성립하지 않는다.
     */
    @Autowired
    private WsSubscriptionStarter subscriptionStarter;

    private WebSocketSession session;

    @BeforeEach
    void 구독을_건다() {
        subscriptionStarter.subscribeUntilConnected();
    }

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

    @Test
    @DisplayName("해제를 발행하면 사유를 보낸 뒤 연결을 끊는다")
    void 해제하면_끊는다() throws Exception {
        session = 세션();
        registry.register(WsTarget.DEVICE, 기기, session);

        publisher.sendAndClose(WsTarget.DEVICE, 기기, 해제_프레임());

        // 순서가 중요하다. 그냥 닫으면 Agent 는 이유를 모른 채 재접속을 반복한다.
        InOrder 순서 = inOrder(session);
        순서.verify(session, timeout(5_000)).sendMessage(any(TextMessage.class));

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        순서.verify(session, timeout(5_000)).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(AgentProtocol.CLOSE_CODE_PROTOCOL_ERROR);
        assertThat(captor.getValue().getReason()).isEqualTo(AgentProtocol.ERROR_DEVICE_REVOKED);

        // 보관소에서도 빠져야 한다. 닫힘 처리가 비동기라 그 사이 다음 프레임이 죽은 소켓으로 나간다.
        assertThat(registry.holds(WsTarget.DEVICE, 기기)).isFalse();
    }

    @Test
    @DisplayName("보통 프레임은 보내기만 하고 연결을 끊지 않는다")
    void 보통_프레임은_끊지_않는다() throws Exception {
        session = 세션();
        registry.register(WsTarget.DEVICE, 기기, session);

        publisher.send(WsTarget.DEVICE, 기기, 작업_프레임(UUID.randomUUID()));

        verify(session, timeout(5_000)).sendMessage(any(TextMessage.class));
        verify(session, never()).close(any(CloseStatus.class));
        assertThat(registry.holds(WsTarget.DEVICE, 기기)).isTrue();
    }

    private static Map<String, Object> 해제_프레임() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "PROTOCOL_ERROR");
        frame.put("code", AgentProtocol.ERROR_DEVICE_REVOKED);
        return frame;
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
