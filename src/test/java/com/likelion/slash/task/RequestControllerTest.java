package com.likelion.slash.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/v1/requests} · {@code GET /api/v1/tasks/{taskId}} 확인. (WBS W1-04)
 *
 * <p>브라우저가 작업을 접수하는 유일한 입구다. 프론트가 이 두 응답의 모양에 맞춰 화면을
 * 만들기 때문에 필드가 조용히 바뀌면 그쪽이 통째로 어긋난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestControllerTest {

    private static final String 사용자 = "Bearer request-controller-tester";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    @BeforeEach
    void setUp() {
        // 기기가 없어도 접수 자체는 되고 FAILED 로 마감된다. 여기서 보는 것은 접수 계약이다.
        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "SYSTEM_STATUS",
                        Map.of(), List.of(), null, 1.0, "SLASH"));
    }

    @Test
    @DisplayName("접수하면 202 와 함께 조회 주소를 알려준다")
    void 접수하면_조회_주소를_준다() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", 사용자)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "/status"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.taskId").exists())
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.statusUrl").exists())
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    @DisplayName("조회 주소가 실제로 조회되는 주소다")
    void 알려준_주소로_조회된다() throws Exception {
        String 응답 = mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", 사용자)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "/status"}
                                """))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(응답).path("data");

        mockMvc.perform(get(data.path("statusUrl").asText()).header("Authorization", 사용자))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(data.path("taskId").asText()))
                .andExpect(jsonPath("$.data.inputText").value("/status"));
    }

    @Test
    @DisplayName("빈 요청은 400 으로 막는다")
    void 빈_요청은_막는다() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", 사용자)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("인증 없이는 접수할 수 없다")
    void 인증_없이는_접수할_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "/status"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("없는 작업은 404 다")
    void 없는_작업은_404_다() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID()).header("Authorization", 사용자))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 키에 다른 내용을 보내면 409 다")
    void 같은_키_다른_내용은_409_다() throws Exception {
        post_요청("/status", "dup-key").andExpect(status().isAccepted());

        post_요청("/file 보고서", "dup-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("작업 상태는 계약에 있는 값이다")
    void 상태값은_계약_안의_값이다() throws Exception {
        String 응답 = post_요청("/status", null).andReturn().getResponse().getContentAsString();
        String status = objectMapper.readTree(응답).path("data").path("status").asText();

        TaskStatus.valueOf(status);
    }

    private org.springframework.test.web.servlet.ResultActions post_요청(String text, String idempotencyKey)
            throws Exception {

        var request = post("/api/v1/requests")
                .header("Authorization", 사용자)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("text", text)));

        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }
}
