package com.likelion.slash.dispatch;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import com.likelion.slash.task.TaskStateWriter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기한이 지난 전달을 마감하고 그 작업까지 함께 끝낸다. (WBS W1-04)
 *
 * <p><b>이 스윕이 없으면 PC 가 영구히 막힌다.</b> {@code uk_dispatch_active_device} 가 기기당
 * 활성 전달을 한 건으로 제한하는데, 그것을 푸는 수단이
 * {@link AgentDispatchRepository#expireOverdue} 뿐이다. PC 가 작업을 받은 뒤 꺼지면 ACK 도
 * RESULT 도 오지 않아 전달이 활성인 채로 남고, <b>그 PC 는 다시 켜도 새 작업을 받지 못한다</b> —
 * {@code TaskRepository.isDeviceOccupied} 에 걸려 모든 요청이 {@code DEVICE_BUSY} 로 마감된다.
 * 사용자가 기기를 해제하고 다시 등록해도 {@code agent_dispatches} 행은 그대로라 소용이 없다.
 *
 * <p><b>표 두 개를 순서대로 다룬다.</b> 한쪽만 하면 다른 쪽이 어긋난다.
 * <ol>
 *   <li>전달을 만료하지 않으면 → 기기가 계속 막힌다</li>
 *   <li>작업을 마감하지 않으면 → 기기는 풀리지만 화면의 진행 표시가 영영 돌아간다</li>
 * </ol>
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 전달 만료는 활성 상태를 함께 보는 한 문장이라
 * 한 Pod 만 그 행을 받고({@code expireOverdue} 주석), 작업 마감은
 * {@link TaskStateWriter#expire} 의 compare-and-set 이 한 번만 통과시킨다.
 * 잠금을 두지 않는 이유다. ({@link PendingDispatchSweeper} 는 발행이라는 부수 효과가 있어
 * 잠금이 필요했다)
 */
@Component
public class DispatchExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(DispatchExpirySweeper.class);

    private static final String MESSAGE = "PC 가 기한 안에 작업을 끝내지 못했습니다.";

    private final AgentDispatchRepository agentDispatchRepository;
    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;

    /** 한 회차에서 마감할 최대 건수. 밀린 양이 많아도 한 회차를 길게 잡지 않는다. */
    private final int batchSize;

    public DispatchExpirySweeper(AgentDispatchRepository agentDispatchRepository,
                                 TaskRepository taskRepository,
                                 TaskStateWriter stateWriter,
                                 @Value("${slash.dispatch.expiry-sweep.batch-size}") int batchSize) {
        this.agentDispatchRepository = agentDispatchRepository;
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유 — {@code @Scheduled} 의 문자열 값은 설정 파일의 {@code 10s} 표기를
     * 그대로 해석하지 못한다. {@link PendingDispatchSweeper} 와 같다.
     */
    @Scheduled(
            fixedDelayString = "${slash.dispatch.expiry-sweep.interval-ms}",
            initialDelayString = "${slash.dispatch.expiry-sweep.interval-ms}")
    public void expireOverdueDispatches() {
        try {
            List<AgentDispatchesRecord> expired = agentDispatchRepository.expireOverdue(
                    SlashTime.now(), ErrorCode.TASK_EXPIRED.name(), batchSize);

            if (expired.isEmpty()) {
                return;
            }

            int finished = 0;
            for (AgentDispatchesRecord dispatch : expired) {
                if (finishTask(dispatch)) {
                    finished++;
                }
            }

            log.info("기한이 지난 전달 {}건을 마감해 기기를 풀었다 (작업 마감 {}건)", expired.size(), finished);

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("전달 만료 스윕 실패: {}", e.getMessage());
        }
    }

    /**
     * 전달에 매달려 있던 작업을 만료로 마감한다.
     *
     * <p><b>한 건이 실패해도 나머지를 계속한다.</b> 전달은 이미 만료돼 다음 회차의 대상이 아니라,
     * 여기서 멈추면 뒤에 있는 작업들이 마감되지 못한 채 남는다. 그렇게 남은 작업은 결국
     * {@code StaleTaskSweeper} 가 주워 가지만 그만큼 화면이 늦게 따라온다.
     */
    private boolean finishTask(AgentDispatchesRecord dispatch) {
        try {
            Optional<TasksRecord> task = taskRepository.findById(dispatch.getTaskId());
            if (task.isEmpty()) {
                log.warn("전달에 딸린 작업이 없다 dispatchId={}", dispatch.getPublicId());
                return false;
            }

            TaskStatus current = TaskStatus.valueOf(task.get().getStatus());

            // 결과가 방금 도착해 이미 끝난 작업이다. 만료로 덮어쓰지 않는다.
            if (current.isTerminal()) {
                return false;
            }

            return stateWriter.expire(task.get().getId(), current, MESSAGE);

        } catch (Exception e) {
            log.error("만료된 전달의 작업을 마감하지 못했다 dispatchId={}: {}",
                    dispatch.getPublicId(), e.getMessage());
            return false;
        }
    }
}
