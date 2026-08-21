package com.likelion.slash.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 인증되지 않은 요청의 응답을 공통 오류 형식으로 맞춘다.
 *
 * <p>Spring Security 필터는 Controller 앞단이라
 * {@link GlobalExceptionHandler} 가 인증 실패를 잡지 못한다.
 * 별도로 등록하지 않으면 본문 없는 401 이 나가 프론트가 오류 코드로 분기할 수 없다.
 *
 * <p><b>거부한 이유를 반드시 남긴다.</b> 이것을 빠뜨렸다가 조사가 하루를 잡아먹은 적이 있다
 * (#56) — 토큰을 아예 안 보냈을 때와 서명 검증에 실패했을 때의 응답이 <b>바이트까지 같아서</b>
 * 밖에서는 원인을 가릴 방법이 없었다. 실제 원인은 NAT 가 사라져 Cognito 공개키를 가져오지
 * 못한 것이었는데, 그 사실이 응답에도 로그에도 남지 않았다.
 *
 * <p>이유는 두 곳에 남긴다.
 * <ul>
 *   <li>{@code WWW-Authenticate} 헤더 — RFC 6750 이 규정한 자리다. Spring 기본
 *       {@code BearerTokenAuthenticationEntryPoint} 가 하던 일인데, 응답 본문을 우리 형식으로
 *       바꾸면서 함께 잃었다. 브라우저 개발자 도구에서 바로 보이므로 프론트도 자기 화면에서
 *       원인을 읽을 수 있다</li>
 *   <li>서버 로그 — 헤더를 볼 수 없는 상황(배치·Agent)에서도 남는다</li>
 * </ul>
 *
 * <p><b>응답 본문은 바꾸지 않는다.</b> 프론트는 {@code error.code} 로 분기하고 있고
 * {@code AUTH_REQUIRED} 하나로 충분하다. 이유를 본문에 넣으면 계약이 바뀐다.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    private static final String BEARER = "Bearer";

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode code = ErrorCode.AUTH_REQUIRED;
        OAuth2Error error = errorOf(authException);
        boolean tokenSent = StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION));

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(error, tokenSent));
        logReason(request, error, authException, tokenSent);

        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of(code, code.defaultMessage()));
    }

    /** 토큰 검증이 남긴 오류. 토큰을 아예 보내지 않은 요청에는 없다. */
    private static OAuth2Error errorOf(AuthenticationException authException) {
        return authException instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError()
                : null;
    }

    /**
     * {@code WWW-Authenticate} 헤더 값을 만든다. (RFC 6750 §3)
     *
     * <p>토큰을 보내지 않은 요청에는 {@code Bearer} 만 내보낸다. 그것이 실패가 아니라
     * "이 자원은 Bearer 토큰이 필요하다" 는 안내이기 때문이다.
     *
     * <p><b>토큰을 보냈는데 거부한 것은 언제나 이유를 붙인다.</b> 검증기가 남긴
     * {@link OAuth2Error} 가 없을 수도 있어서다 — 서명은 물론 형식조차 보지 못하고 실패하는
     * 경우, 이를테면 <b>Cognito 공개키를 가져오지 못하면</b> Spring 은 그것을 토큰의 잘못이
     * 아니라 서비스 오류로 보아 {@code AuthenticationServiceException} 을 던진다. 그대로 두면
     * 헤더가 {@code Bearer} 하나가 되어 <b>토큰을 안 보낸 요청과 다시 구분되지 않는다</b> —
     * #56 에서 실제로 겪은 것이 이 경우다.
     *
     * <p>그때는 이유를 일반화해 적는다. 무엇이 실패했는지는 서버 로그가 들고 있고, 예외
     * 메시지를 그대로 내보내면 내부 구현이 밖으로 샌다.
     */
    private static String challenge(OAuth2Error error, boolean tokenSent) {
        if (error == null && !tokenSent) {
            return BEARER;
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        if (error == null) {
            parameters.put("error", "invalid_token");
            // 헤더 값은 ASCII 로 둔다. HTTP 헤더의 기본 문자셋이 ISO-8859-1 이라
            // 한글을 넣으면 받는 쪽에서 깨진다. 자세한 이유는 어차피 서버 로그에 있다.
            parameters.put("error_description", "Token could not be verified; see server logs");
        } else {
            putIfPresent(parameters, "error", error.getErrorCode());
            putIfPresent(parameters, "error_description", error.getDescription());
            putIfPresent(parameters, "error_uri", error.getUri());
        }

        if (parameters.isEmpty()) {
            return BEARER;
        }

        StringBuilder challenge = new StringBuilder(BEARER);
        String separator = " ";
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            challenge.append(separator)
                    .append(parameter.getKey())
                    .append("=\"")
                    .append(quoted(parameter.getValue()))
                    .append('"');
            separator = ", ";
        }
        return challenge.toString();
    }

    private static void putIfPresent(Map<String, String> parameters, String key, String value) {
        if (StringUtils.hasText(value)) {
            parameters.put(key, value);
        }
    }

    /** 헤더 값 안의 큰따옴표·역슬래시를 이스케이프한다. 없으면 헤더가 그 자리에서 잘린다. */
    private static String quoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 거부한 이유를 남긴다.
     *
     * <p>토큰을 보내지 않은 요청은 {@code DEBUG} 다. 로그인하지 않은 사용자가 화면을 여는
     * 정상적인 경우라 매번 남기면 실제 문제가 묻힌다.
     *
     * <p><b>토큰을 보냈는데 거부한 것은 {@code WARN} 이다.</b> 사용자에게는 로그인이 통째로
     * 막힌 것으로 보이는 상황이고, 그 원인이 서버 쪽(공개키를 못 가져옴·발급자 설정 오류)일 수
     * 있다. 토큰 값은 남기지 않는다 — 그대로 쓰면 남의 계정으로 요청을 보낼 수 있다.
     */
    private static void logReason(HttpServletRequest request,
                                  OAuth2Error error,
                                  AuthenticationException authException,
                                  boolean tokenSent) {

        if (!tokenSent) {
            log.debug("인증 정보가 없는 요청을 거부했다. {} {}", request.getMethod(), request.getRequestURI());
            return;
        }

        log.warn("토큰을 받았으나 거부했다. {} {} error={} reason={}",
                request.getMethod(),
                request.getRequestURI(),
                error != null ? error.getErrorCode() : "-",
                error != null && StringUtils.hasText(error.getDescription())
                        ? error.getDescription()
                        : authException.getMessage());
    }
}
