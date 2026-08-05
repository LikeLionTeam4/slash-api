package com.likelion.slash.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 권한이 없는 요청의 응답을 공통 오류 형식으로 맞춘다.
 *
 * <p>사용자 자원의 소유권 위반은 식별자 추측을 막기 위해 Service 계층에서
 * {@link ErrorCode#RESOURCE_NOT_FOUND} 로 처리한다.
 * 이 처리기는 인증은 됐지만 접근 권한이 없는 경우를 담당한다.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        ErrorCode code = ErrorCode.FORBIDDEN;

        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of(code, code.defaultMessage()));
    }
}
