/**
 * 비동기 AI 작업과 Outbox 전달.
 *
 * <p>애그리거트: {@code async_jobs} + {@code outbox_events}
 *
 * <p>담당 범위
 * <ul>
 *   <li>AI Job 접수 — Task 상태 QUEUED, async_jobs, outbox_events, task_events 를 한 트랜잭션으로 저장</li>
 *   <li>미발행 outbox_events 를 읽어 SQS 로 발행하고 published_at 기록</li>
 *   <li>Worker 결과 수신 — jobId·eventId 중복을 거부하고 한 번만 반영</li>
 *   <li>최대 수신 3회 후 DLQ 이동, Task 를 FAILED 로 마감</li>
 *   <li>모델 준비 상태 조회 — 준비되지 않았으면 MODEL_NOT_READY 로 즉시 안내</li>
 * </ul>
 *
 * <p>SQS 발행은 DB 트랜잭션 밖의 전달기가 처리한다. (문서 2.8.2)
 * Worker 는 RDS·Valkey 에 직접 접근하지 않고 내부 API 로만 결과를 전달한다. (문서 Q-01)
 *
 * <p>관련 문서: 0.5.9 · 2.8.2 · 3.7.2 · 3.8 · WBS W3-02 · W3-03
 */
package com.likelion.slash.job;
