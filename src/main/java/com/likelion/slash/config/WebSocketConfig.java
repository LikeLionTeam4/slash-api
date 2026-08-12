package com.likelion.slash.config;

import com.likelion.slash.ws.AgentWebSocketHandler;
import com.likelion.slash.ws.UserWebSocketHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
 * <p><b>두 채널의 접근 통제가 다르다.</b> Agent 는 데스크톱 앱이라 {@code Origin} 을 보내지
 * 않으므로 프로토콜 안의 서명 검증에 맡기고, 브라우저는 {@code Origin} 을 보내므로 REST 와
 * 같은 오리진 목록으로 거른다.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final UserWebSocketHandler userWebSocketHandler;
    private final List<String> allowedOrigins;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler,
                           UserWebSocketHandler userWebSocketHandler,
                           @Value("${slash.cors.allowed-origins}") List<String> allowedOrigins) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.userWebSocketHandler = userWebSocketHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Agent 는 브라우저가 아니라 데스크톱 앱이라 Origin 헤더를 보내지 않는다.
        // 따라서 오리진 허용 목록을 두지 않는다. 접근 통제는 프로토콜 안의 서명 검증이 담당한다.
        registry.addHandler(agentWebSocketHandler, "/ws/agent");

        // 브라우저는 Origin 을 보낸다. 열어 두면 아무 사이트나 사용자의 알림 채널에 붙어 볼 수
        // 있으므로 REST 와 같은 목록으로 막는다. 접속표 검증은 그다음 단계다.
        registry.addHandler(userWebSocketHandler, "/ws/user")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
