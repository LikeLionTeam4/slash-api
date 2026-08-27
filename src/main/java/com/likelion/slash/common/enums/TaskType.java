package com.likelion.slash.common.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            ExecutionTarget.BACKEND,
            Priority.P0,
            List.of("location"),
            List.of()),

    FILE_SEARCH(
            "/file",
            ExecutionTarget.RUNNER,
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
            ExecutionTarget.RUNNER,
            Priority.P0,
            List.of("fileRef"),
            List.of()),

    SYSTEM_STATUS(
            "/status",
            ExecutionTarget.RUNNER,
            Priority.P0,
            // metrics 는 생략 시 전체(CPU·MEMORY·DISK)를 조회한다.
            List.of(),
            List.of()),

    TEXT_SUMMARY(
            "/summary",
            ExecutionTarget.BACKEND,
            Priority.P0,
            List.of("text"),
            List.of()),

    /**
     * 등록한 프로젝트 작업 폴더를 Claude Code·Codex 로 <b>읽기 전용</b> 분석한다. (P0-B)
     *
     * <p>계획 문서 §1.4 가 제품 본체로 편입한 항목이다. 패치 적용·시험 실행·임의 코드 수정은
     * 승인 정책이 완성되기 전에는 포함하지 않는다(같은 절).
     *
     * <p>{@code workspaceId} 는 Agent 가 READY 로 보고한 목록에서 서버가 고른다.
     * {@code searchFolderId} 와 같은 이유다 — 자연어에서 뽑아낼 수 없는 값이다.
     */
    CODE_ANALYSIS(
            "/code",
            ExecutionTarget.RUNNER,
            Priority.P0,
            // 무엇을 물어볼지(query)가 없으면 CLI 가 빈 질문으로 돌다가 시간만 쓴다.
            // 실행기도 query 를 검증하지 않으므로 여기서 필수로 둬야 NLU 가 되묻는다.
            List.of("query", "workspaceId"),
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
            ExecutionTarget.RUNNER,
            Priority.P0,
            List.of("provider"),
            List.of());

    public enum Priority { P0, P1 }

    /**
     * 실행기가 지원 여부를 보고할 수 있는 작업.
     *
     * <p>{@code ck_device_capabilities_task_type} 이 허용하는 값과 같아야 한다.
     * 어긋나면 Agent 의 보고를 저장하는 단계에서 제약 위반으로 실패한다.
     * ({@link com.likelion.slash.device.DeviceCapabilityRepository#replaceAll})
     */
    private static final Set<TaskType> AGENT_CAPABILITIES = EnumSet.of(
            FILE_SEARCH, FILE_OPEN, SYSTEM_STATUS, CODE_ANALYSIS, AI_AGENT_USAGE, TEXT_SUMMARY);

    private final String slashCommand;
    private final ExecutionTarget defaultExecutionTarget;
    private final Priority priority;
    private final List<String> requiredParameters;
    private final List<String> backendProvidedParameters;

    TaskType(String slashCommand,
             ExecutionTarget defaultExecutionTarget,
             Priority priority,
             List<String> requiredParameters,
             List<String> backendProvidedParameters) {
        this.slashCommand = slashCommand;
        this.defaultExecutionTarget = defaultExecutionTarget;
        this.priority = priority;
        this.requiredParameters = requiredParameters;
        this.backendProvidedParameters = backendProvidedParameters;
    }

    public String slashCommand() {
        return slashCommand;
    }

    /**
     * 다른 근거가 없을 때의 실행 위치.
     *
     * <p><b>이 값이 끝이 아니다.</b> 사용자가 PC 를 고르고 그 PC 가 능력을 보고했으면
     * {@code RUNNER} 로 가고, 브라우저가 스스로 요약해 결과만 제출하면 {@code BROWSER} 다.
     * 그 판단은 {@code TaskService.resolveExecutionTarget()} 이 한다.
     */
    public ExecutionTarget defaultExecutionTarget() {
        return defaultExecutionTarget;
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

    /** 실행 대상 PC 선택이 필요한 작업인지 확인한다. PC 말고는 실행할 곳이 없는 작업이다. */
    public boolean requiresDevice() {
        return defaultExecutionTarget == ExecutionTarget.RUNNER;
    }

    /**
     * 실행기가 지원 여부를 보고할 수 있는 작업인지 확인한다. ({@code device_capabilities})
     *
     * <p><b>{@link #requiresDevice()} 와 같지 않다.</b> 앞은 "PC 가 없으면 실행할 수 없는
     * 작업"이고 이것은 "실행기가 처리할 수 있는 작업"이다. {@code TEXT_SUMMARY} 가 둘을
     * 갈라 놓는다 — PC 없이 브라우저나 서버에서도 실행되지만, 등록한 PC 의 Claude Code·Codex
     * 로도 처리할 수 있다. (slash-docs#3)
     *
     * <p>이 목록은 {@code ck_device_capabilities_task_type} 과 같아야 한다.
     */
    public boolean isAgentCapability() {
        return AGENT_CAPABILITIES.contains(this);
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
