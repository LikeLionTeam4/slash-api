package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Agent 에게 보내는 TASK 프레임.
 *
 * <p>이 프레임을 만드는 곳을 여기 하나로 모은다. 최초 전달(W1-04)과 스윕 재전송이 서로 다른
 * 모양을 보내면 Agent 는 같은 {@code dispatchId} 의 두 번째 프레임을 다른 작업으로 오해한다.
 *
 * <p><b>확인 필요</b> — 필드 이름은 메시지 프로토콜 정의를 읽고 맞춘 것이 아니라 스키마에서
 * 유추한 것이다. slash-agent 와 대조해 확정해야 한다. 고칠 곳은 이 파일 하나다.
 *
 * @param dispatchId    전달 식별자. Agent 는 이 값으로 중복 수신을 걸러낸다.
 * @param taskId        작업의 외부 노출 식별자
 * @param taskType      실행할 작업 유형
 * @param parameters    작업 입력값. NLU 가 추출하고 API 가 재검증한 값이다.
 * @param expiresAt     이 시각을 지나면 Agent 는 실행하지 않는다.
 * @param correlationId 프론트에서 Agent 까지 한 요청을 추적하는 식별자
 */
public record AgentTaskFrame(
        String type,
        UUID dispatchId,
        UUID taskId,
        String taskType,
        JsonNode parameters,
        OffsetDateTime expiresAt,
        UUID correlationId) {

    public static final String TYPE = "TASK";

    public static AgentTaskFrame of(TasksRecord task, AgentDispatchesRecord dispatch, JsonNode parameters) {
        return new AgentTaskFrame(
                TYPE,
                dispatch.getPublicId(),
                task.getPublicId(),
                task.getTaskType(),
                parameters,
                dispatch.getExpiresAt(),
                task.getCorrelationId());
    }
}
