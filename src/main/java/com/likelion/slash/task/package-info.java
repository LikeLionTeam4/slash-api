/**
 * 사용자 요청의 원장(Task)과 상태 타임라인.
 *
 * <p>애그리거트: {@code tasks} + {@code task_events} + {@code idempotency_records}
 *
 * <p>담당 범위
 * <ul>
 *   <li>{@code POST /requests} — 멱등 키와 함께 접수하고 taskId 를 반환 (202)</li>
 *   <li>상태 전이 관리와 {@code task_events} 기록 — 같은 트랜잭션에서 수행</li>
 *   <li>NLU 분석 결과 검증 후 Tool 정책에 따라 실행 위치 결정</li>
 *   <li>작업 단건·이벤트·최근 이력(커서 20건) 조회</li>
 * </ul>
 *
 * <p>허용되지 않은 상태 변경은 409 Conflict 로 거부한다.
 * {@link com.likelion.slash.common.enums.TaskStatus#canTransitionTo} 로 검증한다. (문서 3.10)
 *
 * <p>즉시 완료 가능한 요청도 이력을 일관되게 만들기 위해 Task 를 생성한다. (문서 3.4.6)
 *
 * <p>관련 문서: 0.5.4 · 0.5.5 · 2.8.1 · 3.4.6~3.4.8 · WBS W1-04
 */
package com.likelion.slash.task;
