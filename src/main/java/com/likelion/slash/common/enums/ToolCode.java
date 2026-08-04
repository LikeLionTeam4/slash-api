package com.likelion.slash.common.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * P0 도구 목록. 개발문서 3.3.5 / IN-02
 *
 * <p>화면·문서·서버가 이 목록 하나를 단일 기준으로 사용한다.
 * NLU 응답의 toolCode 가 여기에 없으면 실행하지 않는다. (문서 3.7.1)
 */
public enum ToolCode {

    WEATHER("/weather", ExecutionTarget.CLOUD_SYNC, List.of("location")),

    FILE_SEARCH("/file", ExecutionTarget.LOCAL_AGENT, List.of("query", "rootId")),

    SYSTEM_STATUS("/command", ExecutionTarget.LOCAL_AGENT, List.of()),

    TEXT_SUMMARY("/summary", ExecutionTarget.AI_WORKER, List.of("text")),

    /** P1. Claude 읽기 전용 코드 분석 */
    MODEL_ANALYZE("/model", ExecutionTarget.LOCAL_AGENT, List.of("rootId"));

    private final String slashCommand;
    private final ExecutionTarget executionTarget;
    private final List<String> requiredArguments;

    ToolCode(String slashCommand, ExecutionTarget executionTarget, List<String> requiredArguments) {
        this.slashCommand = slashCommand;
        this.executionTarget = executionTarget;
        this.requiredArguments = requiredArguments;
    }

    public String slashCommand() {
        return slashCommand;
    }

    public ExecutionTarget executionTarget() {
        return executionTarget;
    }

    public List<String> requiredArguments() {
        return requiredArguments;
    }

    /** 로컬 PC 선택이 필요한 도구인지 확인한다. */
    public boolean requiresDevice() {
        return executionTarget == ExecutionTarget.LOCAL_AGENT;
    }

    /** "/weather" 같은 Slash 명령 문자열로 도구를 찾는다. */
    public static Optional<ToolCode> fromSlashCommand(String command) {
        return Arrays.stream(values())
                .filter(tool -> tool.slashCommand.equals(command))
                .findFirst();
    }
}
