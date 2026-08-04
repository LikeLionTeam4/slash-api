package com.likelion.slash.common.enums;

/**
 * SQS 로 전달되는 비동기 작업(async_jobs)의 상태. 개발문서 2.4.3
 */
public enum AsyncJobStatus {

    /** Outbox 저장 완료 */
    PENDING,

    /** SQS 발행 완료 */
    QUEUED,

    /** Worker 수신·실행 */
    RUNNING,

    /** 결과 반영 완료 */
    SUCCEEDED,

    /** 재시도 종료 (최대 수신 3회 후 DLQ) */
    FAILED,

    /** deadline_at 경과 */
    EXPIRED
}
