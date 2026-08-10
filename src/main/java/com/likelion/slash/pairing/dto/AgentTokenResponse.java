package com.likelion.slash.pairing.dto;

import java.time.OffsetDateTime;

/**
 * 기기 Token 발급 응답. 페어링 완료와 세션 재발급이 같은 형식을 쓴다.
 * (메시지 스펙 §8.1 2~3단계)
 *
 * <p><b>Token 원문은 여기서만 나간다.</b> 서버는 해시만 보관하므로 다시 알려줄 수 없다.
 * Agent 가 잃어버리면 재발급(서명 재증명)을 받아야 한다.
 *
 * @param expiresIn 유효 기간(초). 만료 전에 재발급받도록 Agent 가 참고한다
 * @param wsUrl     접속할 Agent WSS 주소. 환경마다 다르므로 서버가 알려준다.
 *                  재발급 응답에는 넣지 않는다 (이미 접속해 있는 상태다)
 */
public record AgentTokenResponse(
        String deviceToken,
        long expiresIn,
        OffsetDateTime issuedAt,
        String wsUrl) {

    public static AgentTokenResponse issued(String deviceToken, long expiresIn, OffsetDateTime issuedAt, String wsUrl) {
        return new AgentTokenResponse(deviceToken, expiresIn, issuedAt, wsUrl);
    }

    public static AgentTokenResponse refreshed(String deviceToken, long expiresIn, OffsetDateTime issuedAt) {
        return new AgentTokenResponse(deviceToken, expiresIn, issuedAt, null);
    }
}
