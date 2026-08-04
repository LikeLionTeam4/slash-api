package com.likelion.slash.common.enums;

/**
 * 작업 처리 위치. 메시지 프로토콜 정의 5.3
 *
 * <p>처리 경로는 slash-api 가 Tool 정책에 따라 결정한다.
 * NLU 나 LLM 이 처리 경로를 직접 결정하지 않는다.
 */
public enum ProcessingRoute {

    /** slash-api 가 외부 API 를 직접 호출한다. (예: 날씨) */
    BACKEND_SERVICE,

    /** 선택한 사용자 PC 의 Agent 에 WSS 로 전달한다. */
    LOCAL_AGENT,

    /** SQS 를 통해 slash-llm 에 비동기로 전달한다. */
    LLM_SERVICE
}
