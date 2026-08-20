package com.likelion.slash.task;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;

/**
 * 이력 목록을 좁히는 조건. ({@code GET /api/v1/tasks})
 *
 * <p>세 가지 모두 비워 둘 수 있고, 비운 것은 조건에서 빠진다.
 *
 * @param taskType 작업 유형. 화면의 갈래(카테고리)에 해당한다
 * @param status   작업 상태
 * @param deviceId 대상 PC 의 <b>내부 PK</b>. 밖에서 받은 공개 식별자는 서비스에서 옮겨 담는다.
 *                 PC 를 거치지 않는 작업({@code /weather}·{@code /summary})은 이 조건에 걸리지 않는다
 */
public record TaskHistoryFilter(TaskType taskType, TaskStatus status, Long deviceId) {

    public static final TaskHistoryFilter NONE = new TaskHistoryFilter(null, null, null);
}
