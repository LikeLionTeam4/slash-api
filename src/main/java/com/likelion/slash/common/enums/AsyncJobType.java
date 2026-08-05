package com.likelion.slash.common.enums;

/**
 * SQS 로 전달하는 비동기 LLM 작업의 종류. V006 의 {@code ck_async_jobs_job_type} 과 같은 목록이다.
 *
 * <p>{@link TaskType} 중 처리 경로가 {@link ProcessingRoute#LLM_SERVICE} 인 것만 여기에 대응한다.
 */
public enum AsyncJobType {

    /** 사용자가 입력한 긴 글의 요약 */
    TEXT_SUMMARY,

    /** Agent 가 수집한 PC 상태 수치를 사람이 읽을 문장으로 설명 */
    SYSTEM_STATUS_EXPLANATION
}
