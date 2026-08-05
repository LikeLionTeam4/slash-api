package com.likelion.slash.auth;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cognito {@code /oauth2/userInfo} 호출.
 *
 * <p><b>왜 필요한가</b> — Cognito <b>Access Token 에는 이메일이 없다.</b>
 * 이메일은 ID Token 에만 들어 있는데, ID Token 을 API 인증에 쓰는 것은 용도가 다르다.
 * 그래서 사용자를 처음 만들 때 한 번만 이 Endpoint 로 이메일을 받아 온다.
 *
 * <p>이미 만들어진 사용자는 호출하지 않으므로 요청마다 발생하는 비용이 아니다.
 *
 * <p>User Pool 의 스코프에 {@code email} 이 없으면 응답에 이메일이 빠진다.
 * 그 경우 최초 로그인이 실패하므로 Cognito 설정에서 반드시 포함해야 한다.
 */
@Component
public class CognitoUserInfoClient {

    private static final Logger log = LoggerFactory.getLogger(CognitoUserInfoClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> USER_INFO =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String userInfoUri;

    public CognitoUserInfoClient(RestClient.Builder restClientBuilder,
                                 @Value("${slash.auth.cognito.user-info-uri:}") String userInfoUri) {
        this.restClient = restClientBuilder.build();
        this.userInfoUri = userInfoUri;
    }

    /**
     * 사용자의 Access Token 을 그대로 써서 프로필을 받아 온다.
     *
     * <p>주소가 설정되지 않았거나 호출에 실패하면 비어 있는 결과를 돌려준다.
     * 이 호출의 실패가 곧 인증 실패는 아니므로 예외를 밖으로 던지지 않는다.
     * 최종 판단은 {@link AuthenticatedUserService} 가 한다.
     */
    public Optional<CognitoUserProfile> fetch(String accessToken) {
        if (!StringUtils.hasText(userInfoUri)) {
            return Optional.empty();
        }

        try {
            Map<String, Object> body = restClient.get()
                    .uri(userInfoUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(USER_INFO);

            if (body == null) {
                return Optional.empty();
            }

            String email = asText(body.get("email"));
            if (!StringUtils.hasText(email)) {
                log.warn("Cognito userInfo 응답에 email 이 없습니다. User Pool 스코프에 email 이 포함됐는지 확인하세요.");
                return Optional.empty();
            }

            return Optional.of(new CognitoUserProfile(email, asText(body.get("name"))));

        } catch (RestClientException e) {
            // Token 이 로그에 남지 않도록 메시지만 남긴다. (문서 OB-02)
            log.warn("Cognito userInfo 호출 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
