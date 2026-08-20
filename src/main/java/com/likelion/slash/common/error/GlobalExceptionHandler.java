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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * 경로·질의 값의 <b>형식</b>이 맞지 않을 때. ({@code /tasks/{taskId}} 에 UUID 가 아닌 값,
     * {@code ?limit=abc} 처럼 숫자가 아닌 값)
     *
     * <p>이것을 따로 잡지 않으면 아래 {@code Exception} 처리로 떨어져 <b>500</b> 이 나간다.
     * 잘못 보낸 쪽은 프론트인데 서버 장애처럼 보이고, 스택트레이스가 쌓여 진짜 장애를 가린다.
     *
     * <p>어느 값이 문제인지 함께 알려 준다. 질의 조건이 여럿일 때 이름이 없으면 찾기 어렵다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        log.info("요청 값 형식 오류: name={}, value={}", e.getName(), e.getValue());

        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage(),
                        Map.of(e.getName(), "형식이 올바르지 않습니다.")));
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
