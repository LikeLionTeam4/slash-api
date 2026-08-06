package com.likelion.slash.config;

import com.likelion.slash.ws.AgentWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WSS 엔드포인트 등록. (WBS W1-06)
 *
 * <p>STOMP 를 쓰지 않는다. 프레임 형식이 메시지 프로토콜 정의로 이미 정해져 있어
 * 별도의 메시징 규약을 얹을 이유가 없다.
 *
 * <p>사용자 WSS {@code /ws/user} 는 아직 등록하지 않았다.
 * Ticket 발급(3.4.2)이 함께 있어야 의미가 있어 별도 단계로 둔다.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Agent 는 브라우저가 아니라 데스크톱 앱이라 Origin 헤더를 보내지 않는다.
        // 따라서 오리진 허용 목록을 두지 않는다. 접근 통제는 프로토콜 안의 서명 검증이 담당한다.
        registry.addHandler(agentWebSocketHandler, "/ws/agent");
    }
}
