package com.likelion.slash.task.dto;

import com.likelion.slash.common.enums.TaskType;
import java.util.Arrays;
import java.util.List;

/**
 * 작업 유형 목록 응답. (slash-api #9)
 *
 * <p>P1 을 포함한 전체를 내려주고, 거를 기준은 {@code priority} 로 남긴다.
 * 서비스마다 지원 범위가 달라서 서버가 미리 잘라내면 오히려 비교가 어려워진다.
 *
 * @param taskTypes 선언 순서를 그대로 유지한 작업 유형 목록
 */
public record TaskTypeCatalogResponse(List<TaskTypeResponse> taskTypes) {

    public static TaskTypeCatalogResponse of() {
        return new TaskTypeCatalogResponse(
                Arrays.stream(TaskType.values())
                        .map(TaskTypeResponse::from)
                        .toList());
    }
}
