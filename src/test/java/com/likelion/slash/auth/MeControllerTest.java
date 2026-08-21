package com.likelion.slash.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.likelion.slash.jooq.Tables.USERS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.UserStatus;
import org.jooq.DSLContext;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/me} 확인. (WBS W1-01)
 *
 * <p>시험은 {@code local} 프로필로 돌아가므로 임시 인증이 켜져 있다.
 * 덕분에 필터 → 토큰 검증 → 사용자 생성 → 응답까지 실제 HTTP 흐름 그대로 확인할 수 있다.
 * Cognito 로 바뀌어도 이 뒤의 코드는 같으므로 시험은 그대로 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DSLContext dsl;

    private void 계정_상태를_바꾼다(String cognitoSub, UserStatus status) {
        dsl.update(USERS)
                .set(USERS.STATUS, status.name())
                .where(USERS.COGNITO_SUB.eq(cognitoSub))
                .execute();
    }

    private JsonNode 로그인해서_내정보_조회(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("첫 로그인이면 사용자를 만들어 돌려준다")
    void 첫_로그인은_사용자를_만든다() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.email").value("alice@local.test"))
                .andExpect(jsonPath("$.data.displayName").value("alice"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("응답이 공통 형식을 따른다")
    void 공통_응답_형식을_따른다() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer alice"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.serverTime").exists());
    }

    @Test
    @DisplayName("내부 PK 와 Cognito sub 는 응답에 넣지 않는다")
    void 내부_식별자를_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer alice"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.cognitoSub").doesNotExist())
                .andExpect(jsonPath("$.data.providerSubject").doesNotExist());
    }

    @Test
    @DisplayName("다시 로그인해도 사용자를 새로 만들지 않는다")
    void 재로그인은_같은_사용자다() throws Exception {
        JsonNode 첫번째 = 로그인해서_내정보_조회("alice");
        JsonNode 두번째 = 로그인해서_내정보_조회("alice");

        assertThat(두번째.at("/data/userId").asText())
                .isEqualTo(첫번째.at("/data/userId").asText());
    }

    @Test
    @DisplayName("다른 사용자는 다른 식별자를 받는다")
    void 사용자별로_분리된다() throws Exception {
        JsonNode alice = 로그인해서_내정보_조회("alice");
        JsonNode bob = 로그인해서_내정보_조회("bob");

        assertThat(bob.at("/data/userId").asText())
                .isNotEqualTo(alice.at("/data/userId").asText());
        assertThat(bob.at("/data/email").asText()).isEqualTo("bob@local.test");
    }

    @Test
    @DisplayName("토큰 없이 호출하면 401 이다")
    void 토큰이_없으면_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                // 실패가 아니라 "Bearer 토큰이 필요하다" 는 안내라 이유를 붙이지 않는다. (RFC 6750 §3)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    @DisplayName("토큰을 보냈는데 거부한 것은 안 보낸 것과 구분된다")
    void 거부한_이유가_헤더에_남는다() throws Exception {
        // 이유를 남기지 않아 조사가 하루를 잡아먹은 적이 있다 (#56) — 토큰을 아예 안 보냈을
        // 때와 검증에 실패했을 때의 응답이 바이트까지 같아서 밖에서는 원인을 가릴 수 없었다.
        // 실제 원인은 서버가 Cognito 공개키를 가져오지 못한 것이었다.
        String 규칙에_맞지_않는_토큰 = "a".repeat(65);

        String 헤더 = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + 규칙에_맞지_않는_토큰))
                .andExpect(status().isUnauthorized())
                // 응답 본문은 그대로다. 프론트는 error.code 로 분기하고 있다.
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.WWW_AUTHENTICATE);

        assertThat(헤더).isNotNull();
        assertThat(헤더).startsWith("Bearer ");
        assertThat(헤더).contains("error=");

        // 토큰을 안 보냈을 때와 같은 값이면 이 시험은 아무것도 지키지 못한다.
        assertThat(헤더).isNotEqualTo("Bearer");
    }

    @Test
    @DisplayName("정지된 계정은 토큰이 살아 있어도 403 이다")
    void 정지된_계정은_거부한다() throws Exception {
        // 첫 호출로 사용자를 만든다.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer carol"))
                .andExpect(status().isOk());

        계정_상태를_바꾼다("carol", UserStatus.SUSPENDED);

        // 토큰 자체는 유효하므로 401 이 아니라 403 이다.
        // 우리 쪽에서 이용을 막을 수 있어야 하고, 프론트가 토큰 갱신으로 뚫으려 해서도 안 된다.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer carol"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    @DisplayName("탈퇴한 계정도 403 이다")
    void 탈퇴한_계정은_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer dave"))
                .andExpect(status().isOk());

        계정_상태를_바꾼다("dave", UserStatus.DELETED);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer dave"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("형식에 맞지 않는 토큰은 401 이다")
    void 잘못된_토큰은_거부한다() throws Exception {
        // 임시 인증도 아무 문자열이나 받지는 않는다. (64자 초과)
        String tooLong = "a".repeat(65);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tooLong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }
}
