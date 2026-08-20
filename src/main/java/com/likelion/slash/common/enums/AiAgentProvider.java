package com.likelion.slash.common.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code AI_AGENT_USAGE} 가 사용량을 물어볼 대상 도구.
 *
 * <p><b>PC 실행기의 {@code usage_adapters.py} 가 원본이다.</b> 그쪽 {@code COLLECTORS} 의 열쇠와
 * 정확히 같아야 하며, 다른 값을 보내면 실행기가 {@code INVALID_PARAMETERS} 로 거부한다.
 * 도구가 늘어나면 두 곳을 함께 고쳐야 한다.
 *
 * <p>서버가 미리 거르는 이유는 <b>PC 까지 갔다 오지 않기 위해서다.</b> 실행기가 판정하게 두면
 * PC 가 꺼져 있을 때 값이 잘못된 요청도 일단 접수되어 기다리다가, 켜진 뒤에야 실패한다.
 *
 * <p>사람이 쓰는 말("클로드")을 이 값으로 옮기는 것은 NLU 의 몫이다. 여기서는 <b>글자 모양만</b>
 * 관대하게 받는다 — 사전이 두 곳으로 갈리면 한쪽만 고쳤을 때 조용히 어긋난다.
 */
public enum AiAgentProvider {

    /** Claude Code CLI 의 로컬 세션 로그 */
    CLAUDE_CODE,

    /** Codex CLI 의 로컬 세션 로그 */
    CODEX;

    /**
     * 들어온 값을 이 목록의 값으로 옮긴다.
     *
     * <p>대소문자와 이음표({@code claude-code})만 맞춰 준다. {@link Locale#ROOT} 를 쓰는 이유는
     * 서버 기본 로케일을 따르면 터키어에서 {@code i} 가 {@code İ} 로 올라가 어긋나기 때문이다.
     *
     * @return 목록에 없는 값이면 비어 있다
     */
    public static Optional<AiAgentProvider> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(provider -> provider.name().equals(normalized))
                .findFirst();
    }
}
