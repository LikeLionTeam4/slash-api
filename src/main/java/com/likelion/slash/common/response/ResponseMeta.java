package com.likelion.slash.common.response;

import com.likelion.slash.common.SlashTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 모든 응답에 포함되는 meta 블록. 메시지 프로토콜 정의 3.3
 *
 * @param requestId  이 응답을 만든 요청의 식별자. 오류 문의와 로그 검색에 사용한다.
 * @param serverTime 서버가 응답을 만든 한국 시각 (예: 2026-08-04T10:47:00+09:00)
 */
public record ResponseMeta(String requestId, OffsetDateTime serverTime) {

    public static ResponseMeta of(String requestId) {
        return new ResponseMeta(requestId, SlashTime.now());
    }

    public static ResponseMeta generated() {
        return of(UUID.randomUUID().toString());
    }
}
