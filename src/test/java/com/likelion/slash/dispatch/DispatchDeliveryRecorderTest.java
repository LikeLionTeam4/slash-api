package com.likelion.slash.dispatch;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DispatchDeliveryRecorder} 확인. (WBS W1-06)
 *
 * <p>실제로 소켓으로 나간 것만 DISPATCHED 로 올라가야 한다.
 * 발행만으로 올라가면 아무도 받지 못한 전달이 스윕 대상에서 빠진다.
 */
class DispatchDeliveryRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentDispatchRepository agentDispatchRepository = mock(AgentDispatchRepository.class);
    private final DispatchDeliveryRecorder recorder = new DispatchDeliveryRecorder(agentDispatchRepository);

    @Test
    @DisplayName("TASK 프레임이 나가면 전달을 DISPATCHED 로 올린다")
    void TASK_는_기록한다() throws Exception {
        UUID dispatchId = UUID.randomUUID();

        AgentDispatchesRecord dispatch = new AgentDispatchesRecord();
        dispatch.setId(9L);
        when(agentDispatchRepository.findByPublicId(dispatchId)).thenReturn(Optional.of(dispatch));

        recorder.recordDelivered(프레임("{\"type\":\"TASK\",\"dispatchId\":\"" + dispatchId + "\"}"));

        verify(agentDispatchRepository).markDispatched(9L);
    }

    @Test
    @DisplayName("TASK 가 아닌 프레임은 원장을 건드리지 않는다")
    void 다른_프레임은_무시한다() throws Exception {
        recorder.recordDelivered(프레임("{\"type\":\"CHALLENGE\",\"nonce\":\"abc\"}"));

        verify(agentDispatchRepository, never()).findByPublicId(org.mockito.ArgumentMatchers.any());
        verify(agentDispatchRepository, never()).markDispatched(anyLong());
    }

    @Test
    @DisplayName("없는 전달을 가리키면 아무것도 기록하지 않는다")
    void 없는_전달은_기록하지_않는다() throws Exception {
        when(agentDispatchRepository.findByPublicId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        recorder.recordDelivered(프레임("{\"type\":\"TASK\",\"dispatchId\":\"" + UUID.randomUUID() + "\"}"));

        verify(agentDispatchRepository, never()).markDispatched(anyLong());
    }

    @Test
    @DisplayName("dispatchId 가 깨져 있어도 예외 없이 넘어간다")
    void 형식이_깨져도_넘어간다() throws Exception {
        recorder.recordDelivered(프레임("{\"type\":\"TASK\",\"dispatchId\":\"uuid가-아님\"}"));

        verify(agentDispatchRepository, never()).markDispatched(anyLong());
    }

    private JsonNode 프레임(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
