package com.likelion.slash.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.likelion.slash.llm.dto.LlmReadinessResponse;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 요약 모델 준비 상태를 들고 있는 방식 확인. (P0 보완)
 *
 * <p>여기서 지키려는 것은 <b>"모른다" 와 "준비되지 않았다" 를 섞지 않는 것</b>이다.
 * 모른다는 이유로 막으면 멀쩡한 모델을 두고 요약이 거부된다.
 */
@ExtendWith(MockitoExtension.class)
class LlmReadinessTest {

    @Mock
    private LlmClient llmClient;

    @Test
    @DisplayName("아직 묻지 못했으면 막지 않는다")
    void 모르면_통과시킨다() {
        LlmReadiness readiness = new LlmReadiness(llmClient);

        // 기동 직후다. 한 번도 확인하지 못했다고 해서 요약을 거부할 이유는 없다.
        assertThat(readiness.canAccept()).isTrue();
        assertThat(readiness.reason()).isEmpty();
    }

    @Test
    @DisplayName("준비됐다고 답하면 받는다")
    void 준비되면_받는다() {
        준비상태가(new LlmReadinessResponse("ready", "gemma3:4b", null));
        LlmReadiness readiness = new LlmReadiness(llmClient);

        readiness.check();

        assertThat(readiness.canAccept()).isTrue();
        assertThat(readiness.reason()).isEmpty();
    }

    @Test
    @DisplayName("받을 수 없다고 답하면 막고 이유를 남긴다")
    void 준비되지_않으면_막는다() {
        준비상태가(new LlmReadinessResponse("not_ready", "gemma3:4b", "OLLAMA_UNAVAILABLE"));
        LlmReadiness readiness = new LlmReadiness(llmClient);

        readiness.check();

        assertThat(readiness.canAccept()).isFalse();
        assertThat(readiness.reason()).contains("OLLAMA_UNAVAILABLE");
    }

    @Test
    @DisplayName("묻지 못한 회차는 마지막으로 알던 값을 그대로 둔다")
    void 닿지_못하면_이전_값을_지킨다() {
        LlmReadiness readiness = new LlmReadiness(llmClient);

        given(llmClient.ready())
                .willReturn(Optional.of(new LlmReadinessResponse("not_ready", "gemma3:4b", "MODEL_NOT_FOUND")))
                .willReturn(Optional.empty());

        readiness.check();
        assertThat(readiness.canAccept()).isFalse();

        // 이번에는 slash-llm 에 닿지 못했다. 그것이 "준비됐다" 는 뜻은 아니다.
        readiness.check();
        assertThat(readiness.canAccept()).isFalse();
        assertThat(readiness.reason()).contains("MODEL_NOT_FOUND");
    }

    @Test
    @DisplayName("다시 준비되면 풀린다")
    void 회복되면_다시_받는다() {
        LlmReadiness readiness = new LlmReadiness(llmClient);

        given(llmClient.ready())
                .willReturn(Optional.of(new LlmReadinessResponse("not_ready", "gemma3:4b", "OLLAMA_UNAVAILABLE")))
                .willReturn(Optional.of(new LlmReadinessResponse("ready", "gemma3:4b", null)));

        readiness.check();
        assertThat(readiness.canAccept()).isFalse();

        readiness.check();
        assertThat(readiness.canAccept()).isTrue();
        assertThat(readiness.reason()).isEmpty();
    }

    private void 준비상태가(LlmReadinessResponse 응답) {
        given(llmClient.ready()).willReturn(Optional.of(응답));
    }
}
