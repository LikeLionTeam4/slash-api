package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.UserProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 서버 → 브라우저 작업 상태 변경. (계약 {@code taskStatusChangedEventSchema})
 *
 * <p>진행 표시를 즉시 바꾸기 위한 알림이다. <b>이것만으로 화면 상태를 구성하지 않는다</b> —
 * 연결이 끊긴 동안의 변경은 사라지므로 최종 상태는 REST 조회가 진실이다.
 *
 * @param taskId 외부 노출용 {@code tasks.public_id}. 내부 PK 가 아니다.
 * @param from   이전 상태. <b>계약이 {@code null} 을 허용하지 않는다</b> — 비워 보내면 Agent 가
 *               아니라 브라우저의 zod 검증에서 프레임 <b>전체</b>가 조용히 버려져 화면이
 *               갱신되지 않는다. 이전 상태가 없는 최초 기록({@code recordCreated})은 애초에
 *               이 이벤트를 보내지 않는다.
 */
public record TaskStatusChangedEvent(
        String type,
        UUID taskId,
        TaskStatus from,
        TaskStatus to,
        OffsetDateTime occurredAt) {

    public static TaskStatusChangedEvent of(UUID taskId, TaskStatus from, TaskStatus to) {
        return new TaskStatusChangedEvent(
                UserProtocol.TYPE_TASK_STATUS_CHANGED, taskId, from, to, SlashTime.now());
    }
}
