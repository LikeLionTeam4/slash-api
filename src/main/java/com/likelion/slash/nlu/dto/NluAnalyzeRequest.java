package com.likelion.slash.nlu.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code POST /internal/v1/nlu/analyze} 요청 본문. (slash-nlu {@code models.py})
 *
 * <p><b>{@code text} 와 {@code command} 중 정확히 하나만 채운다.</b> 둘 다 있거나 둘 다
 * 없으면 NLU 가 400 으로 거부한다. ({@code main.py} 의 {@code has_text == has_command})
 * 그래서 {@link JsonInclude.Include#NON_NULL} 로 빈 쪽을 아예 내보내지 않는다.
 *
 * @param requestId 요청 추적 식별자. 빈 문자열이면 NLU 가 거부한다.
 * @param now       기준 시각. 상대 날짜("어제 받은 파일") 해석에 쓴다.
 *                  <b>오프셋이 있어야 한다</b> — NLU 가 {@code utcoffset()} 을 검사한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NluAnalyzeRequest(
        String requestId,
        String text,
        NluCommandInput command,
        OffsetDateTime now) {

    /** 자연어 입력. */
    public static NluAnalyzeRequest ofText(String requestId, String text, OffsetDateTime now) {
        return new NluAnalyzeRequest(requestId, text, null, now);
    }

    /** 슬래시 명령 입력. */
    public static NluAnalyzeRequest ofCommand(String requestId,
                                              List<String> path,
                                              List<String> operands,
                                              OffsetDateTime now) {
        return new NluAnalyzeRequest(requestId, null, new NluCommandInput(path, operands), now);
    }
}
