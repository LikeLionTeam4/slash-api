/**
 * 비동기 AI 작업 원장.
 *
 * <p>애그리거트: {@code async_jobs} + {@code outbox_events}
 *
 * <p><b>지금 이 패키지가 실제로 하는 일은 원장 접근뿐이다.</b>
 * <ul>
 *   <li>{@link com.likelion.slash.job.AsyncJobRepository} — 요약 작업의 원장.
 *       {@code llm} 패키지의 접수·실행·스윕이 쓴다</li>
 *   <li>{@link com.likelion.slash.job.OutboxEventRepository} — {@code outbox_events} 접근.
 *       <b>부르는 곳이 시험뿐이다</b> (아래 참고)</li>
 * </ul>
 *
 * <p><b>SQS 전달 경로는 만들어지지 않았고, 이제 만들지 않는다.</b> 설계 문서(2.8.2)에는
 * "미발행 {@code outbox_events} 를 읽어 SQS 로 발행", "최대 수신 3회 후 DLQ 이동",
 * "Worker 가 내부 API 로 결과 전달"이 담당 범위로 적혀 있었지만 <b>그 코드는 존재한 적이
 * 없다.</b> SQS 클라이언트 의존성조차 넣지 않았다. slash-docs#3(LLM 실행 구조 전환)으로
 * {@code slash-api → SQS → slash-llm → GPU EC2} 경로 자체가 폐기되면서 관련 이슈(#48)도
 * 닫혔다. 요약은 서버가 직접 호출한다 — CPU 추출 요약은 {@code nlu} 패키지로, GPU 요약은
 * {@code llm} 패키지의 {@code LlmSummaryRunner} 가 별도 스레드에서 동기 호출한다.
 *
 * <p>그래서 {@code outbox_events} 표와 그 저장소는 <b>쓰이지 않은 채 남아 있다.</b>
 * {@code async_jobs} 는 반대로 살아 있다 — GPU 로 접수해 둔 과거 원장을
 * {@code LlmJobSweeper} 가 마감해야 하기 때문이다.
 *
 * <p>{@link com.likelion.slash.common.enums.AsyncJobStatus} 의 값 설명도 같은 사정이다 —
 * {@code PENDING} 을 "Outbox 저장 완료", {@code QUEUED} 를 "SQS 발행 완료",
 * {@code RUNNING} 을 "Worker 수신·실행", {@code FAILED} 를 "최대 수신 3회 후 DLQ" 로
 * 적어 두었지만 실제로는 {@code LlmSummaryEnqueuer} 가 접수하고 {@code LlmSummaryRunner}
 * 가 같은 Pod 의 별도 스레드에서 실행한다. <b>값 자체는 과거 이력이 쓰고 있어 바꾸지
 * 않는다</b> — 뜻을 다시 적는 일은 {@code processing_route} 정리(#58)와 함께 판단한다.
 *
 * <p>관련 문서: 0.5.9 · 2.8.2 · 3.7.2 · 3.8 · WBS W3-02 · W3-03 · slash-docs#3
 */
package com.likelion.slash.job;
