package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * {@link WsSessionRegistry} 확인. (WBS W1-06)
 *
 * <p>브로드캐스트 라우팅에서 "내 것인가"를 판정하는 유일한 근거이므로,
 * 남의 대상에게 새지 않는 것과 끊긴 연결이 남지 않는 것을 본다.
 */
class WsSessionRegistryTest {

    private static final long 기기 = 42L;
    private static final long 다른_기기 = 43L;

    private final WsSessionRegistry registry = new WsSessionRegistry();

    @Test
    @DisplayName("등록한 대상에게만 전달한다")
    void 대상이_아니면_전달하지_않는다() {
        registry.register(WsTarget.DEVICE, 기기, 세션("s1"));

        assertThat(registry.holds(WsTarget.DEVICE, 기기)).isTrue();
        assertThat(registry.holds(WsTarget.DEVICE, 다른_기기)).isFalse();
        assertThat(registry.deliver(WsTarget.DEVICE, 다른_기기, "{}")).isZero();
    }

    @Test
    @DisplayName("같은 식별자라도 대상 종류가 다르면 남남이다")
    void 대상_종류가_다르면_구분한다() {
        registry.register(WsTarget.DEVICE, 기기, 세션("s1"));

        assertThat(registry.holds(WsTarget.USER, 기기)).isFalse();
    }

    @Test
    @DisplayName("등록한 연결로 프레임을 보낸다")
    void 전달한다() throws Exception {
        WebSocketSession session = 세션("s1");
        registry.register(WsTarget.DEVICE, 기기, session);

        assertThat(registry.deliver(WsTarget.DEVICE, 기기, "{\"type\":\"TASK\"}")).isEqualTo(1);
        verify(session).sendMessage(new TextMessage("{\"type\":\"TASK\"}"));
    }

    @Test
    @DisplayName("사용자는 탭마다 연결이 생기므로 모두에게 보낸다")
    void 한_대상에_연결이_여럿일_수_있다() {
        registry.register(WsTarget.USER, 기기, 세션("탭1"));
        registry.register(WsTarget.USER, 기기, 세션("탭2"));

        assertThat(registry.deliver(WsTarget.USER, 기기, "{}")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 기기가 다시 접속하면 이전 연결을 돌려준다 — 호출부가 끊는다")
    void 재접속하면_이전_연결을_알려준다() {
        WebSocketSession 옛_연결 = 세션("s1");
        registry.register(WsTarget.DEVICE, 기기, 옛_연결);

        assertThat(registry.register(WsTarget.DEVICE, 기기, 세션("s2")))
                .hasSize(1)
                .allSatisfy(previous -> assertThat(previous.getId()).isEqualTo("s1"));
    }

    @Test
    @DisplayName("닫힌 연결에는 보내지 않고 보관소에서 뺀다")
    void 닫힌_연결을_정리한다() {
        WebSocketSession session = 세션("s1");
        when(session.isOpen()).thenReturn(false);
        registry.register(WsTarget.DEVICE, 기기, session);

        assertThat(registry.deliver(WsTarget.DEVICE, 기기, "{}")).isZero();
        assertThat(registry.connectionCount(WsTarget.DEVICE)).isZero();
    }

    @Test
    @DisplayName("한 연결의 전송이 실패해도 나머지 연결은 받는다")
    void 전송_실패가_다른_연결을_막지_않는다() throws Exception {
        WebSocketSession 끊긴_탭 = 세션("탭1");
        WebSocketSession 살아있는_탭 = 세션("탭2");
        doThrow(new IOException("broken pipe")).when(끊긴_탭).sendMessage(any());

        registry.register(WsTarget.USER, 기기, 끊긴_탭);
        registry.register(WsTarget.USER, 기기, 살아있는_탭);

        assertThat(registry.deliver(WsTarget.USER, 기기, "{}")).isEqualTo(1);
        verify(살아있는_탭).sendMessage(any());
        // 실패한 연결은 다음 전달 대상에서 빠진다.
        assertThat(registry.connectionCount(WsTarget.USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결이 모두 빠지면 대상 자체를 지운다")
    void 해제하면_남지_않는다() {
        registry.register(WsTarget.DEVICE, 기기, 세션("s1"));
        registry.unregister(WsTarget.DEVICE, 기기, "s1");

        assertThat(registry.holds(WsTarget.DEVICE, 기기)).isFalse();
        assertThat(registry.connectionCount(WsTarget.DEVICE)).isZero();
    }

    @Test
    @DisplayName("전달 대상이 없어도 조용히 넘어간다 — 브로드캐스트에서는 이것이 정상이다")
    void 대상이_없어도_예외가_아니다() {
        assertThat(registry.deliver(WsTarget.DEVICE, 기기, "{}")).isZero();
    }

    @Test
    @DisplayName("등록된 소켓에 직접 쓸 때도 보관소가 감싼 세션을 준다 — 직렬화를 우회하지 않는다")
    void 등록된_소켓은_감싼_세션으로_쓴다() {
        WebSocketSession 원본 = 세션("s1");
        registry.register(WsTarget.DEVICE, 기기, 원본);

        WebSocketSession 쓸_세션 = registry.guarded(WsTarget.DEVICE, 기기, 원본);

        // 원본 그대로 쓰면 deliver 의 잠금을 우회해 Pub/Sub 스레드와 같은 소켓에 동시에 쓰게 된다.
        assertThat(쓸_세션)
                .isNotSameAs(원본)
                .isInstanceOf(ConcurrentWebSocketSessionDecorator.class);

        // deliver 가 쓰는 것과 같은 인스턴스여야 잠금이 공유된다.
        assertThat(registry.guarded(WsTarget.DEVICE, 기기, 원본)).isSameAs(쓸_세션);
    }

    @Test
    @DisplayName("등록 전에는 준 세션을 그대로 돌려준다 — 아직 아무도 이 소켓을 모른다")
    void 등록_전에는_원본을_쓴다() {
        WebSocketSession 인증_전_세션 = 세션("s1");

        assertThat(registry.guarded(WsTarget.DEVICE, 기기, 인증_전_세션)).isSameAs(인증_전_세션);

        // 같은 대상에 다른 연결이 등록돼 있어도, 내 소켓이 등록 전이면 마찬가지다.
        registry.register(WsTarget.DEVICE, 기기, 세션("다른_연결"));
        assertThat(registry.guarded(WsTarget.DEVICE, 기기, 인증_전_세션)).isSameAs(인증_전_세션);
    }

    private static WebSocketSession 세션(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    /** 등록하지 않은 연결로는 전달되지 않는다는 것을 다른 각도에서 한 번 더 본다. */
    @Test
    @DisplayName("인증 전 연결처럼 등록되지 않은 소켓에는 아무것도 나가지 않는다")
    void 등록되지_않은_소켓에는_나가지_않는다() throws Exception {
        WebSocketSession 등록하지_않은_세션 = 세션("s9");

        registry.deliver(WsTarget.DEVICE, 기기, "{}");

        verify(등록하지_않은_세션, never()).sendMessage(any());
    }
}
