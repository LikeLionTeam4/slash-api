package com.likelion.slash.common.error;

import java.util.Map;

/**
 * 업무 규칙 위반을 나타내는 예외. {@link ErrorCode} 가 HTTP 상태와 응답 코드를 결정한다.
 *
 * <p>details 에는 화면이 해결 방법을 안내할 수 있는 값만 담는다.
 * Token·개인키·전체 파일 경로는 넣지 않는다. (문서 OB-02)
 */
public class SlashException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public SlashException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public SlashException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.defaultMessage(), details);
    }

    public SlashException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
