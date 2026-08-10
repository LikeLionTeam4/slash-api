package com.likelion.slash.nlu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /internal/v1/nlu/analyze} 응답. (slash-nlu {@code models.py} 의 {@code AnalyzeResponse})
 *
 * <p>평탄 JSON 이다. 공개 응답 봉투와 Task 이벤트로 바꾸는 것은 slash-api 몫이다.
 *
 * <p>NLU 가 필드를 먼저 늘려도 이쪽이 통째로 실패하지 않도록 모르는 필드는 무시한다.
 *
 * @param taskType                  {@code decision} 이 {@code TASK} 일 때만 채워진다.
 *                                  <b>NLU 의 목록은 P0 네 가지뿐이다</b> — {@code CODE_ANALYSIS}
 *                                  같은 P1 은 돌아오지 않는다.
 * @param parameters                의미 파라미터. {@code searchFolderId} 는 여기 없다 —
 *                                  기기의 {@code READY.searchFolders} 를 보고 slash-api 가 채운다.
 * @param missingRequiredParameters NLU 기준 누락값. 마찬가지로 {@code searchFolderId} 는 빠져 있다.
 * @param question                  {@code CLARIFY} 일 때 사용자에게 보여줄 되묻는 말
 * @param analyzer                  {@code SLASH} 또는 {@code RULE_KIWI}. 어느 길로 분석됐는지 남긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NluAnalyzeResponse(
        String requestId,
        NluDecision decision,
        String taskType,
        Map<String, Object> parameters,
        List<String> missingRequiredParameters,
        String question,
        double confidence,
        String analyzer) {

    public Map<String, Object> parametersOrEmpty() {
        return parameters == null ? Map.of() : parameters;
    }

    public List<String> missingOrEmpty() {
        return missingRequiredParameters == null ? List.of() : missingRequiredParameters;
    }
}
