package com.likelion.slash.common.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 사용자 작업 유형. 메시지 프로토콜 정의 5.3 · 8.10
 *
 * <p>화면·문서·서버·Agent 가 이 목록 하나를 단일 기준으로 사용한다.
 * NLU 응답의 taskType 이 요청 시 허용한 목록에 없으면 실행하지 않는다.
 *
 * <p>{@code GET /api/v1/task-types} 응답이 이 열거형에서 생성된다.
 */
public enum TaskType {

    WEATHER_LOOKUP(
            "/weather",
            ProcessingRoute.BACKEND_SERVICE,
            Priority.P0,
            List.of("location")),

    FILE_SEARCH(
            "/file",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            List.of("query", "searchFolderId")),

    SYSTEM_STATUS(
            "/status",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            // metrics 는 생략 시 전체(CPU·MEMORY·DISK)를 조회한다.
            List.of()),

    TEXT_SUMMARY(
            "/summary",
            ProcessingRoute.LLM_SERVICE,
            Priority.P0,
            List.of("text")),

    /** 조건부 P1. 등록한 프로젝트 작업 폴더를 읽기 전용으로 분석한다. */
    CODE_ANALYSIS(
            "/code",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P1,
            List.of("workspaceId")),

    /** P1. Claude·Codex SDK 의 사용량 조회. 자체 호스팅 Gemma 추론량과 무관하다. */
    AI_AGENT_USAGE(
            "/usage",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P1,
            List.of("provider"));

    public enum Priority { P0, P1 }

    private final String slashCommand;
    private final ProcessingRoute processingRoute;
    private final Priority priority;
    private final List<String> requiredParameters;

    TaskType(String slashCommand,
             ProcessingRoute processingRoute,
             Priority priority,
             List<String> requiredParameters) {
        this.slashCommand = slashCommand;
        this.processingRoute = processingRoute;
        this.priority = priority;
        this.requiredParameters = requiredParameters;
    }

    public String slashCommand() {
        return slashCommand;
    }

    public ProcessingRoute processingRoute() {
        return processingRoute;
    }

    public Priority priority() {
        return priority;
    }

    public List<String> requiredParameters() {
        return requiredParameters;
    }

    /** 실행 대상 PC 선택이 필요한 작업인지 확인한다. */
    public boolean requiresDevice() {
        return processingRoute == ProcessingRoute.LOCAL_AGENT;
    }

    /** Agent 가 지원 여부를 보고해야 하는 작업인지 확인한다. (device_capabilities) */
    public boolean isAgentCapability() {
        return requiresDevice();
    }

    /** "/weather" 같은 Slash 명령 문자열로 작업 유형을 찾는다. */
    public static Optional<TaskType> fromSlashCommand(String command) {
        return Arrays.stream(values())
                .filter(taskType -> taskType.slashCommand.equals(command))
                .findFirst();
    }

    /** P0 범위의 작업 유형만 반환한다. */
    public static List<TaskType> p0Values() {
        return Arrays.stream(values())
                .filter(taskType -> taskType.priority == Priority.P0)
                .toList();
    }
}
