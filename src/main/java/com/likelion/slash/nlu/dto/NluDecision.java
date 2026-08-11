package com.likelion.slash.nlu.dto;

/**
 * NLU 가 내린 판정. (slash-nlu {@code models.py} 의 {@code Decision})
 *
 * <p>처리 방침은 {@code slash-nlu/docs/BACKEND_CONTRACT.md} 의 결과 매핑 표를 따른다.
 */
public enum NluDecision {

    /** 실행할 작업을 알아냈다. {@code taskType} 을 검증한 뒤 처리 경로는 slash-api 가 정한다. */
    TASK,

    /** 되물어야 한다. Task 를 {@code NEEDS_CLARIFICATION} 으로 두고 {@code question} 을 보여준다. */
    CLARIFY,

    /** 지원하지 않는 요청이다. 아무것도 실행하지 않는다. */
    UNSUPPORTED
}
