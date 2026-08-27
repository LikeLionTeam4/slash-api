package com.likelion.slash.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion.slash.common.enums.TaskType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/task-types} 확인. (slash-api #9)
 *
 * <p>NLU·Agent·LLM 이 자기 목록을 맞춰보는 기준 응답이므로,
 * 필드가 조용히 빠지거나 이름이 바뀌면 저쪽 계약 확인이 통째로 어긋난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("작업 유형 전체를 선언 순서대로 내려준다")
    void 작업_유형_전체를_내려준다() throws Exception {
        mockMvc.perform(get("/api/v1/task-types").header("Authorization", "Bearer contract-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskTypes.length()").value(TaskType.values().length))
                .andExpect(jsonPath("$.data.taskTypes[0].taskType").value("WEATHER_LOOKUP"))
                .andExpect(jsonPath("$.data.taskTypes[*].taskType",
                        Matchers.hasItems("FILE_SEARCH", "SYSTEM_STATUS", "TEXT_SUMMARY",
                                "CODE_ANALYSIS", "AI_AGENT_USAGE")));
    }

    @Test
    @DisplayName("작업 유형 한 건의 모든 필드가 채워진다")
    void 한_건의_필드가_모두_채워진다() throws Exception {
        String fileSearch = "$.data.taskTypes[?(@.taskType == 'FILE_SEARCH')]";

        mockMvc.perform(get("/api/v1/task-types").header("Authorization", "Bearer contract-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(fileSearch + ".slashCommand").value("/file"))
                .andExpect(jsonPath(fileSearch + ".defaultExecutionTarget").value("RUNNER"))
                .andExpect(jsonPath(fileSearch + ".priority").value("P0"))
                .andExpect(jsonPath(fileSearch + ".requiresDevice").value(true))
                .andExpect(jsonPath(fileSearch + ".requiredParameters[*]")
                        .value(Matchers.contains("query", "searchFolderId")))
                // NLU 는 query 만 채운다. searchFolderId 를 누락값으로 되물으면 안 된다.
                .andExpect(jsonPath(fileSearch + ".nluRequiredParameters[*]")
                        .value(Matchers.contains("query")))
                .andExpect(jsonPath(fileSearch + ".backendProvidedParameters[*]")
                        .value(Matchers.contains("searchFolderId")));
    }

    @Test
    @DisplayName("입력값이 없는 작업도 빈 배열로 내려준다")
    void 입력값이_없어도_빈_배열이_온다() throws Exception {
        String systemStatus = "$.data.taskTypes[?(@.taskType == 'SYSTEM_STATUS')]";

        // non_null 직렬화라 null 이면 필드가 통째로 빠진다.
        // 소비자가 옵셔널 처리를 하지 않아도 되도록 빈 목록은 빈 배열로 유지한다.
        mockMvc.perform(get("/api/v1/task-types").header("Authorization", "Bearer contract-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(systemStatus + ".requiredParameters").exists())
                .andExpect(jsonPath(systemStatus + ".requiredParameters[0]").doesNotExist());
    }

    @Test
    @DisplayName("공통 응답 형식과 한국 시각을 따른다")
    void 공통_응답_형식을_따른다() throws Exception {
        mockMvc.perform(get("/api/v1/task-types").header("Authorization", "Bearer contract-check"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.serverTime").value(Matchers.matchesPattern(".*\\+09:00$")));
    }

    @Test
    @DisplayName("인증 없이는 조회할 수 없다")
    void 인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/v1/task-types"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }
}
