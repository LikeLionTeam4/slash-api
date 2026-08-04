/**
 * slash-nlu 내부 API 호출 클라이언트.
 *
 * <p>담당 범위
 * <ul>
 *   <li>{@code POST /internal/v1/nlu/analyze} 호출 — 원문과 허용 도구 목록 전달</li>
 *   <li>응답 검증 — toolCode 가 allowedTools 에 포함되고 인자·신뢰도가 계약과 일치하는지 확인</li>
 *   <li>실패 처리 — Task 를 FAILED, ErrorCode 를 NLU_UNAVAILABLE 로 종료</li>
 * </ul>
 *
 * <p>P0 규칙 (문서 3.7.1)
 * <ul>
 *   <li>Service Port 80 → FastAPI targetPort 8000</li>
 *   <li>연결·응답 합계 2초 시간 제한, <b>자동 재시도 없음</b></li>
 *   <li>검증 실패 시 날씨 API·SQS·Agent 를 실행하지 않는다</li>
 * </ul>
 *
 * <p>Namespace 를 코드에 고정하지 않고 환경 변수로 주입받는다. (문서 3.1.1)
 *
 * <p>관련 문서: IN-07 · 3.7.1 · WBS W1-04
 */
package com.likelion.slash.nlu;
