package com.likelion.slash.common.error;

import com.likelion.slash.common.response.ErrorResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * 요청 본문을 읽지 못했다. (깨진 JSON·목록에 없는 열거값·형식이 다른 값)
     *
     * <p><b>사용자가 고칠 수 있는 오류인데 500 으로 나가고 있었다.</b> 이 예외를 다루지
     * 않아 아래 {@code Exception} 핸들러로 떨어졌고, 프론트는 "잠시 후 다시 시도" 로
     * 안내하게 된다 — 몇 번을 다시 보내도 같은 본문이면 결과가 같다.
     *
     * <p><b>예외 메시지를 그대로 내보내지 않는다.</b> Jackson 의 메시지에는 클래스 이름과
     * 필드 경로가 그대로 들어 있어 내부 구조가 밖으로 샌다. 어떤 값이 문제인지는 서버
     * 로그가 들고 있다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        log.info("요청 본문을 읽지 못했다: {}", e.getMostSpecificCause().getMessage());

        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse spring
                && spring.getStatusCode().is4xxClientError()) {
            return handleRequestError(spring);
        }

        // 분류되지 않은 오류만 스택트레이스를 남긴다.
        log.error("처리하지 못한 오류", e);

        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage()));
    }

    /**
     * Spring MVC 가 상태까지 정해 둔 요청 오류를 그 판단대로 내보낸다. (#74)
     *
     * <p>허용하지 않는 메서드·형식이나 없는 경로는 <b>보내는 쪽 잘못</b>인데, 여기서 갈라내지
     * 않으면 위 catch-all 로 떨어져 500 이 된다. 5xx 는 "서버가 잘못했으니 다시 보내면 될
     * 수도 있다" 는 뜻이라, {@code Content-Type} 이 틀린 요청처럼 몇 번을 보내도 같은 것에
     * 쓰면 거짓말이 된다. <b>오타 URL 하나가 500 으로 집계되어</b> 5xx 알림이 오탐을 내고,
     * catch-all 이 스택트레이스까지 남기는 문제도 함께 있었다.
     *
     * <p><b>예외를 하나씩 적지 않는다.</b> Spring 이 아는 요청 오류가 전부
     * {@code org.springframework.web.ErrorResponse} 를 구현하므로, 새 종류가 생겨도 다시
     * 빠지지 않는다. 이름이 우리 {@link ErrorResponse} 와 같아 여기서만 전체 이름을 쓴다.
     *
     * <p>4xx 만 가로챈다. 5xx 를 담고 오는 것은 정말 서버 잘못이라 스택트레이스가 필요하다.
     */
    private ResponseEntity<ErrorResponse> handleRequestError(org.springframework.web.ErrorResponse e) {
        ErrorCode code = requestErrorCode(e.getStatusCode().value());
        log.info("요청을 받을 수 없다: {} → {}", e.getClass().getSimpleName(), code.name());

        return ResponseEntity
                .status(code.httpStatus())
                .body(ErrorResponse.of(code, code.defaultMessage()));
    }

    /**
     * 모르는 4xx 는 {@code VALIDATION_ERROR}(400) 로 본다.
     *
     * <p><b>Spring 이 준 상태를 그대로 흘리지 않는다.</b> 짝이 되는 오류 코드가 없으면 프론트가
     * 분기할 것이 없고, 계약 문서 §4 의 코드-상태 짝과도 어긋난다. 없는 코드를 지어내는
     * 것보다 "요청이 잘못됐다" 로 좁히는 편이 낫다.
     */
    private ErrorCode requestErrorCode(int status) {
        return switch (status) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.VALIDATION_ERROR;
        };
    }
}
