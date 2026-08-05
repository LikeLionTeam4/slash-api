package com.likelion.slash.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증되지 않은 요청의 응답을 공통 오류 형식으로 맞춘다.
 *
 * <p>Spring Security 필터는 Controller 앞단이라
 * {@link GlobalExceptionHandler} 가 인증 실패를 잡지 못한다.
 * 별도로 등록하지 않으면 본문 없는 401 이 나가 프론트가 오류 코드로 분기할 수 없다.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode code = ErrorCode.AUTH_REQUIRED;

        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of(code, code.defaultMessage()));
    }
}
