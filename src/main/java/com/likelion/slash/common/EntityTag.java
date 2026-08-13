package com.likelion.slash.common;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;

/**
 * {@code If-Match} 헤더를 자원의 {@code version} 으로 읽는다. (계약 문서 §1.3)
 *
 * <p>수정 요청은 "내가 본 그 상태일 때만 바꿔라" 를 함께 보낸다. 그 사이 다른 탭이나 기기에서
 * 먼저 바뀌었으면 {@code RESOURCE_VERSION_MISMATCH}(412) 로 거절해 덮어쓰기를 막는다.
 *
 * <p><b>헤더가 없으면 400 이다.</b> HTTP 는 이 경우 428 Precondition Required 를 두지만
 * 계약 문서의 오류 코드 표에 없는 값이라, 프론트가 모르는 코드를 새로 만들지 않고
 * {@code VALIDATION_ERROR} 로 알린다.
 *
 * <p>값은 {@code "3"} 처럼 따옴표로 감싸는 것이 표준이지만 {@code 3} 도 받는다. 프론트가
 * 응답 본문의 {@code version} 을 그대로 넣는 경우가 흔하고, 그것을 거절해서 얻는 것이 없다.
 */
public final class EntityTag {

    private EntityTag() {
    }

    /**
     * @param ifMatch {@code If-Match} 헤더 값. 없으면 {@code null}
     * @return 요청자가 본 자원의 version
     * @throws SlashException 헤더가 없거나 숫자로 읽을 수 없을 때 ({@code VALIDATION_ERROR})
     */
    public static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR,
                    "If-Match 헤더가 필요합니다. 조회 응답의 version 값을 넣어 주세요.", null);
        }

        // W/"3" (약한 검증자)도 값 자체는 같은 의미다. 우리는 정확한 정수 하나만 쓴다.
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR,
                    "If-Match 값을 읽지 못했습니다. 조회 응답의 version 값을 넣어 주세요.", null);
        }
    }
}
