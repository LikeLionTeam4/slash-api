package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.likelion.slash.common.SlashTime;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link UserWebSocketHandler} 확인. (WBS W1-06)
 *
 * <p>여기서 보는 것은 <b>표를 확인하기 전에는 아무것도 새지 않는가</b>다.
 * 검증 전에 보관소에 등록되면 그 소켓으로 남의 알림이 나갈 수 있다.
 */
class UserWebSocketHandlerTest {

    private static final long 사용자_PK = 77L;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultTimeZone(TimeZone.getTimeZone(SlashTime.ZONE))
            .build();

    private final WsSessionRegistry registry = new WsSessionRegistry();
    private final UserWsTicketStore ticketStore = mock(UserWsTicketStore.class);

    private final UserWebSocketHandler handler =
            new UserWebSocketHandler(objectMapper, registry, ticketStore);

    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        session = 세션("ticket=유효한표");
        when(ticketStore.consume("유효한표")).thenReturn(Optional.of(사용자_PK));
    }

    @Test
    @DisplayName("표가 유효하면 연결을 등록하고 CONNECTED 를 먼저 보낸다")
    void 유효한_표로_붙는다() throws Exception {
        handler.afterConnectionEstablished(session);

        JsonNode connected = 마지막_응답();
        assertThat(connected.path("type").asText()).isEqualTo("CONNECTED");
        assertThat(connected.path("connectionId").asText()).isNotBlank();
        assertThat(connected.path("serverTime").asText()).endsWith("+09:00");
        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isTrue();
    }

    @Test
    @DisplayName("표가 없으면 4401 로 끊고 보관소에 넣지 않는다")
    void 표_없이는_붙지_못한다() throws Exception {
        WebSocketSession 표없음 = 세션(null);

        handler.afterConnectionEstablished(표없음);

        verify(표없음).close(종료코드(4401));
        // 등록되지 않아야 어떤 알림도 이 소켓으로 나가지 않는다.
        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isFalse();
        verify(표없음, never()).sendMessage(any());
    }

    @Test
    @DisplayName("이미 쓴 표로는 붙지 못한다")
    void 쓴_표는_통하지_않는다() throws Exception {
        WebSocketSession 재사용 = 세션("ticket=쓴표");
        when(ticketStore.consume("쓴표")).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(재사용);

        verify(재사용).close(종료코드(4401));
        assertThat(registry.connectionCount(WsTarget.USER)).isZero();
    }

    @Test
    @DisplayName("표는 접속할 때 소비된다 — 같은 표로 두 번 붙을 수 없다")
    void 표를_소비한다() throws Exception {
        handler.afterConnectionEstablished(session);

        verify(ticketStore).consume("유효한표");
    }

    @Test
    @DisplayName("탭을 여러 개 열면 연결도 여러 개 유지된다")
    void 탭마다_연결을_유지한다() throws Exception {
        WebSocketSession 두번째_탭 = 세션("ticket=두번째표");
        when(ticketStore.consume("두번째표")).thenReturn(Optional.of(사용자_PK));

        handler.afterConnectionEstablished(session);
        handler.afterConnectionEstablished(두번째_탭);

        // 기기와 달리 옛 연결을 밀어내지 않는다. 창을 두 개 띄우면 둘 다 알림을 받아야 한다.
        assertThat(registry.connectionCount(WsTarget.USER)).isEqualTo(2);
    }

    @Test
    @DisplayName("PING 에 PONG 으로 답한다")
    void PING_에_답한다() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"type\":\"PING\"}"));

        assertThat(마지막_응답().path("type").asText()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("모르는 메시지에는 연결을 끊지 않는다 — 알림 전용 채널이다")
    void 모르는_메시지를_흘려보낸다() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("망가진 JSON"));
        handler.handleMessage(session, new TextMessage("{\"type\":\"무엇인가\"}"));

        verify(session, never()).close(any());
        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isTrue();
    }

    @Test
    @DisplayName("연결이 끊기면 보관소에서 뺀다")
    void 끊기면_정리한다() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isFalse();
    }

    @Test
    @DisplayName("붙지 못한 연결이 끊겨도 남의 연결을 건드리지 않는다")
    void 미인증_종료는_영향이_없다() throws Exception {
        handler.afterConnectionEstablished(session);
        WebSocketSession 표없음 = 세션(null);
        handler.afterConnectionEstablished(표없음);

        handler.afterConnectionClosed(표없음, CloseStatus.NORMAL);

        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isTrue();
    }

    @Test
    @DisplayName("퍼센트 인코딩된 표를 한 번만 푼다")
    void 표를_두_번_디코딩하지_않는다() throws Exception {
        // %2B 는 '+' 다. 이미 디코딩된 질의 문자열을 다시 풀면 '+' 가 공백이 돼 표가 어긋난다.
        // 증상은 "인증 실패" 하나뿐이라 원인을 찾기 어렵다.
        WebSocketSession 인코딩된_표 = 세션("ticket=a%2Bb");
        when(ticketStore.consume("a+b")).thenReturn(Optional.of(사용자_PK));

        handler.afterConnectionEstablished(인코딩된_표);

        verify(ticketStore).consume("a+b");
        assertThat(registry.holds(WsTarget.USER, 사용자_PK)).isTrue();
    }

    @Test
    @DisplayName("표 말고 다른 질의 값이 붙어 있어도 표를 찾는다")
    void 다른_질의값이_섞여도_찾는다() throws Exception {
        WebSocketSession 세션 = 세션("v=2&ticket=유효한표&debug=1");

        handler.afterConnectionEstablished(세션);

        verify(ticketStore).consume("유효한표");
    }

    // ------------------------------------------------------------------

    private WebSocketSession 세션(String query) {
        WebSocketSession mock = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(mock.getAttributes()).thenReturn(attributes);
        when(mock.getId()).thenReturn("session-" + System.nanoTime());
        when(mock.isOpen()).thenReturn(true);
        when(mock.getUri()).thenReturn(
                URI.create("ws://localhost:8080/ws/user" + (query == null ? "" : "?" + query)));
        return mock;
    }

    private JsonNode 마지막_응답() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        List<TextMessage> sent = captor.getAllValues();
        return objectMapper.readTree(sent.get(sent.size() - 1).getPayload());
    }

    /**
     * 종료 코드만 확인하는 조건.
     *
     * <p>{@link CloseStatus} 는 사유 문자열까지 같아야 동등하다. 사유는 사람이 읽는 값이라
     * 문구가 바뀌었다고 시험이 깨지면 안 되므로 코드만 본다.
     */
    private static CloseStatus 종료코드(int code) {
        return org.mockito.ArgumentMatchers.argThat(
                status -> status != null && status.getCode() == code);
    }
}
