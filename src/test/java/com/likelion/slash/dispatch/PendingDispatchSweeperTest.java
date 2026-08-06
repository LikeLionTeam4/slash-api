package com.likelion.slash.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import com.likelion.slash.ws.WsMessagePublisher;
import com.likelion.slash.ws.WsTarget;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link PendingDispatchSweeper} 확인. (WBS W1-06)
 *
 * <p>스윕은 여러 Pod 에서 동시에 돈다. 한 회차에서 한 Pod 만 발행하는 것과,
 * 보낼 수 없는 전달을 걸러 내는 것을 본다.
 */
class PendingDispatchSweeperTest {

    private static final long 기기_PK = 42L;
    private static final long 작업_PK = 7L;

    private final AgentDispatchRepository agentDispatchRepository = mock(AgentDispatchRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final WsMessagePublisher publisher = mock(WsMessagePublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private PendingDispatchSweeper sweeper;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);

        sweeper = new PendingDispatchSweeper(
                agentDispatchRepository,
                taskRepository,
                publisher,
                redis,
                new ObjectMapper(),
                Duration.ofSeconds(5),
                100);
    }

    @Test
    @DisplayName("미전달 작업을 TASK 프레임으로 다시 발행한다")
    void 재발행한다() {
        AgentDispatchesRecord dispatch = 전달();
        미전달_목록(dispatch);
        작업_있음(TaskType.FILE_SEARCH, "{\"query\":\"보고서\"}");
        잠금_획득(true);

        sweeper.resendPending();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).send(eq(WsTarget.DEVICE), eq(기기_PK), captor.capture());

        AgentTaskFrame frame = (AgentTaskFrame) captor.getValue();
        assertThat(frame.type()).isEqualTo("TASK");
        assertThat(frame.dispatchId()).isEqualTo(dispatch.getPublicId());
        assertThat(frame.taskType()).isEqualTo("FILE_SEARCH");
        assertThat(frame.parameters().path("query").asText()).isEqualTo("보고서");
        assertThat(frame.expiresAt()).isEqualTo(dispatch.getExpiresAt());

        // 계약이 요구하는 공통 필드. 하나라도 빠지면 Agent 가 메시지 전체를 거부한다.
        assertThat(frame.schemaVersion()).isEqualTo("1.0");
        assertThat(frame.eventId()).isNotNull();
        assertThat(frame.sentAt()).isNotNull();
        assertThat(frame.taskId()).isNotNull();
        assertThat(frame.correlationId()).isNotNull();
        assertThat(frame.payloadSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("correlationId 가 없는 작업도 계약을 만족한다 — 재전송해도 같은 값이다")
    void correlationId_가_없으면_작업_식별자를_쓴다() {
        미전달_목록(전달());
        작업_있음(TaskType.SYSTEM_STATUS, null, null);
        잠금_획득(true);

        sweeper.resendPending();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).send(any(), anyLong(), captor.capture());

        AgentTaskFrame frame = (AgentTaskFrame) captor.getValue();
        assertThat(frame.correlationId()).isEqualTo(frame.taskId());
    }

    @Test
    @DisplayName("잠금을 잡지 못하면 발행하지 않는다 — 다른 Pod 이 이미 보냈다")
    void 잠금을_못_잡으면_건너뛴다() {
        미전달_목록(전달());
        작업_있음(TaskType.FILE_SEARCH, "{}");
        잠금_획득(false);

        sweeper.resendPending();

        verify(publisher, never()).send(any(), anyLong(), any());
    }

    @Test
    @DisplayName("Valkey 가 끊겨 잠금을 시도조차 못 하면 발행하지 않는다")
    void 잠금_오류면_건너뛴다() {
        미전달_목록(전달());
        작업_있음(TaskType.FILE_SEARCH, "{}");
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new IllegalStateException("valkey down"));

        assertThatCode(() -> sweeper.resendPending()).doesNotThrowAnyException();
        verify(publisher, never()).send(any(), anyLong(), any());
    }

    @Test
    @DisplayName("아직 분석되지 않아 작업 유형이 없으면 발행하지 않는다")
    void 작업_유형이_없으면_건너뛴다() {
        미전달_목록(전달());
        작업_있음(null, "{}");
        잠금_획득(true);

        sweeper.resendPending();

        verify(publisher, never()).send(any(), anyLong(), any());
    }

    @Test
    @DisplayName("작업 자체가 없으면 발행하지 않는다")
    void 작업이_없으면_건너뛴다() {
        미전달_목록(전달());
        when(taskRepository.findById(anyLong())).thenReturn(Optional.empty());
        잠금_획득(true);

        sweeper.resendPending();

        verify(publisher, never()).send(any(), anyLong(), any());
    }

    @Test
    @DisplayName("입력값이 없는 작업도 발행한다 — /status 처럼 인자가 없는 작업이 있다")
    void 입력값이_없어도_발행한다() {
        미전달_목록(전달());
        작업_있음(TaskType.SYSTEM_STATUS, null);
        잠금_획득(true);

        sweeper.resendPending();

        verify(publisher).send(eq(WsTarget.DEVICE), eq(기기_PK), any());
    }

    @Test
    @DisplayName("조회가 실패해도 예외를 올리지 않는다 — 다음 회차가 다시 시도한다")
    void 실패해도_스케줄이_멈추지_않는다() {
        when(agentDispatchRepository.findPendingForResend(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> sweeper.resendPending()).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------

    private void 미전달_목록(AgentDispatchesRecord... dispatches) {
        when(agentDispatchRepository.findPendingForResend(any(), any(), anyInt()))
                .thenReturn(List.of(dispatches));
    }

    private void 잠금_획득(boolean acquired) {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(acquired);
    }

    private void 작업_있음(TaskType taskType, String parameters) {
        작업_있음(taskType, parameters, UUID.randomUUID());
    }

    private void 작업_있음(TaskType taskType, String parameters, UUID correlationId) {
        TasksRecord task = new TasksRecord();
        task.setId(작업_PK);
        task.setPublicId(UUID.randomUUID());
        task.setDeviceId(기기_PK);
        task.setTaskType(taskType == null ? null : taskType.name());
        task.setParameters(parameters == null ? null : JSONB.valueOf(parameters));
        task.setCorrelationId(correlationId);

        when(taskRepository.findById(작업_PK)).thenReturn(Optional.of(task));
    }

    private static AgentDispatchesRecord 전달() {
        AgentDispatchesRecord dispatch = new AgentDispatchesRecord();
        dispatch.setId(1L);
        dispatch.setPublicId(UUID.randomUUID());
        dispatch.setTaskId(작업_PK);
        dispatch.setDeviceId(기기_PK);
        dispatch.setExpiresAt(SlashTime.now().plusMinutes(5));
        return dispatch;
    }
}
