package com.likelion.slash.device.dto;

import com.likelion.slash.jooq.tables.records.DeviceSearchFoldersRecord;

/**
 * PC 가 검색 대상으로 등록해 둔 폴더. (이슈 #25)
 *
 * <p>Agent 가 READY 로 보고한 값을 그대로 돌려준다. 화면은 {@code searchFolderId} 대신
 * {@code displayName} 을 보여주면 된다 — 식별자만으로는 사용자가 어느 폴더인지 알 수 없다.
 *
 * <p><b>로컬 절대 경로는 담지 않는다.</b> Agent 가 보내지도 않는다. 서버가 아는 것은
 * 불투명한 식별자와 사람이 읽을 이름뿐이다.
 */
public record SearchFolderResponse(String searchFolderId, String displayName, String indexStatus) {

    public static SearchFolderResponse from(DeviceSearchFoldersRecord folder) {
        return new SearchFolderResponse(
                folder.getSearchFolderId(),
                folder.getDisplayName(),
                folder.getIndexStatus());
    }
}
