package com.likelion.slash.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.slash.common.error.ErrorCode;
import java.util.Map;

/**
 * 오류 응답 공통 형식. 개발문서 3.1.3
 *
 * <pre>
 * {
 *   "error": {
 *     "code": "DEVICE_NOT_READY",
 *     "message": "선택한 PC가 작업을 받을 수 없습니다.",
 *     "details": { "deviceId": "..." }
 *   },
 *   "meta": { "requestId": "...", "serverTime": "..." }
 * }
 * </pre>
 */
public record ErrorResponse(ErrorBody error, ResponseMeta meta) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(
                new ErrorBody(code.name(), message, details),
                ResponseMeta.generated());
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, null);
    }
}
