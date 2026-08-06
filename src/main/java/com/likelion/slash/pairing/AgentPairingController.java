package com.likelion.slash.pairing;

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

    public AgentPairingController(PairingService pairingService) {
        this.pairingService = pairingService;
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
     * <p>Ingress 뒤에 있으면 {@code getRemoteAddr()} 가 전부 같은 주소로 보인다.
     * 그 경우 한 사람의 실패가 모두를 막으므로 {@code X-Forwarded-For} 의 첫 주소를 쓴다.
     * 이 헤더는 클라이언트가 위조할 수 있지만, 위조하면 자기 한도를 늘릴 뿐 남을 막지는 못한다.
     * 정확한 차단이 아니라 무차별 대입 속도를 늦추는 것이 목적이라 이 정도로 충분하다.
     */
    private static String clientOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
