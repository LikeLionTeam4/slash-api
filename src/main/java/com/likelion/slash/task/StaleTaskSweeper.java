package com.likelion.slash.task;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아무 데도 매여 있지 않은 채 오래 남은 작업을 만료로 마감한다. (WBS W1-04)
 *
 * <p>전달이 붙은 작업은 {@code DispatchExpirySweeper} 가 그 전달의 기한에 맞춰 정리한다.
 * 여기서 주워야 하는 것은 <b>전달이 애초에 만들어지지 않은 작업</b>이라 그 그물에 걸리지 않는다.
 * <ul>
 *   <li>{@code WAITING_FOR_DEVICE} — PC 를 켜지 않아 계속 기다린다</li>
 *   <li>{@code NEEDS_CLARIFICATION} — 되물었는데 사용자가 답하지 않고 창을 닫았다</li>
 *   <li>{@code ANALYZING} · {@code CREATED} — 분석 도중 Pod 이 죽어 되돌릴 주체가 사라졌다</li>
 * </ul>
 *
 * <p>이것이 없으면 그런 작업이 <b>화면에서 영영 "진행 중"</b>으로 남는다. 사용자 눈에는
 * 멈춘 것으로 보이고, 목록에도 계속 쌓인다.
 *
 * <p><b>기한을 넉넉히 잡는 이유</b> — 여기 걸리는 상태는 대부분 사람을 기다리는 중이다.
 * PC 를 켜러 갔거나 되묻는 말에 답하는 중일 수 있어, 짧게 잡으면 멀쩡히 기다리던 작업을
 * 서버가 먼저 지워 버린다. 반대로 길어도 손해가 적다 — 기기를 막는 것은 전달이지 작업이 아니다.
 */
@Component
public class StaleTaskSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleTaskSweeper.class);

    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;

    /** 접수 후 이 시간이 지나도 끝나지 않은 작업을 만료로 본다. */
    private final Duration staleAfter;

    /** 한 회차에서 처리할 최대 건수. */
    private final int batchSize;

    public StaleTaskSweeper(TaskRepository taskRepository,
                            TaskStateWriter stateWriter,
                            @Value("${slash.task.stale-after}") Duration staleAfter,
                            @Value("${slash.task.expiry-sweep.batch-size}") int batchSize) {
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유 — {@code @Scheduled} 의 문자열 값은 설정 파일의 {@code 1m} 표기를
     * 그대로 해석하지 못한다. 다른 스윕과 같다.
     */
    @Scheduled(
            fixedDelayString = "${slash.task.expiry-sweep.interval-ms}",
            initialDelayString = "${slash.task.expiry-sweep.interval-ms}")
    public void expireStaleTasks() {
        try {
            List<TasksRecord> overdue =
                    taskRepository.findOverdue(SlashTime.now().minus(staleAfter), batchSize);

            if (overdue.isEmpty()) {
                return;
            }

            int expired = 0;
            for (TasksRecord task : overdue) {
                if (expire(task)) {
                    expired++;
                }
            }

            if (expired > 0) {
                log.info("기한이 지난 작업 {}건을 만료로 마감했다 (대상 {}건)", expired, overdue.size());
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("작업 만료 스윕 실패: {}", e.getMessage());
        }
    }

    /** 한 건이 실패해도 나머지를 계속한다. 남은 것은 다음 회차가 다시 집는다. */
    private boolean expire(TasksRecord task) {
        try {
            TaskStatus current = TaskStatus.valueOf(task.getStatus());
            return stateWriter.expire(task.getId(), current, message(current));

        } catch (Exception e) {
            log.error("작업을 만료로 마감하지 못했다 taskId={}: {}", task.getPublicId(), e.getMessage());
            return false;
        }
    }

    /**
     * 왜 끝났는지 사용자가 알 수 있게 상태별로 다르게 적는다.
     *
     * <p>타임라인의 마지막 칸에 그대로 보이는 문장이다. "기한이 지났습니다" 하나로 뭉뚱그리면
     * PC 를 켜야 했던 것인지 답을 해야 했던 것인지 알 수 없다.
     */
    private String message(TaskStatus from) {
        return switch (from) {
            case WAITING_FOR_DEVICE -> "PC 가 켜지지 않아 기한이 지났습니다.";
            case NEEDS_CLARIFICATION -> "답을 받지 못해 기한이 지났습니다.";
            default -> "작업이 기한 안에 끝나지 않았습니다.";
        };
    }
}
