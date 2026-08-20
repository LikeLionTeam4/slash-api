package com.likelion.slash.device;

import com.likelion.slash.common.enums.AiAgentProvider;
import java.util.List;
import java.util.Set;

/**
 * Agent 가 READY 프레임의 {@code projectWorkspaces} 로 보고한 프로젝트 폴더 한 건. (P0-B)
 *
 * <p>계약 원본은 slash-runner 의 {@code agent.py} {@code _build_ready()} 와
 * {@code code_adapters.py} 의 {@code ProjectWorkspaceConfig} 다.
 *
 * <pre>
 *   {"workspaceId": "ws-…", "displayName": "slash-api",
 *    "workspaceType": "GIT_REPOSITORY", "availableCodeAdapters": ["CLAUDE_CODE"]}
 * </pre>
 *
 * <p>{@link SearchFolder} 와 같은 설계다. <b>실제 경로가 없는 것이 요점이다</b> — Agent 는
 * 폴더의 진짜 위치를 자기만 들고 있고, 서버는 식별자를 작업 파라미터로 되돌려주기만 한다.
 *
 * @param workspaceId           Agent 가 발급한 식별자. <b>서버가 만들지 않는다.</b>
 * @param displayName           사용자에게 보여줄 이름. 서버가 아는 유일한 사람용 값이다
 * @param workspaceType         {@code GIT_REPOSITORY} 또는 {@code DIRECTORY}
 * @param availableCodeAdapters 그 폴더에서 쓸 수 있는 도구. <b>비어 있을 수 있다</b> —
 *                              CLI 가 설치되지 않은 PC 다
 */
public record ProjectWorkspace(String workspaceId,
                               String displayName,
                               String workspaceType,
                               List<String> availableCodeAdapters) {

    /** 형상 관리 중인 폴더. */
    public static final String GIT_REPOSITORY = "GIT_REPOSITORY";

    /** 그 밖의 보통 폴더. */
    public static final String DIRECTORY = "DIRECTORY";

    /** {@code ck_device_project_workspaces_type} 과 같은 목록이다. */
    private static final Set<String> WORKSPACE_TYPES = Set.of(GIT_REPOSITORY, DIRECTORY);

    /** {@code device_project_workspaces.workspace_id} 열 길이. */
    private static final int ID_MAX_LENGTH = 100;

    /** {@code device_project_workspaces.display_name} 열 길이. */
    private static final int DISPLAY_NAME_MAX_LENGTH = 200;

    /**
     * 저장할 수 있는 보고인지 확인한다.
     *
     * <p>Agent 가 계약에 없는 값을 보내도 READY 전체가 실패하지 않도록 여기서 먼저 거른다.
     *
     * <p><b>여기서 걸러내지 못하면 연결이 끊긴다.</b> 저장이 제약을 어기면 예외가 소켓 밖으로
     * 나가 연결이 닫히는데, Agent 는 재접속해서 <b>같은 READY 를 다시 보낸다.</b> 폴더 하나
     * 때문에 그 PC 가 영영 붙지 못하는 상태가 된다. ({@link SearchFolder#isStorable()} 과 같은 이유)
     */
    public boolean isStorable() {
        return workspaceId != null && !workspaceId.isBlank()
                && workspaceId.length() <= ID_MAX_LENGTH
                && displayName != null && !displayName.isBlank()
                // Set.of(...) 는 null 을 담을 수 없어 contains(null) 이 NPE 를 던진다.
                // 그 예외가 READY 처리 밖으로 나가면 소켓이 닫히고, Agent 는 재접속해 같은
                // 보고를 다시 보낸다 — 막으려던 무한 재접속을 방어 코드가 만드는 셈이다.
                && workspaceType != null && WORKSPACE_TYPES.contains(workspaceType);
    }

    /**
     * 저장할 도구 목록. 계약에 없는 이름은 버린다.
     *
     * <p>{@code ck_device_project_workspaces_adapters} 가 목록 밖의 값을 거부하므로, 걸러내지
     * 않으면 폴더 하나 때문에 READY 전체가 실패한다. 모르는 도구를 버려도 나머지 도구로
     * 분석할 수 있고, 전부 버려지면 <b>쓸 수 없는 폴더</b>가 되어 서버가 고르지 않는다.
     */
    public List<String> storableAdapters() {
        if (availableCodeAdapters == null) {
            return List.of();
        }
        return availableCodeAdapters.stream()
                .filter(name -> AiAgentProvider.from(name).isPresent())
                .map(name -> AiAgentProvider.from(name).orElseThrow().name())
                .distinct()
                .toList();
    }

    /** 이 폴더로 분석할 수 있는가. 도구가 하나도 없으면 Agent 가 거절한다. */
    public boolean isUsable() {
        return !storableAdapters().isEmpty();
    }

    /**
     * 열 길이에 맞춘 표시 이름.
     *
     * <p>길다고 폴더를 버리지 않는다. 식별자를 자르면 다른 폴더를 가리키게 되지만 표시 이름은
     * 사람이 보는 값이라 짧아져도 폴더를 쓸 수 있다. 보조 평면 글자(이모지)의 짝을 깨뜨리지
     * 않도록 한 칸 물러나 끊는다. ({@link SearchFolder#displayNameForStorage()} 와 같다)
     */
    public String displayNameForStorage() {
        if (displayName.length() <= DISPLAY_NAME_MAX_LENGTH) {
            return displayName;
        }

        int cut = DISPLAY_NAME_MAX_LENGTH;
        if (Character.isHighSurrogate(displayName.charAt(cut - 1))) {
            cut--;
        }
        return displayName.substring(0, cut);
    }
}
