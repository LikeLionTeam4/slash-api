package com.likelion.slash.llm;

import com.likelion.slash.llm.dto.LlmSummaryResponse;

/**
 * 요약 호출의 결말.
 *
 * <p>성공과 실패 모두 {@code async_jobs} 에 남겨야 하는 정상적인 결말이라 값으로 돌려준다.
 * 이유는 {@link LlmFailure} 에 적었다.
 */
public sealed interface LlmSummaryOutcome {

    /**
     * @param durationMilliseconds 호출에 걸린 시간. {@code async_jobs.duration_milliseconds} 에 남는다.
     *                             모델의 순수 추론 시간이 아니라 <b>우리가 잰 왕복 시간</b>이다.
     */
    record Success(LlmSummaryResponse response, int durationMilliseconds) implements LlmSummaryOutcome {
    }

    record Failure(LlmFailure failure) implements LlmSummaryOutcome {
    }
}
