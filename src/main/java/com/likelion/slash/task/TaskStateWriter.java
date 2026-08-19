package com.likelion.slash.task;

import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.ws.UserEventPublisher;
import java.util.Optional;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 전이와 타임라인 기록을 한 트랜잭션으로 묶는다. (WBS W1-04)
 *
 * <p>둘을 따로 하면 전이는 됐는데 기록이 없거나 그 반대인 상태가 생긴다. 화면의 진행 표시가
 * 곧 이 타임라인이라 어긋나면 사용자에게 그대로 보인다.
 *
 * <p><b>접수 흐름 전체를 한 트랜잭션으로 묶지 않는 이유</b> — 그 안에 NLU 호출(최대 2초)이
 * 들어 있다. 외부 호출을 트랜잭션 안에 두면 그동안 {@code tasks} 행 잠금과 커넥션을 함께
 * 붙잡는다. Pod 당 커넥션이 10개뿐이라(Hikari 설정) 몇 건만 겹쳐도 말라붙는다.
 *
 * <p>전이가 거짓을 돌려주면 이미 다른 상태로 넘어간 작업이다. Agent 의 RESULT 가 먼저
 * 도착한 경우가 여기 해당하며, 나중에 온 쪽이 조용히 물러난다.
 */
@Component
public class TaskStateWriter {

    private static final Logger log = LoggerFactory.getLogger(TaskStateWriter.class);

    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;
    private final UserEventPublisher userEvents;

    public TaskStateWriter(TaskRepository taskRepository,
                           TaskEventRepository taskEventRepository,
                           UserEventPublisher userEvents) {
        this.taskRepository = taskRepository;
        this.taskEventRepository = taskEventRepository;
        this.userEvents = userEvents;
    }

    /**
     * 상태가 바뀐 것을 브라우저에 알린다.
     *
     * <p>타임라인 기록과 짝을 이룬다. 기록만 남기고 알리지 않으면 화면은 새로고침해야 따라온다.
     *
     * <p><b>알림 실패가 전이를 되돌리지 않는다.</b> 발행은 커밋 뒤에 일어나고
     * ({@link UserEventPublisher}) 그 안에서 예외를 삼킨다. 화면이 늦게 따라오는 것은
     * 불편이지만, 상태 전이가 취소되는 것은 사실이 달라지는 일이다.
     *
     * <p>인자로 받는 것은 <b>전이가 돌려준 그 행</b>이다. 다시 읽지 않는다.
     */
    private void notifyStatusChanged(TasksRecord task, TaskStatus from, TaskStatus to) {
        userEvents.taskStatusChanged(task.getUserId(), task.getPublicId(), from, to);
    }

    /** 최종 상태에 닿았음을 알린다. 프론트는 이 신호로 결과를 다시 조회한다. */
    private void notifyFinished(TasksRecord task, TaskStatus from, TaskStatus terminal) {
        userEvents.taskStatusChanged(task.getUserId(), task.getPublicId(), from, terminal);
        userEvents.taskResultAvailable(task.getUserId(), task.getPublicId(), terminal, task.getResult());
    }

    /** 접수 직후의 최초 기록. 아직 전이가 아니므로 이전 상태는 없다. */
    @Transactional
    public void recordCreated(long taskId, String message) {
        taskEventRepository.append(taskId, null, TaskStatus.CREATED, null, message);
    }

    /**
     * 분석 결과를 반영하고 다음 상태로 옮긴다.
     *
     * @return 반영 여부. 거짓이면 이미 {@code ANALYZING} 이 아니다.
     */
    @Transactional
    public boolean applyAnalysisAndMove(long taskId,
                                        TaskType taskType,
                                        ProcessingRoute processingRoute,
                                        Long deviceId,
                                        JSONB parameters,
                                        String requestSummary,
                                        TaskStatus next,
                                        String reasonCode,
                                        String message) {

        if (!taskRepository.applyAnalysis(taskId, taskType, processingRoute, deviceId, parameters, requestSummary)) {
            log.debug("분석 결과를 반영하지 못했다. 이미 다른 상태다. taskId={}", taskId);
            return false;
        }
        Optional<TasksRecord> moved = taskRepository.transition(taskId, TaskStatus.ANALYZING, next);
        if (moved.isEmpty()) {
            return false;
        }
        taskEventRepository.append(taskId, TaskStatus.ANALYZING, next, reasonCode, message);
        notifyStatusChanged(moved.get(), TaskStatus.ANALYZING, next);
        return true;
    }

    /** 중간 상태로 옮기고 기록한다. */
    @Transactional
    public boolean move(long taskId, TaskStatus from, TaskStatus to, String reasonCode, String message) {
        Optional<TasksRecord> moved = taskRepository.transition(taskId, from, to);
        if (moved.isEmpty()) {
            log.debug("상태 전이 실패. 이미 {} 가 아니다. taskId={}", from, taskId);
            return false;
        }
        taskEventRepository.append(taskId, from, to, reasonCode, message);
        notifyStatusChanged(moved.get(), from, to);
        return true;
    }

