package com.likelion.slash.task.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/tasks/text-summary/browser-result} 요청 본문. (slash-docs#3 권장 순서 3번)
 *
 * <p>브라우저가 WebLLM으로 이미 끝낸 요약의 결과만 싣는다. <b>원문은 여기에 없다</b> —
 * slash-docs#3 처리 원칙 2번("명시적인 브라우저 요약은 원문을 브라우저에 유지한다")에 따라
 * {@code inputLength}만 보내고, 실제 문자열은 이 요청 밖으로 나가지 않는다.
 *
 * @param inputLength  원문 길이(문자 수). 원문 자체는 보내지 않는다
 * @param modelId      사용한 WebLLM 모델 ID(예: {@code Qwen2.5-1.5B-Instruct-q4f16_1-MLC})
 * @param promptVersion 고정 시스템 프롬프트의 버전 표식
 * @param status       {@code SUCCEEDED} 또는 {@code FAILED}
 * @param summary      {@code SUCCEEDED}일 때만 있는 최종 요약 결과
 * @param durationMs   추론에 걸린 시간(밀리초). 없으면 비운다
 * @param errorMessage {@code FAILED}일 때 사용자에게 보여줄 설명. 민감정보를 넣지 않는다
 */
public record BrowserSummaryResultRequest(

        @NotNull(message = "원문 길이를 알려주세요.")
        @Min(value = 1, message = "원문 길이는 1자 이상이어야 합니다.")
        Integer inputLength,

        @NotBlank(message = "모델 ID를 알려주세요.")
        @Size(max = 200, message = "모델 ID는 200자를 넘을 수 없습니다.")
        String modelId,

        @NotBlank(message = "프롬프트 버전을 알려주세요.")
        @Size(max = 50, message = "프롬프트 버전은 50자를 넘을 수 없습니다.")
        String promptVersion,

        @NotNull(message = "결과 상태를 알려주세요.")
        Status status,

        @Size(max = 8000, message = "요약 결과는 8000자를 넘을 수 없습니다.")
        String summary,

        @Min(value = 0, message = "처리 시간은 0 이상이어야 합니다.")
        Integer durationMs,

        @Size(max = 500, message = "오류 설명은 500자를 넘을 수 없습니다.")
        String errorMessage) {

    public enum Status {
        SUCCEEDED, FAILED
    }
}
