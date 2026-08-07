package com.likelion.slash.pairing;

import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.pairing.dto.PairingCodeResponse;
import com.likelion.slash.pairing.dto.PairingStatusResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 화면의 PC 등록. (WBS W1-02 · 메시지 스펙 §4.1)
 *
 * <p>사용자가 "PC 등록" 을 누르면 6자리 코드를 받고, 그 코드를 자기 PC 의 Agent 에 입력한다.
 * 화면은 등록이 끝났는지 상태를 조회해 확인한다.
 *
 * <p>Agent 가 호출하는 경로는 {@link AgentPairingController} 다. 사용자 인증이 없어
 * 보안 성격이 달라서 컨트롤러를 나눈다.
 */
@RestController
@RequestMapping("/api/v1/pairing-requests")
public class PairingController {

    private final PairingService pairingService;
    private final AuthenticatedUserService authenticatedUserService;

    public PairingController(PairingService pairingService,
                             AuthenticatedUserService authenticatedUserService) {
        this.pairingService = pairingService;
        this.authenticatedUserService = authenticatedUserService;
    }

    /** 등록 코드 발급. 코드 원문은 이 응답에서만 볼 수 있다. */
    @PostMapping
    public ResponseEntity<ApiResponse<PairingCodeResponse>> issue() {
        long userId = authenticatedUserService.current().id();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(pairingService.issueCode(userId)));
    }

    /** 등록 진행 상태. 화면이 주기적으로 조회한다. */
    @GetMapping("/{pairingRequestId}")
    public ApiResponse<PairingStatusResponse> status(@PathVariable UUID pairingRequestId) {
        long userId = authenticatedUserService.current().id();

        return ApiResponse.of(pairingService.findStatus(pairingRequestId, userId));
    }
}
