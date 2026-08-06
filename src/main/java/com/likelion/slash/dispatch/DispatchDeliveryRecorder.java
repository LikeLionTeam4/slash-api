package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TASK 프레임이 실제로 소켓으로 나간 시점을 전달 원장에 기록한다.
 *
 * <p>기록하는 주체가 <b>발행한 Pod 이 아니라 연결을 보유한 Pod</b> 이라는 점이 중요하다.
 * 발행만으로 DISPATCHED 로 올리면, 대상 Pod 이 재시작 중이라 아무도 받지 못한 전달까지
 * 보낸 것으로 남아 스윕이 다시 집어 가지 않는다. 그러면 그 작업은 영영 전달되지 않는다.
 *
 * <p>반대로 여기서 기록하면 "발행됐지만 아직 아무 Pod 도 내보내지 않은" 전달만 PENDING 으로
 * 남아 {@link PendingDispatchSweeper} 의 대상이 된다.
 *
 * <p>관련 문서: 3.6.2 · WBS W1-06
 */
@Component
public class DispatchDeliveryRecorder {

    private static final Logger log = LoggerFactory.getLogger(DispatchDeliveryRecorder.class);

    private final AgentDispatchRepository agentDispatchRepository;

    public DispatchDeliveryRecorder(AgentDispatchRepository agentDispatchRepository) {
        this.agentDispatchRepository = agentDispatchRepository;
    }

    /**
     * 소켓으로 내보낸 프레임을 원장에 반영한다. TASK 가 아닌 프레임은 아무 일도 하지 않는다.
     *
     * <p>재전송으로 두 번 나가도 {@code attempt_count} 만 올라간다. 상태는 이미 DISPATCHED 다.
     */
    public void recordDelivered(JsonNode frame) {
        if (!AgentTaskFrame.TYPE.equals(frame.path("type").asText())) {
            return;
        }

        parseUuid(frame.path("dispatchId").asText(null))
                .flatMap(agentDispatchRepository::findByPublicId)
                .ifPresentOrElse(
                        dispatch -> agentDispatchRepository.markDispatched(dispatch.getId()),
                        () -> log.warn("전달 기록을 찾지 못했다 dispatchId={}",
                                frame.path("dispatchId").asText(null)));
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
