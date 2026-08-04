package com.likelion.slash.common.enums;

/**
 * 작업 실행 위치. 개발문서 3.3.2
 *
 * <p>실행 위치는 Tool 정책에 따라 Spring 이 결정한다.
 * AI 모델이 권한이나 실행 위치를 결정하지 않는다. (문서 IN-05, 0.7)
 */
public enum ExecutionTarget {

    /** Spring 이 즉시 외부 API 를 호출 */
    CLOUD_SYNC,

    /** SQS 를 통해 GPU Worker 에 전달 */
    AI_WORKER,

    /** 선택한 PC 의 Agent 에 전달 */
    LOCAL_AGENT
}
