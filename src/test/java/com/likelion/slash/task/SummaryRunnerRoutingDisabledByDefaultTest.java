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
 * {@code slash.text-summary.runner-enabled} 의 기본값(꺼짐)이 실제로 지켜지는지.
 * (slash-docs#3, 2026-08-24 보안 검토)
 *
 * <p>PC 쪽 요약 어댑터(Claude Code/Codex CLI 실행)가 지금 도구 화이트리스트만으로 방어하고
 * 있어, 프롬프트 인젝션으로 화이트리스트 밖 파일을 읽을 수 있는 경로가 남아 있다
 * (slash-runner#44 조사에서 발견). OS 수준 격리로 이중화되기 전까지는, PC 가 요약 능력을
 * 보고했고 사용자가 그 PC 를 골랐어도 {@code RUNNER} 로 가면 안 된다 — {@link
 * SummaryRunnerRoutingTest} 와 반대로 이 클래스는 설정을 전혀 오버라이드하지 않는다
 * (운영이 실제로 쓰는 기본값 그대로).
 */
@SpringBootTest
@Transactional
class SummaryRunnerRoutingDisabledByDefaultTest {

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

    @Test
    @DisplayName("설정을 켜지 않으면 PC 를 고르고 능력이 있어도 서버로 간다")
    void 기본값에서는_기기가_지원해도_BACKEND_로_남는다() {
        deviceCapabilityRepository.replaceAll(deviceId, Set.of(TaskType.TEXT_SUMMARY));

        CreateRequestResponse 응답 =
                taskService.accept(사용자, new CreateRequestRequest("/summary 요약할 긴 글", 기기공개id), null);

        TasksRecord 작업 = dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(응답.taskId())).fetchOne();
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
        assertThat(작업.getDeviceId()).isNull();
    }
}
