package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.ws.WsMessagePublisher;
import com.likelion.slash.ws.WsTarget;
import java.time.Duration;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 작업 하나를 기기로 내보낸다. (WBS W1-04)
 *
 * <p>전달 원장을 만들고 TASK 프레임을 발행하는 것까지가 여기 몫이다. Task 의 상태 전이는
 * 부르는 쪽이 한다 — 최초 접수와 대기 해소는 출발 상태가 서로 다르기 때문이다.
 *
 * <p><b>발행이 곧 전달은 아니다.</b> 연결을 보유한 Pod 이 실제로 소켓에 쓸 때
 * {@link DispatchDeliveryRecorder} 가 DISPATCHED 로 올린다. 아무도 받지 못하면 PENDING 으로
 * 남아 {@link PendingDispatchSweeper} 가 다시 집어 간다.
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final AgentDispatchRepository agentDispatchRepository;
    private final WsMessagePublisher publisher;
    private final ObjectMapper objectMapper;

    /**
     * 전달 기한. 이 시각을 지나면 Agent 는 실행하지 않는다.
     *
     * <p>기기가 켜져 있는 것을 확인한 뒤에만 전달을 만들기 때문에 짧게 잡아도 된다.
     * 꺼져 있는 동안 접수된 작업은 전달을 만들지 않고 {@code WAITING_FOR_DEVICE} 로 기다린다.
     */
    private final Duration ttl;

    public TaskDispatcher(AgentDispatchRepository agentDispatchRepository,
                          WsMessagePublisher publisher,
                          ObjectMapper objectMapper,
                          @Value("${slash.dispatch.ttl}") Duration ttl) {
        this.agentDispatchRepository = agentDispatchRepository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * 전달을 만들고 TASK 프레임을 발행한다.
     *
     * @return 만들어진 전달 원장
     */
    public AgentDispatchesRecord dispatch(TasksRecord task, long deviceId) {
        AgentDispatchesRecord dispatch =
                agentDispatchRepository.create(task.getId(), deviceId, SlashTime.now().plus(ttl));

        JsonNode parameters = parameters(task);
        String payloadSha256 = AgentTaskPayloadHash.of(
                objectMapper,
                task.getPublicId(),
                dispatch.getPublicId(),
                task.getTaskType(),
                parameters);

        publisher.send(WsTarget.DEVICE, deviceId,
                AgentTaskFrame.of(task, dispatch, parameters, payloadSha256));

        log.info("작업 전달 발행 taskId={} dispatchId={} deviceId={}",
                task.getPublicId(), dispatch.getPublicId(), deviceId);

        return dispatch;
    }

    /**
     * 저장된 입력값을 프레임에 실을 형태로 읽는다.
     *
     * <p>{@link PendingDispatchSweeper} 와 같은 규칙을 쓴다 — 읽지 못해도 작업 유형만으로
     * 실행할 수 있는 경우가 있어({@code /status}) 통째로 실패시키지 않는다.
     */
    private JsonNode parameters(TasksRecord task) {
        JSONB parameters = task.getParameters();
        if (parameters == null || parameters.data() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(parameters.data());
        } catch (Exception e) {
            log.warn("작업 입력값을 읽지 못했다 taskId={}: {}", task.getPublicId(), e.getMessage());
            return null;
        }
    }
}
