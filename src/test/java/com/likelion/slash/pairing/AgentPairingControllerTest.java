package com.likelion.slash.pairing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agent 등록 경로를 실제 HTTP 로 확인한다. (WBS W1-02)
 *
 * <p><b>서비스 계층 시험으로는 못 잡는 것을 본다.</b> 시도 횟수를 세는 기준은 컨트롤러가
 * 요청 헤더에서 정하므로, {@link PairingServiceTest} 처럼 {@code client} 를 인자로 넘기는
 * 시험은 헤더 조작을 통과시킨다. 등록 코드가 6자리뿐이라 이 제한이 유일한 방어선이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentPairingControllerTest {

    /**
     * 이 시험이 만든 사용자를 알아보는 접두사.
     *
     * <p>로컬 임시 인증은 Bearer 문자열을 그대로 {@code users.cognito_sub} 로 쓴다.
     * ({@link com.likelion.slash.config.LocalDevAuthConfig})
     */
    private static final String USER_PREFIX = "pairctl-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DSLContext dsl;

    @Value("${slash.pairing.max-attempts}")
    private int maxAttempts;

    @BeforeEach
    void 시도_기록을_비운다() {
        // Valkey 는 트랜잭션으로 되돌아가지 않는다. 시험끼리 카운터를 물려받지 않게 지운다.
        Set<String> keys = redis.keys("pairing:attempt:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 만든 행을 직접 지운다.
     *
     * <p>{@code @Transactional} 로 되돌릴 수 없다. 이 시험은 시도 횟수 제한을 확인하려고
     * 일부러 업무 예외를 일으키는데, 그러면 시험 트랜잭션이 rollback-only 로 표시돼
     * 이어지는 요청이 전부 깨진다.
     *
     * <p>남겨 두면 다른 시험에 샌다. {@code expireOverdue} 처럼 표 전체를 훑는 동작을
     * 확인하는 시험이 이 행들까지 세기 때문이다.
     */
    @AfterEach
    void 만든_행을_지운다() {
        String pattern = USER_PREFIX + "%";
        String owner = "select id from users where cognito_sub like ?";

        dsl.execute("delete from device_pairing_requests where user_id in (" + owner + ")", pattern);
        dsl.execute("delete from devices where user_id in (" + owner + ")", pattern);
        dsl.execute("delete from users where cognito_sub like ?", pattern);
    }

    // -----------------------------------------------------------------------
    // 도우미
    // -----------------------------------------------------------------------

    /** 사용자 화면에서 등록 코드를 하나 받아 온다. 코드는 이 응답에서만 볼 수 있다. */
    private String 등록_코드를_받는다(String user) throws Exception {
        String body = mockMvc.perform(post("/api/v1/pairing-requests")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body).path("data").path("pairingCode").asText();
    }

    private String 등록_요청_본문(String pairingCode) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "pairingCode", pairingCode,
                "publicKey", "key-" + UUID.randomUUID(),
                "device", Map.of(
                        "name", "내 PC",
                        "os", "MACOS",
                        "architecture", "ARM64")));
    }

    /** 등록을 한 번 시도한다. {@code forwardedFor} 가 null 이면 헤더를 붙이지 않는다. */
    private int 등록을_시도한다(String pairingCode, String forwardedFor) throws Exception {
        var request = post("/api/v1/agent/pair")
                .contentType(MediaType.APPLICATION_JSON)
                .content(등록_요청_본문(pairingCode));

        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }

        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    // -----------------------------------------------------------------------
    // 시도 횟수 제한
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("X-Forwarded-For 를 요청마다 바꿔도 시도 횟수가 누적된다")
    void 헤더를_바꿔도_시도_횟수가_누적된다() throws Exception {
        String user = USER_PREFIX + "xff-" + UUID.randomUUID();

        // 매 요청 다른 주소를 지어내며 한도를 채운다.
        // 헤더를 그대로 믿으면 매번 새 버킷이 잡혀 카운터가 1 에서 오르지 않는다.
        for (int i = 0; i < maxAttempts; i++) {
            int status = 등록을_시도한다("000000", "10.9.9." + i);
            assertThat(status).isEqualTo(422);
        }

        // 한도를 채운 뒤에는 올바른 코드도 거절돼야 한다.
        // 이 단정이 "제한이 실제로 걸렸다"는 유일한 증거다 — 틀린 코드와 응답이 같기 때문이다.
        String validCode = 등록_코드를_받는다(user);
        int status = 등록을_시도한다(validCode, "10.9.9.250");

        assertThat(status)
                .as("한도를 넘긴 뒤에는 올바른 코드도 거절해야 한다")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("한도 안에서는 올바른 코드로 등록이 시작된다")
    void 한도_안에서는_등록이_된다() throws Exception {
        String user = USER_PREFIX + "ok-" + UUID.randomUUID();

        // 실패를 조금 섞어도 한도에 닿지 않으면 정상 등록을 막지 않는다.
        for (int i = 0; i < maxAttempts - 1; i++) {
            assertThat(등록을_시도한다("000000", null)).isEqualTo(422);
        }

        String validCode = 등록_코드를_받는다(user);

        mockMvc.perform(post("/api/v1/agent/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(등록_요청_본문(validCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deviceId").exists())
                .andExpect(jsonPath("$.data.challengeId").exists())
                .andExpect(jsonPath("$.data.nonce").exists());
    }

    @Test
    @DisplayName("등록을 마치면 그 호출자의 실패 기록이 지워진다")
    void 등록을_마치면_실패_기록이_지워진다() throws Exception {
        String user = USER_PREFIX + "reset-" + UUID.randomUUID();

        // 한도 직전까지 실패를 쌓아 둔다.
        for (int i = 0; i < maxAttempts - 1; i++) {
            assertThat(등록을_시도한다("000000", null)).isEqualTo(422);
        }

        // 기록은 pair 가 아니라 서명 검증까지 끝난 verify 에서 지워진다.
        // 소유를 증명하지 못한 단계에서 지우면, 코드만 맞히고 서명을 포기하는 것을 반복해
        // 한도를 무한히 되돌릴 수 있다.
        등록을_마친다(등록_코드를_받는다(user));

        assertThat(등록을_시도한다("000000", null))
                .as("기록이 지워졌으므로 아직 한도가 아니다")
                .isEqualTo(422);

        등록을_마친다(등록_코드를_받는다(user));
    }

    /** 코드 제출부터 서명 증명까지 한 번에 마친다. 실제 Ed25519 키쌍을 쓴다. */
    private void 등록을_마친다(String pairingCode) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        String pairBody = objectMapper.writeValueAsString(Map.of(
                "pairingCode", pairingCode,
                "publicKey", publicKey,
                "device", Map.of("name", "내 PC", "os", "MACOS", "architecture", "ARM64")));

        String paired = mockMvc.perform(post("/api/v1/agent/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(paired).path("data");
        String payload = data.path("challengeId").asText()
                + ":" + data.path("nonce").asText()
                + ":" + data.path("deviceId").asText();

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        String verifyBody = objectMapper.writeValueAsString(Map.of(
                "pairingSessionId", data.path("pairingSessionId").asText(),
                "challengeId", data.path("challengeId").asText(),
                "signature", signature));

        mockMvc.perform(post("/api/v1/agent/pair/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceToken").exists());
    }

    // -----------------------------------------------------------------------
    // 인증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Agent 등록 경로는 사용자 인증 없이 열려 있다")
    void 사용자_인증_없이_호출된다() throws Exception {
        // 401 이 아니라 422 여야 한다. Agent 는 아직 아무 자격도 갖고 있지 않다.
        assertThat(등록을_시도한다("000000", null)).isEqualTo(422);
    }

    @Test
    @DisplayName("등록 코드 형식이 틀리면 400 으로 거른다")
    void 코드_형식이_틀리면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/agent/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(등록_요청_본문("12345")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
