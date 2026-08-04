package com.likelion.slash.common.response;

import java.time.Instant;
import java.util.UUID;

/**
 * 모든 응답에 포함되는 meta 블록. 개발문서 3.1.3
 *
 * @param requestId  이 응답을 만든 요청의 식별자
 * @param serverTime 응답 생성 시각 (ISO 8601 UTC)
 */
public record ResponseMeta(String requestId, Instant serverTime) {

    public static ResponseMeta of(String requestId) {
        return new ResponseMeta(requestId, Instant.now());
    }

    public static ResponseMeta generated() {
        return of(UUID.randomUUID().toString());
    }
}
