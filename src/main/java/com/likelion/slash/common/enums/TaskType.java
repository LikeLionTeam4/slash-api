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
            List.of("location"),
            List.of()),

    FILE_SEARCH(
            "/file",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            List.of("query", "searchFolderId"),
            // searchFolderId 는 Agent 가 READY 로 보고한 searchFolders 중에서 서버가 고른다.
            // NLU 는 이 값을 반환하지도, 누락값으로 보고하지도 않는다.
            // (slash-nlu docs/BACKEND_CONTRACT.md · 소유권)
            List.of("searchFolderId")),

    /**
     * 검색 결과의 파일을 Finder·탐색기에서 열어 위치를 보여 준다. (P0-B)
     *
     * <p><b>파일을 실행하지 않는다.</b> 실행기는 그 파일이 있는 자리를 띄우기만 한다.
     *
     * <p>{@code fileRef} 는 {@code FILE_SEARCH} 결과가 준 값을 그대로 돌려보내는 것이다.
     * 절대 경로는 클라우드로 오지 않으며, 그 값으로 실제 경로를 되찾는 일은 PC 가 한다.
     * 서버는 무엇을 가리키는지 알지 못한 채 옮겨 주기만 한다.
     */
    FILE_OPEN(
            "/open",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            List.of("fileRef"),
            List.of()),

    SYSTEM_STATUS(
            "/status",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            // metrics 는 생략 시 전체(CPU·MEMORY·DISK)를 조회한다.
            List.of(),
            List.of()),

    TEXT_SUMMARY(
            "/summary",
            ProcessingRoute.LLM_SERVICE,
            Priority.P0,
            List.of("text"),
            List.of()),

    /** 조건부 P1. 등록한 프로젝트 작업 폴더를 읽기 전용으로 분석한다. */
    CODE_ANALYSIS(
            "/code",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P1,
            List.of("workspaceId"),
            // 작업 폴더도 사용자가 미리 등록한 목록에서 서버가 고른다. searchFolderId 와 같은 이유다.
            List.of("workspaceId")),

    /**
     * Claude·Codex CLI 의 로컬 세션 로그에서 토큰 사용량을 읽는다. 자체 호스팅 Gemma 추론량과
     * 무관하다.
     *
     * <p>계획 문서 §1.4 가 P0-B(제품 본체)로 편입한 항목이라 P0 로 둔다.
     *
     * <p>{@code provider} 는 {@link AiAgentProvider} 의 값이어야 한다. PC 실행기가 그 목록
     * 밖의 값을 거부하므로 서버가 먼저 확인한다.
     */
    AI_AGENT_USAGE(
            "/usage",
            ProcessingRoute.LOCAL_AGENT,
            Priority.P0,
            List.of("provider"),
            List.of());

    public enum Priority { P0, P1 }

    private final String slashCommand;
    private final ProcessingRoute processingRoute;
    private final Priority priority;
    private final List<String> requiredParameters;
    private final List<String> backendProvidedParameters;

    TaskType(String slashCommand,
             ProcessingRoute processingRoute,
             Priority priority,
             List<String> requiredParameters,
             List<String> backendProvidedParameters) {
        this.slashCommand = slashCommand;
        this.processingRoute = processingRoute;
        this.priority = priority;
        this.requiredParameters = requiredParameters;
        this.backendProvidedParameters = backendProvidedParameters;
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

    /** 작업을 실행하는 데 필요한 입력값 전체. 누가 채우는지는 구분하지 않는다. */
    public List<String> requiredParameters() {
        return requiredParameters;
    }

    /**
     * 필수 입력값 중 서버가 채우는 것.
     *
     * <p>사용자가 미리 등록해 둔 목록(검색 폴더·작업 폴더)에서 고르는 값이라
     * 자연어에서 뽑아낼 수 없다. NLU 는 이 값을 반환하지 않는다.
     */
    public List<String> backendProvidedParameters() {
        return backendProvidedParameters;
    }

    /**
     * 필수 입력값 중 NLU 가 채워야 하는 것.
     *
     * <p>NLU 응답을 검증할 때 기준이 되는 목록이다. 여기 없는 값이 비어 있다고 해서
     * {@code NEEDS_CLARIFICATION} 으로 되물으면 안 된다. 서버가 채울 값이기 때문이다.
     */
    public List<String> nluRequiredParameters() {
        return requiredParameters.stream()
                .filter(parameter -> !backendProvidedParameters.contains(parameter))
                .toList();
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
