package com.likelion.slash.llm;

import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.task.TaskStateWriter;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요약 작업을 접수한다.
 *
 * <p><b>실행({@link LlmSummaryRunner})과 나눠 둔 이유가 트랜잭션이다.</b> 접수는 Task 전이와
 * 원장 생성을 <b>한 트랜잭션으로 묶어야</b> 하고, 실행은 모델을 기다리는 수십 초 동안
 * DB 연결을 붙들지 <b>않아야</b> 한다. 한 클래스에 두면 둘 중 하나는 어긋난다.
 */
@Component
public class LlmSummaryEnqueuer {

    private final AsyncJobRepository asyncJobRepository;
    private final TaskStateWriter stateWriter;

    public LlmSummaryEnqueuer(AsyncJobRepository asyncJobRepository, TaskStateWriter stateWriter) {
        this.asyncJobRepository = asyncJobRepository;
        this.stateWriter = stateWriter;
    }

    /**
     * 요약 작업을 접수한다. <b>Task 전이와 원장 생성을 한 트랜잭션으로 묶는다.</b>
     *
     * <p>갈라 두면 그 사이에 실패했을 때 원장 없이 {@code QUEUED} 인 Task 가 남는다. 스윕은
     * 원장을 보고 도는 것이라 그 Task 를 찾지 못하고, 화면에는 끝나지 않는 진행 표시만 남는다.
     *
     * <p>실행은 이 메서드가 <b>돌아온 뒤</b>(= 커밋된 뒤) 시작해야 한다. 커밋 전에 시작하면
     * 다른 Pod 이나 스윕이 아직 보이지 않는 행을 두고 움직인다.
     *
     * @return 만들어진 원장. 이미 다른 상태로 옮겨간 Task 면 비어 있다.
     */
    @Transactional
    public Optional<AsyncJobsRecord> enqueue(long taskId,
                                             TaskType taskType,
                                             JSONB input,
                                             String requestSummary,
                                             OffsetDateTime deadlineAt) {

        boolean applied = stateWriter.applyAnalysisAndMove(
                taskId, taskType, ExecutionTarget.BACKEND, null, input, requestSummary,
                TaskStatus.QUEUED, null, "요약을 맡겼습니다.");

        if (!applied) {
            return Optional.empty();
        }

        return Optional.of(createLedger(taskId, input, deadlineAt));
    }

    /**
     * 승인받은 요약을 접수한다. (P0-C)
     *
     * <p>{@link #enqueue} 와 갈라 둔 이유는 <b>시작 상태와 이미 저장된 것</b>이 다르기
     * 때문이다. 접수 직후에는 {@code ANALYZING} 에서 분석 결과까지 함께 반영하지만, 승인
     * 뒤에는 {@code WAITING_FOR_APPROVAL} 에서 시작하고 작업 유형·입력값·실행 위치는 이미
     * 물어보기 전에 저장돼 있다 — 사용자가 본 것과 같아야 하므로 다시 쓰지 않는다.
     *
     * <p><b>한 트랜잭션이어야 하는 것은 같다.</b> 나눠 두면 그 사이의 실패가 원장 없는
     * {@code QUEUED} Task 를 남기고, 스윕은 원장을 보고 도는 것이라 찾지 못한다.
     *
     * @return 만들어진 원장. 이미 다른 상태로 넘어간 Task 면 비어 있다.
     */
    @Transactional
    public Optional<AsyncJobsRecord> enqueueApproved(long taskId, JSONB input, OffsetDateTime deadlineAt) {
        if (!stateWriter.move(taskId, TaskStatus.WAITING_FOR_APPROVAL, TaskStatus.QUEUED,
                null, "승인을 받아 요약을 맡겼습니다.")) {
            return Optional.empty();
        }

        return Optional.of(createLedger(taskId, input, deadlineAt));
    }

    private AsyncJobsRecord createLedger(long taskId, JSONB input, OffsetDateTime deadlineAt) {
        AsyncJobsRecord job = asyncJobRepository.create(taskId, AsyncJobType.TEXT_SUMMARY, input, deadlineAt);

        // PENDING 은 "아직 아무에게도 맡기지 않은" 상태다. 지금은 SQS 없이 곧바로 부르므로
        // 맡긴 시점이 여기다. (SQS 로 옮기면 발행에 성공한 시점으로 옮겨 간다)
        asyncJobRepository.markQueued(job.getId());
        return job;
    }
}
