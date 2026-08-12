package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/ws/ticket} 확인. (계약 §7 · WBS W1-06)
 *
 * <p>Valkey 가 떠 있어야 한다. ({@code docker compose up -d})
 *
 * <p>여기서 보는 것은 두 가지다.
 * <ul>
 *   <li><b>표는 로그인한 사람만 받는다.</b> 이 입구가 열려 있으면 30초·1회용으로 자격을
 *       짧게 만든 의미가 통째로 사라진다 — 아무나 남의 알림 채널에 붙을 표를 받게 된다</li>
 *   <li><b>계약대로 응답한다.</b> 프론트는 {@code wsUrl} 을 그대로 써서 접속하도록
 *       문서에 적어 두었으므로, 이 필드가 빠지면 접속 주소를 코드에 박게 된다</li>
 * </ul>
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. 표는 DB 가 아니라 Valkey 에 들어가 롤백되지 않고,
 * 30초 뒤 저절로 사라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WsTicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserWsTicketStore ticketStore;

    @Test
    @DisplayName("로그인한 사용자에게 표를 201 로 내준다")
    void 표를_발급한다() throws Exception {
        mockMvc.perform(post("/api/v1/ws/ticket").header("Authorization", "Bearer wsalice"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticket").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(30))
                .andExpect(jsonPath("$.data.wsUrl").value("ws://localhost:8080/ws/user"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 이다")
    void 인증_없이는_받지_못한다() throws Exception {
        // 이 입구가 열려 있으면 표를 짧게 두는 것이 아무 의미가 없다.
        mockMvc.perform(post("/api/v1/ws/ticket"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("발급한 표가 그대로 접속에 쓰인다 — 부른 사람의 표다")
    void 발급한_표로_접속한다() throws Exception {
        long 사용자 = 표를_받은_사용자("wsbob");

        // 발급(REST)과 소비(WSS)가 다른 Pod 에서 일어나도 이어져야 한다.
        assertThat(사용자).isPositive();
    }

    @Test
    @DisplayName("두 사용자가 받은 표는 서로 다른 주인을 가리킨다")
    void 사용자별로_갈린다() throws Exception {
        assertThat(표를_받은_사용자("wscarol")).isNotEqualTo(표를_받은_사용자("wsdave"));
    }

    @Test
    @DisplayName("부를 때마다 새 표를 준다 — 1회용이라 재사용할 수 없다")
    void 매번_새로_발급한다() throws Exception {
        assertThat(발급받은_표("wserin")).isNotEqualTo(발급받은_표("wserin"));
    }

    // ------------------------------------------------------------------

    /** 표를 받아 실제로 소비해 본다. 돌려주는 값이 그 표의 주인이다. */
    private long 표를_받은_사용자(String token) throws Exception {
        return ticketStore.consume(발급받은_표(token))
                .orElseThrow(() -> new AssertionError("발급한 표를 쓸 수 없습니다."));
    }

    private String 발급받은_표(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/ws/ticket").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        return response.at("/data/ticket").asText();
    }
}
