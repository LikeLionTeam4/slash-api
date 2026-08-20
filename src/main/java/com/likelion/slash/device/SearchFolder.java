package com.likelion.slash.device;

import java.util.Set;

/**
 * Agent 가 READY 프레임의 {@code searchFolders} 로 보고한 검색 폴더 한 건. (WBS W1-03)
 *
 * <p>계약 원본은 slash-agent 의 {@code file_index.py} 에 있는 {@code list_search_folders()} 다.
 * "READY.searchFolders 에 그대로 실어보낼" 목록이라고 적혀 있다.
 *
 * <pre>
 *   {"searchFolderId": "sf-…", "displayName": "문서", "indexStatus": "INDEXED"}
 * </pre>
 *
 * <p><b>실제 경로가 없는 것이 이 계약의 요점이다.</b> Agent 는 폴더의 진짜 위치를 자기만 들고
 * 있고 서버에는 식별자와 표시 이름만 보낸다. 서버는 사용자 PC 의 디렉터리 구조를 알지 못한 채
 * 받은 식별자를 작업 파라미터로 되돌려주기만 한다.
 *
 * @param searchFolderId Agent 가 발급한 식별자. <b>서버가 만들지 않는다.</b>
 * @param displayName    사용자에게 보여줄 이름. 서버가 아는 유일한 사람용 값이다
 * @param indexStatus    {@code INDEXED}·{@code INDEXING}·{@code UNAVAILABLE}
 */
public record SearchFolder(String searchFolderId, String displayName, String indexStatus) {

    /** 색인이 끝나 지금 검색할 수 있는 상태. */
    public static final String INDEXED = "INDEXED";

    /** 색인 중. 검색은 되지만 결과가 불완전하다. */
    public static final String INDEXING = "INDEXING";

    /** 폴더가 사라졌거나 읽을 수 없다. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** {@code ck_device_search_folders_index_status} 와 같은 목록이다. */
    private static final Set<String> INDEX_STATUSES = Set.of(INDEXED, INDEXING, UNAVAILABLE);

    /** {@code device_search_folders.search_folder_id} 열 길이. */
    private static final int ID_MAX_LENGTH = 100;

    /** {@code device_search_folders.display_name} 열 길이. */
    private static final int DISPLAY_NAME_MAX_LENGTH = 200;

    /**
     * 저장할 수 있는 보고인지 확인한다.
     *
     * <p>Agent 가 계약에 없는 값을 보내도 READY 전체가 실패하지 않도록 여기서 먼저 거른다.
     * {@code device_capabilities} 가 모르는 작업 유형을 버리는 것과 같은 이유다.
     *
     * <p><b>여기서 걸러내지 못하면 연결이 끊긴다.</b> 저장이 제약을 어기면 예외가
     * {@code handleTextMessage} 밖으로 나가 소켓이 닫히는데, Agent 는 재접속해서 <b>같은 READY 를
     * 다시 보낸다.</b> 폴더 하나 때문에 그 PC 가 영영 붙지 못하는 상태가 된다.
     *
     * <p>식별자 길이도 본다. Agent 는 {@code sf-} + uuid 8자를 쓰지만 그건 지금 구현이 그럴 뿐이고,
     * 열 길이를 넘는 값이 오면 저장 단계에서 터진다.
     */
    public boolean isStorable() {
        return searchFolderId != null && !searchFolderId.isBlank()
                && searchFolderId.length() <= ID_MAX_LENGTH
                && displayName != null && !displayName.isBlank()
                // Set.of(...) 는 null 을 담을 수 없어 contains(null) 이 NPE 를 던진다.
                // Agent 가 indexStatus 를 빠뜨린 보고를 보내면 그 예외가 READY 처리 밖으로 나가
                // 소켓이 닫히고, Agent 는 재접속해 같은 보고를 다시 보낸다.
                && indexStatus != null && INDEX_STATUSES.contains(indexStatus);
    }

    /**
     * 열 길이에 맞춘 표시 이름.
     *
     * <p><b>길다고 폴더를 버리지 않는다.</b> 식별자는 잘라내면 다른 폴더를 가리키게 되지만,
     * 표시 이름은 사람이 보는 값이라 짧아져도 폴더를 쓸 수 있다. 이름이 길다는 이유로
     * 검색 폴더를 통째로 못 쓰게 만드는 것이 더 나쁘다.
     *
     * <p>보조 평면 글자(이모지)는 {@code char} 두 개라 그 사이에서 자르면 짝을 잃은 서로게이트가
     * 남는다. PostgreSQL 은 이것을 올바른 UTF-8 로 보지 않아 저장 자체가 실패한다.
     * 한 칸 물러나 온전한 글자에서 끊는다.
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
