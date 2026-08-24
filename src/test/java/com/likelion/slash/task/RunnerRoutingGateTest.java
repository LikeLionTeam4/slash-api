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
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행기 경로 잠금. (slash-docs#3 보안 게이트 · {@link RunnerRoutingPolicy})
 *
 * <p><b>막았을 때의 결과가 유형마다 다르다는 것이 이 시험의 요점이다.</b> 서버에도 경로가
 * 있는 유형은 조용히 서버가 처리하고, PC 밖에 갈 곳이 없는 유형은 기능이 멈춘다. 그 차이가
 * {@code CODE_ANALYSIS} 를 기본값에 넣지 않은 근거다.
 *
 * <p>게이트를 연 상태의 라우팅 규칙은 {@link SummaryRunnerRoutingTest} 가 본다.
 */
@SpringBootTest
@TestPropertySource(properties = "slash.runner.blocked-task-types=TEXT_SUMMARY,CODE_ANALYSIS")
@Transactional
class RunnerRoutingGateTest {

    @Autowired private TaskService taskService;
    @Autowired private DeviceCapabilityRepository deviceCapabilityRepository;
    @Autowired private DSLContext dsl;
    @MockitoBean private NluClient nluClient;
    @MockitoBean private TaskDispatcher taskDispatcher;

    private AuthenticatedUser 사용자;
    private long deviceId;
    private UUID 기기공개id;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(userId, UUID.randomUUID(), "t@e.com", "시험",
                "Asia/Seoul", "ACTIVE", SlashTime.now());
        this.deviceId = 준비된_기기(dsl, userId);
        this.기기공개id = dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);
    }

    private TasksRecord 요청한다(String text, TaskType 분석결과, Map<String, Object> 입력값) {
        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, 분석결과.name(),
                        입력값, List.of(), null, 1.0, "SLASH"));
        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest(text, 기기공개id), null);
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(응답.taskId())).fetchOne();
    }

    @Test
    @DisplayName("서버에도 경로가 있는 작업은 잠가도 조용히 서버가 처리한다")
    void 요약은_막혀도_서버로_간다() {
        // PC 가 요약을 지원한다고 보고했는데도 — 게이트가 우선한다.
        deviceCapabilityRepository.replaceAll(deviceId, Set.of(TaskType.TEXT_SUMMARY));

        TasksRecord 작업 = 요청한다("/summary 요약할 긴 글", TaskType.TEXT_SUMMARY,
                Map.of("text", "요약할 긴 글"));

        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
        assertThat(작업.getDeviceId()).isNull();
        // 사용자가 잃는 것이 없어야 한다 — 실패로 끝나지 않는다.
        assertThat(작업.getErrorCode()).isNotEqualTo(ErrorCode.EXECUTION_PATH_DISABLED.name());
    }

    @Test
    @DisplayName("PC 밖에 갈 곳이 없는 작업은 잠그면 기능이 멈춘다")
    void 코드분석은_막으면_실행되지_않는다() {
        TasksRecord 작업 = 요청한다("/code 이 프로젝트 구조 알려줘", TaskType.CODE_ANALYSIS,
                Map.of("query", "이 프로젝트 구조"));

        // 대체 경로가 없어 실행하지 못하고 마감된다. 이것이 CODE_ANALYSIS 를 기본
        // 차단 목록에 넣지 않은 이유다 — 넣는 순간 /code 가 멈춘다.
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_PATH_DISABLED.name());
    }
}
