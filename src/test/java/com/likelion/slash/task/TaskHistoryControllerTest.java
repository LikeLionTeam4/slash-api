package com.likelion.slash.task;

import static com.likelion.slash.support.TestFixtures.분석된_작업;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.device.DeviceRepository;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/tasks} 확인. (P0-B 작업 이력)
 *
 * <p>이력 화면이 이 응답 하나로 목록·필터·다음 쪽을 모두 그린다. 필드가 조용히 바뀌면
 * 그쪽이 통째로 어긋나므로 계약을 여기서 고정한다.
 *
 * <p>시험마다 다른 {@code sub} 를 쓴다. 개발용으로 붙여 둔 실제 자료와 섞이지 않게 하기 위해서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskHistoryControllerTest {

    private static final String SUB = "task-history-tester";
    private static final String 인증 = "Bearer " + SUB;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("이력은 최신순으로 오고 결과 본문은 담기지 않는다")
    void 이력_최신순() throws Exception {
        long userId = 사용자(dsl, SUB);
        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "오늘 서울 날씨");
        분석된_작업(dsl, userId, null, TaskStatus.QUEUED.name(), TaskType.TEXT_SUMMARY, "이 글 요약해줘");

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("이 글 요약해줘"))
                .andExpect(jsonPath("$.data.items[0].taskType").value("TEXT_SUMMARY"))
                .andExpect(jsonPath("$.data.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.data.items[1].requestSummary").value("오늘 서울 날씨"))
                // 목록에는 결과도 입력값도 싣지 않는다
                .andExpect(jsonPath("$.data.items[0].result").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].parameters").doesNotExist())
                // 마지막 쪽이라 다음 표식이 없다
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("분석에 이르지 못한 요청도 목록에 남고 원문으로 요약을 채운다")
    void 분석_전_요청도_보인다() throws Exception {
        long userId = 사용자(dsl, SUB);
        작업(dsl, userId, null, TaskStatus.CREATED.name());

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("오늘 서울 날씨 알려줘"))
                .andExpect(jsonPath("$.data.items[0].taskType").doesNotExist());
    }

    @Test
    @DisplayName("커서로 다음 쪽을 이어 받는다")
    void 커서_페이징() throws Exception {
        long userId = 사용자(dsl, SUB);
        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "첫째");
        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "둘째");
        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "셋째");

        String 응답 = mockMvc.perform(get("/api/v1/tasks")
                        .param("limit", "2")
                        .header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("셋째"))
                .andExpect(jsonPath("$.data.nextCursor").exists())
                .andReturn().getResponse().getContentAsString();

        String cursor = objectMapper.readTree(응답).at("/data/nextCursor").asText();

        mockMvc.perform(get("/api/v1/tasks")
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("첫째"))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("갈래·상태·PC 로 좁힌다")
    void 필터() throws Exception {
        long userId = 사용자(dsl, SUB);
        long 기기 = 준비된_기기(dsl, userId);
        UUID 기기_공개id = deviceRepository.findById(기기).map(DevicesRecord::getPublicId).orElseThrow();

        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "오늘 서울 날씨");
        분석된_작업(dsl, userId, 기기, TaskStatus.RUNNING.name(), TaskType.FILE_SEARCH, "회의록 찾아줘");

        mockMvc.perform(get("/api/v1/tasks")
                        .param("taskType", "FILE_SEARCH")
                        .header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("회의록 찾아줘"))
                .andExpect(jsonPath("$.data.items[0].deviceId").value(기기_공개id.toString()));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "SUCCEEDED")
                        .header("Authorization", 인증))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("오늘 서울 날씨"));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("deviceId", 기기_공개id.toString())
                        .header("Authorization", 인증))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].taskType").value("FILE_SEARCH"));
    }

    @Test
    @DisplayName("남의 작업은 오지 않는다")
    void 남의_이력은_보이지_않는다() throws Exception {
        long 나 = 사용자(dsl, SUB);
        long 남 = 사용자(dsl);
        분석된_작업(dsl, 남, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "남의 요청");
        분석된_작업(dsl, 나, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "내 요청");

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", 인증))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestSummary").value("내 요청"));
    }

    @Test
    @DisplayName("남의 PC 로 좁히면 그 PC 가 있는지 알려 주지 않고 빈 목록으로 답한다")
    void 남의_기기로_좁히면_빈_목록() throws Exception {
        long 나 = 사용자(dsl, SUB);
        long 남 = 사용자(dsl);
        long 남의_기기 = 준비된_기기(dsl, 남);
        UUID 남의_기기_공개id = deviceRepository.findById(남의_기기).map(DevicesRecord::getPublicId).orElseThrow();
        분석된_작업(dsl, 나, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "내 요청");

        mockMvc.perform(get("/api/v1/tasks")
                        .param("deviceId", 남의_기기_공개id.toString())
                        .header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("모르는 갈래·상태 이름과 범위를 벗어난 개수는 400 이다")
    void 잘못된_조건은_거절한다() throws Exception {
        사용자(dsl, SUB);

        mockMvc.perform(get("/api/v1/tasks").param("taskType", "없는유형").header("Authorization", 인증))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/tasks").param("status", "없는상태").header("Authorization", 인증))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/tasks").param("limit", "0").header("Authorization", 인증))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/tasks").param("limit", "101").header("Authorization", 인증))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/tasks").param("cursor", "망가진표식").header("Authorization", 인증))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("형식이 틀린 값은 500 이 아니라 400 이다")
    void 형식이_틀린_값() throws Exception {
        사용자(dsl, SUB);

        // 잘못 보낸 쪽은 프론트인데 500 이 나가면 서버 장애로 보이고, 스택트레이스가 쌓여
        // 진짜 장애를 가린다.
        mockMvc.perform(get("/api/v1/tasks").param("deviceId", "abc").header("Authorization", 인증))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.deviceId").exists());

        mockMvc.perform(get("/api/v1/tasks").param("limit", "abc").header("Authorization", 인증))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비워서 보낸 조건은 조건이 없는 것으로 본다")
    void 빈_조건() throws Exception {
        long userId = 사용자(dsl, SUB);
        분석된_작업(dsl, userId, null, TaskStatus.SUCCEEDED.name(), TaskType.WEATHER_LOOKUP, "오늘 서울 날씨");

        // 화면이 필터를 걸지 않은 채 질의 문자열을 만들면 이런 요청이 흔히 나간다.
        mockMvc.perform(get("/api/v1/tasks")
                        .param("taskType", "")
                        .param("status", "")
                        .param("deviceId", "")
                        .param("cursor", "")
                        .param("limit", "")
                        .header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    @DisplayName("볼 수 없는 PC 를 함께 보내도 잘못된 이름은 그대로 400 이다")
    void 조건_조합에서도_검사를_건너뛰지_않는다() throws Exception {
        사용자(dsl, SUB);
        long 남 = 사용자(dsl);
        long 남의_기기 = 준비된_기기(dsl, 남);
        UUID 남의_기기_공개id = deviceRepository.findById(남의_기기).map(DevicesRecord::getPublicId).orElseThrow();

        // 기기를 먼저 보고 빈 목록으로 끝내면, 함께 온 잘못된 갈래가 조용히 넘어간다.
        // 같은 잘못을 보내도 조건 조합에 따라 400 이 되기도 하고 200 이 되기도 하면 안 된다.
        mockMvc.perform(get("/api/v1/tasks")
                        .param("deviceId", 남의_기기_공개id.toString())
                        .param("taskType", "없는유형")
                        .header("Authorization", 인증))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("deviceId", 남의_기기_공개id.toString())
                        .param("cursor", "망가진표식")
                        .header("Authorization", 인증))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("해제한 PC 로 실행했던 작업도 이력에 남고 기기 식별자가 함께 온다")
    void 해제한_기기의_작업도_남는다() throws Exception {
        long userId = 사용자(dsl, SUB);
        long 기기 = 준비된_기기(dsl, userId);
        UUID 기기_공개id = deviceRepository.findById(기기).map(DevicesRecord::getPublicId).orElseThrow();
        분석된_작업(dsl, userId, 기기, TaskStatus.SUCCEEDED.name(), TaskType.FILE_SEARCH, "회의록 찾아줘");

        // 실제 해제 경로를 그대로 쓴다. 상태만 바꾸면 ck_devices_revoked_at 이 막는다.
        deviceRepository.revoke(기기_공개id, userId, 0);

        // 기기 목록에서는 사라지지만
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(jsonPath("$.data.devices.length()").value(0));

        // 그 PC 로 했던 일은 이력에 그대로 남는다. 활성 기기만 훑으면 이 줄의 기기가 사라져 보인다.
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].deviceId").value(기기_공개id.toString()));
    }

    @Test
    @DisplayName("이력이 없어도 오류가 아니다")
    void 빈_이력() throws Exception {
        사용자(dsl, SUB);

        mockMvc.perform(get("/api/v1/tasks").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }
}
