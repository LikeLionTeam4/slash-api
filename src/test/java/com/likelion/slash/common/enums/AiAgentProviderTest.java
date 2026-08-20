package com.likelion.slash.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AiAgentProvider} 확인.
 *
 * <p>PC 실행기의 {@code COLLECTORS} 열쇠와 같아야 한다. 값이 어긋나면 실행기가
 * {@code INVALID_PARAMETERS} 로 거부하는데, 그 사실은 PC 가 켜진 뒤에야 드러난다.
 */
class AiAgentProviderTest {

    @Test
    @DisplayName("실행기가 아는 이름은 그대로 받는다")
    void 계약된_이름() {
        assertThat(AiAgentProvider.from("CLAUDE_CODE")).contains(AiAgentProvider.CLAUDE_CODE);
        assertThat(AiAgentProvider.from("CODEX")).contains(AiAgentProvider.CODEX);
    }

    @Test
    @DisplayName("대소문자와 이음표, 앞뒤 공백은 맞춰 준다")
    void 글자_모양은_관대하다() {
        assertThat(AiAgentProvider.from("claude_code")).contains(AiAgentProvider.CLAUDE_CODE);
        assertThat(AiAgentProvider.from("claude-code")).contains(AiAgentProvider.CLAUDE_CODE);
        assertThat(AiAgentProvider.from("  Codex  ")).contains(AiAgentProvider.CODEX);
    }

    @Test
    @DisplayName("사람이 쓰는 말은 받지 않는다 — 그것을 옮기는 일은 NLU 몫이다")
    void 낱말_별칭은_받지_않는다() {
        assertThat(AiAgentProvider.from("claude")).isEmpty();
        assertThat(AiAgentProvider.from("클로드")).isEmpty();
        assertThat(AiAgentProvider.from("gpt")).isEmpty();
    }

    @Test
    @DisplayName("비어 있거나 없는 값은 비어 있다")
    void 빈_값() {
        assertThat(AiAgentProvider.from(null)).isEmpty();
        assertThat(AiAgentProvider.from("")).isEmpty();
        assertThat(AiAgentProvider.from("   ")).isEmpty();
    }

    @Test
    @DisplayName("터키어 로케일에서도 소문자 이름이 어긋나지 않는다")
    void 로케일에_흔들리지_않는다() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            // 기본 로케일을 따랐다면 'i' 가 'İ' 로 올라가 valueOf 가 어긋난다.
            assertThat(AiAgentProvider.from("codex")).contains(AiAgentProvider.CODEX);
        } finally {
            Locale.setDefault(before);
        }
    }
}