    /**
     * Agent 가 보낸 결과를 반영해 성공으로 마감한다. (WBS W1-04)
     *
     * <p><b>{@code RUNNING} 을 거치지 않고 결과가 올 수 있다.</b> ACK 가 유실되거나 작업이 아주
     * 빨리 끝나면 작업은 아직 {@code QUEUED} 인데 RESULT 가 먼저 도착한다. 전이 규칙에
     * {@code QUEUED → SUCCEEDED} 는 없으므로 그때는 {@code RUNNING} 을 한 칸 지나간다.
     * 결과가 왔다는 것 자체가 실행이 있었다는 증거라 없는 사실을 지어내는 것이 아니다.
     *
     * <p>타임라인에는 지나간 칸도 그대로 남긴다. 화면의 진행 표시가 이 기록이라, 건너뛰면
     * 실행된 적 없는 작업이 성공한 것처럼 보인다.
     *
     * @return 반영 여부. 거짓이면 이미 마감된 작업이다. (기한 만료 스윕이 먼저 닿은 경우)
     */
    @Transactional
    public boolean succeed(long taskId, JSONB result, String message) {
        if (finishSucceeded(taskId, result, message)) {
            return true;
        }

        // 아직 QUEUED 다. ACK 를 못 받은 것이므로 RUNNING 을 지나 다시 마감한다.
        if (!move(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, null, "PC 가 작업을 시작했습니다.")) {
            log.debug("성공 마감을 반영하지 못했다. 이미 마감된 작업이다. taskId={}", taskId);
            return false;
        }
        return finishSucceeded(taskId, result, message);
    }

    private boolean finishSucceeded(long taskId, JSONB result, String message) {
        Optional<TasksRecord> finished = taskRepository.succeed(taskId, TaskStatus.RUNNING, result);
        if (finished.isEmpty()) {
            return false;
        }
        taskEventRepository.append(taskId, TaskStatus.RUNNING, TaskStatus.SUCCEEDED, null, message);
        notifyFinished(finished.get(), TaskStatus.RUNNING, TaskStatus.SUCCEEDED);
        return true;
    }

    /**
     * 우리 대신 일하는 쪽에서 온 실패를 반영해 마감한다.
     *
     * <p>Agent 는 ACK 거부를 {@code QUEUED} 에서, RESULT 실패를 {@code RUNNING} 에서 보낸다.
     * 요약도 마찬가지로 시작 전({@code QUEUED})과 실행 중({@code RUNNING}) 어느 쪽에서든
     * 실패할 수 있다. 부르는 쪽이 둘 중 어디인지 알 필요가 없게 여기서 모두 시도한다.
     *
     * @return 반영 여부. 거짓이면 이미 마감된 작업이다.
     */
    @Transactional
    public boolean failFromWorker(long taskId, ErrorCode errorCode, String message) {
        return fail(taskId, TaskStatus.RUNNING, errorCode, message)
                || fail(taskId, TaskStatus.QUEUED, errorCode, message);
    }

    /** 실패로 마감하고 기록한다. */
    @Transactional
    public boolean fail(long taskId, TaskStatus from, ErrorCode errorCode, String message) {
        return finish(taskId, from, TaskStatus.FAILED, errorCode, message);
    }

    /**
     * 기한이 지난 작업을 만료로 마감한다. (만료 배치 · WBS W1-04)
     *
     * <p>어느 단계에서든 멈춘 채로 기한을 넘길 수 있어 이전 상태를 받는다. 배치는 조회한
     * 행의 현재 상태를 그대로 넘긴다.
     *
     * <p><b>거짓을 돌려주는 것이 정상 경로에 있다.</b> 조회와 마감 사이에 Agent 의 결과가
     * 도착했거나 다른 Pod 이 먼저 닿은 경우다. 늦게 온 쪽이 조용히 물러나면 된다 —
     * 이미 끝난 작업을 만료로 덮어쓰는 것이 훨씬 나쁘다.
     */
    @Transactional
    public boolean expire(long taskId, TaskStatus from, String message) {
        return finish(taskId, from, TaskStatus.EXPIRED, ErrorCode.TASK_EXPIRED, message);
    }

    /**
     * 최종 상태로 마감하고 기록한다.
     *
     * <p>실패와 만료는 마감 사유만 다르고 밟는 절차가 같다 — 결과를 지우고, 타임라인에 남기고,
     * 브라우저에 알린다. 한쪽에만 손대 두 경로가 어긋나지 않도록 여기로 모은다.
     */
    private boolean finish(long taskId,
                           TaskStatus from,
                           TaskStatus terminal,
                           ErrorCode errorCode,
                           String message) {
        Optional<TasksRecord> finished =
                taskRepository.finishWithError(taskId, from, terminal, errorCode);
        if (finished.isEmpty()) {
            log.debug("{} 마감을 반영하지 못했다. 이미 {} 가 아니다. taskId={}", terminal, from, taskId);
            return false;
        }
        taskEventRepository.append(taskId, from, terminal, errorCode.name(), message);
        notifyFinished(finished.get(), from, terminal);
        return true;
    }
}
