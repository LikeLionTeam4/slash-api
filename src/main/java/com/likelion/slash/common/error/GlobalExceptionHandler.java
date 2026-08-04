package com.likelion.slash.common.error;

import com.likelion.slash.common.response.ErrorResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 오류를 개발문서 3.1.3 형식으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SlashException.class)
    public ResponseEntity<ErrorResponse> handleSlashException(SlashException e) {
        ErrorCode code = e.errorCode();
        // 업무 규칙 위반은 예상된 흐름이므로 스택트레이스를 남기지 않는다.
        log.info("업무 오류: code={}, message={}", code, e.getMessage());

        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage(), fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 분류되지 않은 오류만 스택트레이스를 남긴다.
        log.error("처리하지 못한 오류", e);

        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage()));
    }
}
