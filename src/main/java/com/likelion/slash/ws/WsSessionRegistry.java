package com.likelion.slash.ws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 이 Pod 이 들고 있는 WSS 연결 보관소.
 *
 * <p>브로드캐스트 라우팅에서 "이 대상이 내 것인가"를 판정하는 유일한 근거다.
 * Valkey 에 연결 소유 Pod 를 따로 적어 두지 않는 이유가 여기에 있다.
 * 브로드캐스트는 각 Pod 이 자기 보관소만 보면 되므로 조회할 외부 상태가 없다.
 * (지목 전송으로 승급할 때 레지스트리가 필요해진다 — docs/w1-06-wss-routing.md 5.2)
 *
 * <p><b>동시 전송</b> — 하나의 소켓에 Pub/Sub 수신 스레드와 요청 처리 스레드가 동시에 쓰면
 * 프레임이 섞여 깨진다. WebSocket 은 부분 메시지 전송 중 다른 메시지가 끼어드는 것을 허용하지 않는다.
 * 등록 시점에 {@link ConcurrentWebSocketSessionDecorator} 로 감싸 전송을 직렬화한다.
 *
 * <p>관련 문서: 3.4.2 · 3.6 · WBS W1-06
 */
@Component
public class WsSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WsSessionRegistry.class);

    /** 전송이 이 시간을 넘기면 소켓을 끊는다. 느린 클라이언트가 Pod 의 버퍼를 붙잡는 것을 막는다. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 보내지 못하고 쌓인 양이 이 값을 넘으면 끊는다. */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    /**
     * 대상 → (세션 ID → 세션).
     *
     * <p>사용자는 탭마다 연결이 생기므로 값이 여러 개다. 기기는 한 개지만 재연결 직후
     * 옛 연결이 아직 닫히지 않은 구간이 있어 자료구조는 같게 둔다.
     */
    private final Map<Key, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();

    private record Key(WsTarget target, long targetId) {
    }

    /**
     * 연결을 등록한다.
     *
     * @return 같은 대상으로 이미 등록돼 있던 연결들. 기기처럼 연결이 하나여야 하는 대상은
     *         호출부가 이 목록을 닫아 준다. 여기서 닫지 않는 이유는 닫는 사유(중복 접속)를
     *         호출부만 알기 때문이다.
     */
    public List<WebSocketSession> register(WsTarget target, long targetId, WebSocketSession session) {
        WebSocketSession guarded =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);

        Map<String, WebSocketSession> held =
                sessions.computeIfAbsent(new Key(target, targetId), key -> new ConcurrentHashMap<>());

        // 자기 자신은 제외하고 넘긴다. 같은 세션 ID 로 다시 등록하는 경우는 교체로 본다.
        List<WebSocketSession> previous = held.values().stream()
                .filter(existing -> !existing.getId().equals(guarded.getId()))
                .toList();

        held.put(guarded.getId(), guarded);
        return previous;
    }

    /**
     * 연결을 해제한다. 대상에 남은 연결이 없으면 키까지 지운다.
     *
     * <p>키를 남겨 두면 연결이 붙었다 끊긴 기기 수만큼 Map 이 계속 커진다.
     */
    public void unregister(WsTarget target, long targetId, String sessionId) {
        sessions.computeIfPresent(new Key(target, targetId), (key, held) -> {
            held.remove(sessionId);
            return held.isEmpty() ? null : held;
        });
    }

    /** 이 Pod 이 해당 대상의 연결을 들고 있는지. */
    public boolean holds(WsTarget target, long targetId) {
        Map<String, WebSocketSession> held = sessions.get(new Key(target, targetId));
        return held != null && !held.isEmpty();
    }

    /**
     * 대상의 모든 연결로 보낸다. 이 Pod 에 연결이 없으면 아무 일도 하지 않는다.
     *
     * <p>브로드캐스트라 대부분의 Pod 에서 0 이 나오는 것이 정상이다.
     *
     * @return 실제로 보낸 연결 수
     */
    public int deliver(WsTarget target, long targetId, String payload) {
        Map<String, WebSocketSession> held = sessions.get(new Key(target, targetId));
        if (held == null || held.isEmpty()) {
            return 0;
        }

        TextMessage message = new TextMessage(payload);
        int sent = 0;

        for (WebSocketSession session : held.values()) {
            if (!session.isOpen()) {
                held.remove(session.getId());
                continue;
            }
            try {
                session.sendMessage(message);
                sent++;
            } catch (IOException | IllegalStateException e) {
                // 전송 실패는 사실상 끊긴 연결이다. 보관소에서 빼고 정리한다.
                // 여기서 예외를 올리면 같은 대상의 나머지 연결이 전송되지 못한다.
                log.warn("WSS 전송 실패 target={} targetId={} sessionId={}: {}",
                        target, targetId, session.getId(), e.getMessage());
                held.remove(session.getId());
                closeQuietly(session);
            }
        }

        return sent;
    }

    /** 이 Pod 이 들고 있는 연결 수. 운영 확인용. */
    public int connectionCount(WsTarget target) {
        return sessions.entrySet().stream()
                .filter(entry -> entry.getKey().target() == target)
                .mapToInt(entry -> entry.getValue().size())
                .sum();
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 이미 끊긴 연결을 닫는 중이라 더 할 수 있는 일이 없다.
        }
    }
}
