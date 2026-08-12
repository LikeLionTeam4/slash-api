package com.likelion.slash.ws;

import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.ws.dto.WsTicketResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 WSS 접속표 발급. (계약 §7 · WBS W1-06)
 *
 * <p>브라우저는 이 표를 받아 {@code /ws/user} 에 접속한다. 표가 따로 필요한 이유는
 * {@link UserWsTicketStore} 에 적어 두었다 — 요약하면 <b>WSS 자격이 URL 에 실려 접근 로그에
 * 남기 때문</b>이다.
 *
 * <p><b>매번 새로 발급받는다.</b> 1회용이라 재사용할 수 없고, 연결이 끊길 때마다 다시 받는 것이
 * 정상적인 흐름이다. 발급 자체는 Valkey 쓰기 한 번이라 비싸지 않다.
 */
@RestController
@RequestMapping("/api/v1/ws")
public class WsTicketController {

    private final AuthenticatedUserService authenticatedUserService;
    private final UserWsTicketStore ticketStore;
    private final String wsUrl;

    public WsTicketController(AuthenticatedUserService authenticatedUserService,
                              UserWsTicketStore ticketStore,
                              @Value("${slash.websocket.ws-url}") String wsUrl) {
        this.authenticatedUserService = authenticatedUserService;
        this.ticketStore = ticketStore;
        this.wsUrl = wsUrl;
    }

    @PostMapping("/ticket")
    public ResponseEntity<ApiResponse<WsTicketResponse>> issue() {
        long userId = authenticatedUserService.current().id();

        WsTicketResponse response = new WsTicketResponse(
                ticketStore.issue(userId), ticketStore.ttl().toSeconds(), wsUrl);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
