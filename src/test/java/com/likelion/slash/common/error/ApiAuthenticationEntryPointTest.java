package com.likelion.slash.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;

/**
 * {@link ApiAuthenticationEntryPoint} 확인.
 *
 * <p>여기서 지키는 것은 하나다 — <b>거부한 이유가 응답에 남는가.</b>
 *
 * <p>#56 에서 토큰을 안 보낸 요청과 검증에 실패한 요청의 응답이 <b>바이트까지 같아서</b>
 * 원인을 밖에서 가릴 수 없었다. 실제 원인은 서버가 Cognito 공개키를 가져오지 못한 것이었는데,
 * 그 사실이 응답에도 로그에도 남지 않아 조사가 하루를 잡아먹었다.
 */
class ApiAuthenticationEntryPointTest {

    // Spring Boot 가 실제로 쓰는 것과 같이 맞춘다. 생 ObjectMapper 는 OffsetDateTime 을
    // 직렬화하지 못해, 오류 응답을 쓰는 이 지점이 시험에서만 터진다.
    private final ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(
            JsonMapper.builder().addModule(new JavaTimeModule()).build());

    private String 거부한다(String authorizationHeader, AuthenticationException exception) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        if (authorizationHeader != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_REQUIRED");

        return response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("토큰을 보내지 않았으면 이유를 붙이지 않는다")
    void 토큰이_없으면_안내만_한다() throws Exception {
        // 실패가 아니라 "이 자원은 Bearer 토큰이 필요하다" 는 안내다. (RFC 6750 §3)
        String 헤더 = 거부한다(null, new AuthenticationServiceException("no token"));

        assertThat(헤더).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("토큰이 잘못됐으면 이유가 헤더에 남는다")
    void 토큰이_잘못되면_이유를_붙인다() throws Exception {
        // Spring 이 실제로 만드는 오류다. BearerTokenErrors 는 설명을 "Invalid token" 으로
        // 고정하므로 상세한 이유는 서버 로그에만 남는다.
        String 헤더 = 거부한다("Bearer eyJhbGciOiJub25lIn0.e30.",
                new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("signature mismatch")));

        assertThat(헤더).startsWith("Bearer ");
        assertThat(헤더).contains("error=\"invalid_token\"");
        assertThat(헤더).contains("error_description=");
    }

    @Test
    @DisplayName("검증기가 이유를 남기지 못해도 토큰을 안 보낸 것과 구분된다")
    void 서비스_오류도_구분된다() throws Exception {
        // #56 에서 실제로 난 상황이다. Cognito 공개키를 가져오지 못하면 Spring 은 그것을
        // 토큰의 잘못이 아니라 서비스 오류로 보아 OAuth2AuthenticationException 이 아닌
        // 예외를 던진다. 이유를 붙이지 않으면 헤더가 "Bearer" 하나가 되어 토큰을 안 보낸
        // 요청과 똑같아진다 — 그래서 밖에서는 원인을 가릴 수 없었다.
        String 헤더 = 거부한다("Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2ln",
                new AuthenticationServiceException(
                        "An error occurred while attempting to decode the Jwt: "
                                + "Couldn't retrieve remote JWK set"));

        assertThat(헤더).isNotEqualTo("Bearer");
        assertThat(헤더).contains("error=");

        // 예외 메시지를 그대로 내보내지 않는다. 내부 구현이 밖으로 샌다.
        assertThat(헤더).doesNotContain("JWK");
    }

    @Test
    @DisplayName("헤더 값 안의 큰따옴표를 이스케이프한다")
    void 따옴표를_이스케이프한다() throws Exception {
        // 이스케이프하지 않으면 헤더가 그 자리에서 잘려 뒤의 값이 사라진다.
        String 헤더 = 거부한다("Bearer x", new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token", "a \" quote", null)));

        assertThat(헤더).contains("\\\"");
    }
}
