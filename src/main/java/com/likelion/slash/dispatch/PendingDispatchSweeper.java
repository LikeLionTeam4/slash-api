package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import com.likelion.slash.ws.WsMessagePublisher;
import com.likelion.slash.ws.WsTarget;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아직 아무 Pod 도 내보내지 못한 전달을 다시 발행한다. (WBS W1-06 · docs/w1-06-wss-routing.md 5.4)
 *
 * <p>Valkey Pub/Sub 은 전달을 보장하지 않는다. 발행 순간 대상 Pod 이 재시작 중이면 메시지는
 * 그대로 사라진다. 그 경우 전달은 {@code PENDING} 으로 남는데, 이것을 주워 가는 것이 이 스윕이다.
 * 재연결 재전송이 주 경로이고 스윕은 그 아래에 깔아 두는 그물이다.
 *
 * <p><b>중복 발행은 안전하다.</b> 여러 Pod 이 같은 전달을 집어도 문제가 없도록 세 겹으로 막는다.
 * <ol>
 *   <li>전달마다 Valkey 짧은 잠금을 잡아 한 Pod 만 발행한다 (문서 3.8 이 허용하는 용도)</li>
 *   <li>같은 {@code dispatchId} 를 두 번 받으면 Agent 가 무시한다 (계약)</li>
 *   <li>ACK·RESULT 는 활성 상태에서만 반영된다 (Repository 조건절)</li>
 * </ol>
 *
 * <p><b>만료된 전달은 건드리지 않는다.</b> 기한이 지난 것을 다시 보내면 Agent 가 거부할 뿐이다.
 * 그것을 마감하는 일은 {@link AgentDispatchRepository#expireOverdue} 를 부르는 별도 배치의
 * 몫이며 아직 없다. (Task 상태 전이가 함께 필요해 W1-04 로 묶인다)
 *
 * <p><b>기기가 꺼져 있으면 기한이 다할 때까지 계속 재발행한다.</b> 어느 Pod 도 연결을 갖고
 * 있지 않으면 전달은 PENDING 으로 남고 다음 회차에 다시 대상이 되기 때문이다.
 * 발행 자체는 가벼워서 P0 규모에서는 문제가 되지 않는다. 기기 수가 늘어 부담이 되면
 * 잠금 유지 시간을 스윕 주기보다 길게 잡아 재시도 간격을 벌리는 것이 가장 싼 해법이다.
 */
@Component
public class PendingDispatchSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingDispatchSweeper.class);

    private static final String LOCK_KEY_PREFIX = "dispatch:resend:";

    private final AgentDispatchRepository agentDispatchRepository;
    private final TaskRepository taskRepository;
    private final WsMessagePublisher publisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 발행된 지 이 시간이 지나도 PENDING 이면 유실로 본다.
     *
     * <p>너무 짧으면 방금 발행한 것을 곧바로 다시 보내고, 너무 길면 유실 복구가 늦어진다.
     */
    private final Duration staleAfter;

    /** 한 번에 처리할 최대 건수. 장애 복구 직후 밀린 양이 많아도 한 회차를 길게 잡지 않는다. */
    private final int batchSize;

    public PendingDispatchSweeper(AgentDispatchRepository agentDispatchRepository,
                                  TaskRepository taskRepository,
                                  WsMessagePublisher publisher,
                                  StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  @Value("${slash.dispatch.resend.stale-after}") Duration staleAfter,
                                  @Value("${slash.dispatch.resend.batch-size}") int batchSize) {
        this.agentDispatchRepository = agentDispatchRepository;
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유 — {@code @Scheduled} 의 문자열 값은 설정 파일의 {@code 5s} 표기를
     * 그대로 해석하지 못한다. 다른 설정과 표기가 달라 보이지만 여기서만 예외를 둔다.
     */
    @Scheduled(
            fixedDelayString = "${slash.dispatch.resend.interval-ms}",
            initialDelayString = "${slash.dispatch.resend.interval-ms}")
    public void resendPending() {
        try {
            OffsetDateTime now = SlashTime.now();
            List<AgentDispatchesRecord> pending =
                    agentDispatchRepository.findPendingForResend(now.minus(staleAfter), now, batchSize);

            int resent = 0;
            for (AgentDispatchesRecord dispatch : pending) {
                if (resend(dispatch)) {
                    resent++;
                }
            }

            if (resent > 0) {
                log.info("미전달 작업 재발행 {}건 (대상 {}건)", resent, pending.size());
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("미전달 작업 스윕 실패: {}", e.getMessage());
        }
    }

    private boolean resend(AgentDispatchesRecord dispatch) {
        Optional<TasksRecord> task = taskRepository.findById(dispatch.getTaskId());

        // 분석 전이라 작업 유형이 없으면 보낼 것이 없다. 이 상태로 전달이 만들어졌다면
        // 그것은 W1-04 의 오류이므로 조용히 넘기지 않고 남긴다.
        if (task.isEmpty() || task.get().getTaskType() == null) {
            log.warn("작업 유형이 없어 재발행하지 않는다 dispatchId={}", dispatch.getPublicId());
            return false;
        }

        // 여러 Pod 이 동시에 스윕한다. 한 회차에서는 한 Pod 만 발행한다.
        if (!acquireLock(dispatch)) {
            return false;
        }

        publisher.send(WsTarget.DEVICE, dispatch.getDeviceId(),
                AgentTaskFrame.of(task.get(), dispatch, parameters(task.get())));
        return true;
    }

    /**
     * 재발행 잠금. 다음 회차에는 다시 시도할 수 있도록 스윕 주기만큼만 잡는다.
     *
     * <p>Valkey 가 끊긴 상황에서는 잠금을 잡지 못한다. 그때는 발행해 봐야 어차피 전달되지
     * 않으므로 건너뛰는 것이 맞다.
     */
    private boolean acquireLock(AgentDispatchesRecord dispatch) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LOCK_KEY_PREFIX + dispatch.getPublicId(), "1", staleAfter));
        } catch (Exception e) {
            log.warn("재발행 잠금 실패 dispatchId={}: {}", dispatch.getPublicId(), e.getMessage());
            return false;
        }
    }

    private JsonNode parameters(TasksRecord task) {
        JSONB parameters = task.getParameters();
        if (parameters == null || parameters.data() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(parameters.data());
        } catch (Exception e) {
            // 입력값을 못 읽어도 작업 유형만으로 보낼 수 있는 경우가 있다. (예: /status)
            log.warn("작업 입력값을 읽지 못했다 taskId={}: {}", task.getPublicId(), e.getMessage());
            return null;
        }
    }
}
