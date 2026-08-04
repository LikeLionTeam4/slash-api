/**
 * 로컬 에이전트로의 작업 전달.
 *
 * <p>애그리거트: {@code agent_dispatches}
 *
 * <p>담당 범위
 * <ul>
 *   <li>대상 Device 소유권·READY·Capability 확인</li>
 *   <li>기기별 짧은 Valkey 잠금 획득</li>
 *   <li>전달 기록 저장 후 WSS 로 TASK 프레임 전송</li>
 *   <li>ACK·RESULT 를 dispatchId 기준으로 한 번만 반영</li>
 *   <li>연결 복구 뒤 미완료 전달 재전송</li>
 * </ul>
 *
 * <p>Task 당 활성 전달은 한 건만 존재한다(부분 UNIQUE 제약).
 * P0 는 기기 한 대당 동시 작업 한 건만 허용한다. (문서 3.6.2)
 *
 * <p>관련 문서: 2.8.3 · 3.6.2 · WBS W1-04 · W2-03
 */
package com.likelion.slash.dispatch;
