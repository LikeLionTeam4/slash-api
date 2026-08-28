package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.ws.AgentProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Agent 에게 보내는 TASK 프레임. (메시지 스펙 §5)
 *
 * <p>이 프레임을 만드는 곳을 여기 하나로 모은다. 최초 전달(W1-04)과 스윕 재전송이 서로 다른
 * 모양을 보내면 Agent 는 같은 {@code dispatchId} 의 두 번째 프레임을 다른 작업으로 오해한다.
 *
 * <p>계약 원본은 slash-runner 의 {@code slash-python-pc-runner/src/slash_pc_runner/protocol.py} 다.
 * (과거 {@code contracts/src/agentMessages.ts} — Python 재작성으로 소멸)
 * 공통 필드가 하나라도 빠지면 Agent 의 zod 검증에서 통째로 거부된다.
 *
 * @param eventId       메시지마다 새로 만든다. 재전송이면 새 값이다.
 * @param dispatchId    전달 식별자. Agent 는 {@code taskId:dispatchId} 로 중복 수신을 걸러낸다.
 * @param correlationId 프론트에서 Agent 까지 한 요청을 추적하는 식별자
 * @param parameters    작업 입력값. NLU 가 추출하고 API 가 재검증한 값이다.
 * @param expiresAt     이 시각을 지나면 Agent 는 실행하지 않는다.
 * @param payloadSha256 본문 해시. <b>무결성 증명이 아니다</b> — 정규화 알고리즘이 확정되지 않아
 *                      참조 구현과 같은 임시 방식을 쓴다. Agent 도 형식만 본다.
 */
public record AgentTaskFrame(
        String schemaVersion,
        String type,
        UUID eventId,
        OffsetDateTime sentAt,
        UUID taskId,
        UUID dispatchId,
        UUID correlationId,
        String taskType,
        JsonNode parameters,
        OffsetDateTime expiresAt,
        String payloadSha256) {

    public static final String TYPE = AgentProtocol.TYPE_TASK;

    /**
     * @param payloadSha256 {@link AgentTaskPayloadHash} 로 계산한 값
     */
    public static AgentTaskFrame of(TasksRecord task,
                                    AgentDispatchesRecord dispatch,
                                    JsonNode parameters,
                                    String payloadSha256) {
        return new AgentTaskFrame(
                AgentProtocol.SCHEMA_VERSION,
                TYPE,
                UUID.randomUUID(),
                SlashTime.now(),
                task.getPublicId(),
                dispatch.getPublicId(),
                // 계약은 correlationId 를 필수로 요구하는데 tasks.correlation_id 는 비어 있을 수 있다.
                // 재전송해도 값이 흔들리지 않도록 작업 식별자를 대신 쓴다. (W1-04 가 채우면 그 값이 쓰인다)
                task.getCorrelationId() != null ? task.getCorrelationId() : task.getPublicId(),
                task.getTaskType(),
                parameters,
                dispatch.getExpiresAt(),
                payloadSha256);
    }
}
