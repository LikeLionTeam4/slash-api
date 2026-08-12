package com.likelion.slash.task;

import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.ws.UserEventPublisher;
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
     * <p>여기서 작업을 한 번 더 읽는 것은 {@code user_id}·{@code public_id} 가 필요해서다.
     * 상태가 바뀔 때만 일어나므로 잦은 조회가 아니다.
     */
    private void notifyStatusChanged(long taskId, TaskStatus from, TaskStatus to) {
        taskRepository.findById(taskId).ifPresent(task ->
                userEvents.taskStatusChanged(task.getUserId(), task.getPublicId(), from, to));
    }

    /** 최종 상태에 닿았음을 알린다. 프론트는 이 신호로 결과를 다시 조회한다. */
    private void notifyFinished(long taskId, TaskStatus from, TaskStatus terminal) {
        taskRepository.findById(taskId).ifPresent(task -> {
            userEvents.taskStatusChanged(task.getUserId(), task.getPublicId(), from, terminal);
            userEvents.taskResultAvailable(task.getUserId(), task.getPublicId(), terminal, task.getResult());
        });
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
        if (!taskRepository.transition(taskId, TaskStatus.ANALYZING, next)) {
            return false;
        }
        taskEventRepository.append(taskId, TaskStatus.ANALYZING, next, reasonCode, message);
        notifyStatusChanged(taskId, TaskStatus.ANALYZING, next);
        return true;
    }

    /** 중간 상태로 옮기고 기록한다. */
    @Transactional
    public boolean move(long taskId, TaskStatus from, TaskStatus to, String reasonCode, String message) {
        if (!taskRepository.transition(taskId, from, to)) {
            log.debug("상태 전이 실패. 이미 {} 가 아니다. taskId={}", from, taskId);
            return false;
        }
        taskEventRepository.append(taskId, from, to, reasonCode, message);
        notifyStatusChanged(taskId, from, to);
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
        if (!taskRepository.succeed(taskId, TaskStatus.RUNNING, result)) {
            return false;
        }
        taskEventRepository.append(taskId, TaskStatus.RUNNING, TaskStatus.SUCCEEDED, null, message);
        notifyFinished(taskId, TaskStatus.RUNNING, TaskStatus.SUCCEEDED);
        return true;
    }

    /**
     * Agent 가 보고한 실패를 반영해 마감한다.
     *
     * <p>ACK 거부는 {@code QUEUED} 에서, RESULT 실패는 {@code RUNNING} 에서 온다. 부르는 쪽이
     * 둘 중 어디인지 알 필요가 없게 여기서 모두 시도한다.
     *
     * @return 반영 여부. 거짓이면 이미 마감된 작업이다.
     */
    @Transactional
    public boolean failFromAgent(long taskId, ErrorCode errorCode, String message) {
        return fail(taskId, TaskStatus.RUNNING, errorCode, message)
                || fail(taskId, TaskStatus.QUEUED, errorCode, message);
    }

    /** 실패로 마감하고 기록한다. */
    @Transactional
    public boolean fail(long taskId, TaskStatus from, ErrorCode errorCode, String message) {
        if (!taskRepository.finishWithError(taskId, from, TaskStatus.FAILED, errorCode)) {
            log.debug("실패 마감을 반영하지 못했다. 이미 {} 가 아니다. taskId={}", from, taskId);
            return false;
        }
        taskEventRepository.append(taskId, from, TaskStatus.FAILED, errorCode.name(), message);
        notifyFinished(taskId, from, TaskStatus.FAILED);
        return true;
    }
}
