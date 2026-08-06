package com.likelion.slash.pairing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/v1/agent/pair} 요청. (메시지 스펙 §8.1 1단계)
 *
 * <p>사용자 인증이 없는 경로다. 사용자가 화면에서 받은 6자리 코드를 Agent 에 입력하는 것이
 * 유일한 연결 고리이며, 소유 증명은 다음 단계(서명)에서 한다.
 *
 * @param publicKey Ed25519 공개키(Base64). 개인키는 PC 밖으로 나오지 않는다. (문서 LA-01)
 */
public record AgentPairRequest(
        @NotBlank @Size(min = 6, max = 6) String pairingCode,
        @NotBlank String publicKey,
        @NotNull @Valid Device device,
        List<String> supportedTaskTypes) {

    public record Device(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String os,
            @NotBlank String architecture,
            @Size(max = 50) String osVersion,
            @Size(max = 50) String agentVersion) {
    }
}
