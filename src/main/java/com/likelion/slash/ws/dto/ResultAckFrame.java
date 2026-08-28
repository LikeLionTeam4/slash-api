package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.AgentProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 서버 → Agent 결과 접수 확인. (메시지 스펙 §3 · WBS W1-04)
 *
 * <p><b>Agent 는 이것을 받아야 결과 캐시를 지운다.</b> 보내지 않으면 재연결할 때마다 같은
 * 결과를 다시 보낸다. 중복 반영은 {@code AgentWebSocketHandler} 가 막지만, 그건 데이터가
 * 안전하다는 뜻일 뿐 오가는 프레임이 계속 쌓이는 것은 그대로다.
 *
 * <p>계약 원본은 slash-runner 의 {@code slash-python-pc-runner/src/slash_pc_runner/protocol.py}
 * (과거 {@code contracts/src/agentMessages.ts} — Python 재작성으로 소멸)
 * ({@code resultAckMessageSchema}) 이고, 참조 구현은 {@code mock-api/src/taskOrchestrator.ts}
 * 의 {@code onAgentResult} 다. 세 필드({@code taskId}·{@code dispatchId}·{@code correlationId})는
 * 계약의 {@code taskFields} 로 묶여 있어 하나라도 빠지면 Agent 의 zod 검증에서 프레임 전체가
 * 거부된다.
 *
 * @param persisted  결과를 실제로 저장했는지. 이미 마감된 작업이라 반영하지 못했으면 거짓이다.
 * @param taskStatus 반영 뒤의 작업 상태. Agent 가 로그와 재시도 판단에 쓴다.
 */
public record ResultAckFrame(
        String schemaVersion,
        String type,
        UUID eventId,
        OffsetDateTime sentAt,
        UUID taskId,
        UUID dispatchId,
        UUID correlationId,
        boolean persisted,
        String taskStatus) {

    public static ResultAckFrame of(
            UUID taskId, UUID dispatchId, UUID correlationId, boolean persisted, TaskStatus taskStatus) {

        return new ResultAckFrame(
                AgentProtocol.SCHEMA_VERSION,
                AgentProtocol.TYPE_RESULT_ACK,
                UUID.randomUUID(),
                SlashTime.now(),
                taskId,
                dispatchId,
                correlationId,
                persisted,
                taskStatus.name());
    }
}
