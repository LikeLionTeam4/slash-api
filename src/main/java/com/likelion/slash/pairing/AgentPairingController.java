package com.likelion.slash.pairing;

import com.likelion.slash.common.ClientAddressResolver;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.pairing.dto.AgentPairRequest;
import com.likelion.slash.pairing.dto.AgentPairResponse;
import com.likelion.slash.pairing.dto.AgentPairVerifyRequest;
import com.likelion.slash.pairing.dto.AgentSessionRefreshRequest;
import com.likelion.slash.pairing.dto.AgentTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 가 호출하는 등록 경로. (WBS W1-02 · 메시지 스펙 §8.1)
 *
 * <p><b>사용자 인증이 없다.</b> Agent 는 아직 아무 자격도 갖고 있지 않은 상태에서 시작한다.
 * 사용자가 화면에서 받은 6자리 코드를 입력하는 것이 유일한 연결 고리이고,
 * 소유 증명은 Ed25519 서명으로 한다.
 *
 * <p>인증이 없는 만큼 시도 횟수 제한이 중요하다. {@link PairingAttemptLimiter} 참고.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentPairingController {

    private final PairingService pairingService;
    private final ClientAddressResolver clientAddressResolver;

    public AgentPairingController(PairingService pairingService,
                                  ClientAddressResolver clientAddressResolver) {
        this.pairingService = pairingService;
        this.clientAddressResolver = clientAddressResolver;
    }

    /** 등록 코드를 제출하고 도전값을 받는다. 아직 Token 은 나오지 않는다. */
    @PostMapping("/pair")
    public ResponseEntity<ApiResponse<AgentPairResponse>> pair(
            @Valid @RequestBody AgentPairRequest request, HttpServletRequest httpRequest) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(pairingService.pair(request, clientOf(httpRequest))));
    }

    /** 도전값 서명으로 소유를 증명하고 기기 Token 을 받는다. */
    @PostMapping("/pair/verify")
    public ApiResponse<AgentTokenResponse> verify(
            @Valid @RequestBody AgentPairVerifyRequest request, HttpServletRequest httpRequest) {

        return ApiResponse.of(pairingService.verify(request, clientOf(httpRequest)));
    }

    /** Token 만료 전에 서명으로 재증명하고 새 Token 을 받는다. */
    @PostMapping("/sessions/refresh")
    public ApiResponse<AgentTokenResponse> refresh(@Valid @RequestBody AgentSessionRefreshRequest request) {
        return ApiResponse.of(pairingService.refresh(request));
    }

    /**
     * 시도 횟수를 세는 기준.
     *
     * <p>{@code X-Forwarded-For} 를 어디까지 믿을지는 배포 구성에 달려 있어
     * {@link ClientAddressResolver} 가 정한다. 여기서 헤더를 직접 읽지 않는다 —
     * 첫 값을 그냥 믿으면 요청마다 다른 값을 넣어 시도 횟수 제한을 통째로 지울 수 있다.
     */
    private String clientOf(HttpServletRequest request) {
        return clientAddressResolver.resolve(request);
    }
}
