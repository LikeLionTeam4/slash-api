package com.likelion.slash.nlu;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.nlu.dto.NluSummaryResponse;

/**
 * CPU 추출 요약의 결말.
 *
 * <p>예외로 던지지 않고 값으로 돌려주는 이유는 날씨와 같다 — <b>요약 실패는 정상적인 결말
 * 중 하나</b>다. 사용자에게는 "요약하지 못했다" 가 보여야 하고 Task 는 그 이유로 마감돼야
 * 한다. 예외로 만들면 호출부에서 다시 풀어내야 한다.
 */
public sealed interface SummaryOutcome {

    record Success(NluSummaryResponse response) implements SummaryOutcome {
    }

    /**
     * @param errorCode 사용자에게 보일 우리 쪽 코드
     * @param message   사용자에게 보일 말. NLU 의 문구를 그대로 노출하지 않는다
     */
    record Failure(ErrorCode errorCode, String message) implements SummaryOutcome {
    }
}
