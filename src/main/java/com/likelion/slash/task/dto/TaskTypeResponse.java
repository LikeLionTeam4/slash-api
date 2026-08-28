package com.likelion.slash.task.dto;

import com.likelion.slash.common.enums.TaskType;
import java.util.List;

/**
 * 작업 유형 한 건. (slash-api #9)
 *
 * <p>{@link TaskType} 하나를 그대로 옮긴 것이며 이 응답이 전 서비스의 기준 목록이다.
 * NLU·Agent·LLM 은 자신이 실제로 처리하는 값만 지원하면 되고,
 * 여기 없는 값을 먼저 반환해서는 안 된다.
 *
 * @param taskType                  작업 유형 이름. {@code tasks.task_type} 에 그대로 저장된다
 * @param slashCommand              화면에서 입력하는 슬래시 명령 (예: {@code /file})
 * @param defaultExecutionTarget    다른 근거가 없을 때의 실행 위치. 사용자가 PC 를 고르거나
 *                                  브라우저가 스스로 처리하면 실제 실행 위치는 달라진다
 * @param priority                  P0 는 이번 MVP 범위, P1 은 이후 범위
 * @param requiresDevice            실행 대상 PC 선택이 필요한지 여부
 * @param requiredParameters        실행에 필요한 입력값 전체
 * @param nluRequiredParameters     그중 NLU 가 채워야 하는 값. NLU 계약 비교의 기준이다
 * @param backendProvidedParameters 그중 서버가 채우는 값. NLU 는 반환하지 않는다
 */
public record TaskTypeResponse(
        String taskType,
        String slashCommand,
        String defaultExecutionTarget,
        String priority,
        boolean requiresDevice,
        List<String> requiredParameters,
        List<String> nluRequiredParameters,
        List<String> backendProvidedParameters) {

    public static TaskTypeResponse from(TaskType taskType) {
        return new TaskTypeResponse(
                taskType.name(),
                taskType.slashCommand(),
                taskType.defaultExecutionTarget().name(),
                taskType.priority().name(),
                taskType.requiresDevice(),
                taskType.requiredParameters(),
                taskType.nluRequiredParameters(),
                taskType.backendProvidedParameters());
    }
}
