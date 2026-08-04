package com.likelion.slash.common.response;

/**
 * 성공 응답 공통 형식. 개발문서 3.1.3
 *
 * <pre>
 * {
 *   "data": { ... },
 *   "meta": { "requestId": "...", "serverTime": "2026-07-31T15:00:00Z" }
 * }
 * </pre>
 */
public record ApiResponse<T>(T data, ResponseMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, ResponseMeta.generated());
    }

    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, ResponseMeta.of(requestId));
    }
}
