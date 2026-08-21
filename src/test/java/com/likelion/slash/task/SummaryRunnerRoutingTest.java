package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code TEXT_SUMMARY} 를 PC 로 보내는 경로. (slash-docs#3 권장 순서 7번)
 *
 * <p>기본은 그대로 {@code BACKEND} 다 — PC 를 명시적으로 선택하고, 그 PC 가 실행기 능력으로
 * {@code TEXT_SUMMARY} 를 보고했을 때만 {@code RUNNER} 로 간다. {@link ExtractiveSummaryRoutingTest}
 * 가 보는 기본 경로와 겹치지 않게, 여기서는 PC 선택이 실제로 갈래를 바꾸는지만 본다.
 */
@SpringBootTest
@Transactional
class SummaryRunnerRoutingTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private DeviceCapabilityRepository deviceCapabilityRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    private AuthenticatedUser 사용자;
    private long deviceId;
    private UUID 기기공개id;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());

        this.deviceId = 준비된_기기(dsl, userId);
        this.기기공개id = dsl.select(DEVICES.PUBLIC_ID)
                .from(DEVICES)
                .where(DEVICES.ID.eq(deviceId))
                .fetchOne(DEVICES.PUBLIC_ID);

        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "TEXT_SUMMARY",
                        Map.of("text", "요약할 긴 글"), List.of(), null, 1.0, "SLASH"));
    }

    private CreateRequestResponse 요약을_요청한다(UUID selectedDeviceId) {
        return taskService.accept(사용자, new CreateRequestRequest("/summary 요약할 긴 글", selectedDeviceId), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    @Test
    @DisplayName("PC 를 선택하고 그 PC 가 요약 능력을 보고했으면 PC 로 보낸다")
    void 기기가_지원하면_RUNNER_로_간다() {
        deviceCapabilityRepository.replaceAll(deviceId, Set.of(TaskType.TEXT_SUMMARY));

        CreateRequestResponse 응답 = 요약을_요청한다(기기공개id);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.RUNNER.name());
        assertThat(작업.getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("PC 를 선택했어도 요약 능력을 보고한 적 없으면 조용히 서버로 간다")
    void 기기가_미지원이면_BACKEND_로_남는다() {
        // 능력 보고 자체가 없다 — 오래된 실행기 버전이거나 로컬 CLI 가 없는 경우.
        CreateRequestResponse 응답 = 요약을_요청한다(기기공개id);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
        assertThat(작업.getDeviceId()).isNull();
    }

    @Test
    @DisplayName("PC 를 고르지 않으면 능력이 있어도 서버로 간다")
    void 기기를_안_고르면_BACKEND_로_남는다() {
        deviceCapabilityRepository.replaceAll(deviceId, Set.of(TaskType.TEXT_SUMMARY));

        CreateRequestResponse 응답 = 요약을_요청한다(null);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
        assertThat(작업.getDeviceId()).isNull();
    }
}
