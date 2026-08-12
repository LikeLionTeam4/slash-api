package com.likelion.slash.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.ws.dto.ConnectedEvent;
import com.likelion.slash.ws.dto.PongEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 사용자 브라우저 WSS {@code /ws/user}. (계약 §7 · WBS W1-06)
 *
 * <p>작업 상태가 바뀌면 화면이 즉시 따라오게 하는 <b>알림 전용</b> 채널이다. Agent 채널과 달리
 * 여기로 들어오는 것은 {@code PING} 뿐이고, 클라이언트가 상태를 바꿀 수단은 없다.
 *
 * <pre>
 *   1. POST /api/v1/ws/ticket   (Bearer)  →  30초·1회용 접속표
 *   2. 브라우저  wss://.../ws/user?ticket=...   →
 *              ←  CONNECTED {connectionId, serverTime}
 *              ←  TASK_STATUS_CHANGED / TASK_RESULT_AVAILABLE
 *      브라우저  PING                            →
 *              ←  PONG
 * </pre>
 *
 * <p><b>접속표로 인증한다.</b> 브라우저 {@code WebSocket} API 는 {@code Authorization} 헤더를
 * 붙일 수 없어 자격이 URL 에 실린다. URL 은 접근 로그에 남으므로 몇 시간짜리 Access Token 을
 * 그대로 쓸 수 없다. 30초·1회용 표를 따로 발급하는 이유가 그것이다.
 * ({@link UserWsTicketStore})
 *
 * <p><b>표를 확인하기 전에는 보관소에 넣지 않는다.</b> 등록되지 않은 소켓으로는 어떤 이벤트도
 * 나가지 않으므로, 검증 실패한 연결이 남의 알림을 엿볼 수 없다. Agent 핸들러와 같은 원칙이다.
 *
 * <p><b>탭마다 연결이 생긴다.</b> 기기와 달리 옛 연결을 밀어내지 않는다. 사용자가 창을 두 개
 * 띄워 두면 둘 다 같은 알림을 받아야 한다. {@link WsSessionRegistry} 가 대상 하나에 여러 세션을
 * 담을 수 있게 돼 있는 이유다.
 *
 * <p><b>진실 소스는 REST 다.</b> 끊긴 동안의 이벤트는 그냥 사라진다. 다시 붙은 브라우저는
 * 재조회로 따라잡는다. 여기서 놓친 것을 되짚어 보내지 않는다.
 */
@Component
public class UserWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UserWebSocketHandler.class);

    private static final String ATTR_USER_ID = "slash.userId";

    private static final String TICKET_QUERY_PARAMETER = "ticket";

    /** 접속표가 없거나 만료·재사용됐을 때. 참조 구현과 같은 값이다. */
    private static final CloseStatus CLOSE_UNAUTHENTICATED = new CloseStatus(4401, "unauthenticated");

    private final ObjectMapper objectMapper;
    private final WsSessionRegistry registry;
    private final UserWsTicketStore ticketStore;

    public UserWebSocketHandler(ObjectMapper objectMapper,
                                WsSessionRegistry registry,
                                UserWsTicketStore ticketStore) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.ticketStore = ticketStore;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Optional<Long> userId = ticket(session).flatMap(ticketStore::consume);

        if (userId.isEmpty()) {
            // 만료된 표와 없는 표를 구분해 알리지 않는다. 값을 넣어 보며 유효한 표를 찾는 것을 막는다.
            close(session, CLOSE_UNAUTHENTICATED);
            return;
        }

        session.getAttributes().put(ATTR_USER_ID, userId.get());
        registry.register(WsTarget.USER, userId.get(), session);

        send(session, ConnectedEvent.create());
        log.debug("사용자 WSS 연결 userId={} sessionId={}", userId.get(), session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        registry.unregister(WsTarget.USER, userId, session.getId());
        log.debug("사용자 WSS 종료 userId={} status={}", userId, status);
    }

    /**
     * 들어오는 메시지는 {@code PING} 뿐이다.
     *
     * <p>모르는 메시지에 연결을 끊지 않는다. 이 채널은 알림 전용이라 클라이언트가 무엇을 보내든
     * 서버 상태가 바뀌지 않고, 끊으면 화면만 조용히 죽는다. 참조 구현도 조용히 무시한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (session.getAttributes().get(ATTR_USER_ID) == null) {
            return;
        }

        try {
            JsonNode frame = objectMapper.readTree(message.getPayload());
            if (UserProtocol.TYPE_PING.equals(frame.path("type").asText())) {
                send(session, PongEvent.create());
            }
        } catch (Exception e) {
            log.debug("사용자 WSS 메시지를 읽지 못했다 sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * 질의 문자열에서 접속표를 꺼낸다.
     *
     * <p><b>{@code getRawQuery} 를 쓴다.</b> {@code getQuery} 는 이미 디코딩된 값을 주므로 거기에
     * 다시 디코딩을 걸면 두 번 푸는 것이 된다. 지금 쓰는 표는 base64url({@code A-Za-z0-9_-})이라
     * {@code %}·{@code +} 가 없어 결과가 같지만, 표 형식이 바뀌는 날 조용히 틀린 값을 넘긴다.
     * 그때 나오는 증상은 "인증 실패"뿐이라 원인을 찾기 어렵다.
     */
    private Optional<String> ticket(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getRawQuery() == null) {
            return Optional.empty();
        }

        return Arrays.stream(uri.getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith(TICKET_QUERY_PARAMETER + "="))
                .map(parameter -> parameter.substring(TICKET_QUERY_PARAMETER.length() + 1))
                .map(UserWebSocketHandler::decode)
                .flatMap(Optional::stream)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    /**
     * 망가진 이스케이프({@code %zz})는 표가 아니다.
     *
     * <p>{@link URI} 가 만들어질 때 이스케이프는 이미 검증되므로 여기까지 오기 어렵다.
     * 그래도 한 겹 두는 것은, 예외가 나가면 <b>4401 이 아니라 서버 오류로 끝나기</b> 때문이다.
     * 프론트는 4401 을 보고 표를 새로 받도록 만들어져 있다.
     */
    private static Optional<String> decode(String value) {
        try {
            return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 프레임 하나를 이 연결에만 보낸다.
     *
     * <p>등록된 뒤에는 Pub/Sub 스레드도 같은 소켓에 쓰므로 보관소가 감싼 세션을 거친다.
     * ({@link WsSessionRegistry#guarded})
     */
    private void send(WebSocketSession session, Object frame) throws IOException {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        WebSocketSession target = userId == null ? session : registry.guarded(WsTarget.USER, userId, session);

        target.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("사용자 WSS 를 닫지 못했다 sessionId={}: {}", session.getId(), e.getMessage());
        }
    }
}
