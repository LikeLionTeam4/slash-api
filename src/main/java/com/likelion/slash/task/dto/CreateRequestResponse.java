package com.likelion.slash.task.dto;

import com.likelion.slash.common.enums.TaskStatus;
import java.util.UUID;

/**
 * {@code POST /api/v1/requests} 응답. (WBS W1-04)
 *
 * <p>참조 구현은 {@code status} 를 늘 {@code ANALYZING} 으로 고정해 돌려주지만, 여기서는
 * <b>접수 시점의 실제 상태</b>를 넣는다. 분석과 전달을 요청 안에서 마치기 때문에 응답이
 * 나갈 때 이미 {@code QUEUED} 이거나, PC 가 꺼져 있으면 {@code WAITING_FOR_DEVICE} 다.
 * 고정값을 돌려주면 화면이 곧바로 한 번 더 조회해야 한다.
 *
 * @param statusUrl 결과를 확인할 주소. 프론트는 이 주소를 폴링한다.
 */
public record CreateRequestResponse(UUID taskId, TaskStatus status, String statusUrl) {

    public static CreateRequestResponse of(UUID taskId, TaskStatus status) {
        return new CreateRequestResponse(taskId, status, "/api/v1/tasks/" + taskId);
    }
}
