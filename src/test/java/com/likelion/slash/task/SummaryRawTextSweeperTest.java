package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SummaryRawTextSweeper} 확인. (slash-docs#3 · 원문 기본 미저장)
 *
 * <p><b>배포 롤링 창을 메우는 스윕이다.</b> 신규 건은 성공 마감과 같은 트랜잭션에서 지우고
 * ({@link SummaryRawTextRetentionTest}) 과거 행은 {@code V015} 가 맞췄지만, 마이그레이션이
 * 돈 뒤에도 옛 Pod 이 잠시 트래픽을 받는다. 그 사이 접수된 건이 여기서 잡힌다.
 *
 * <p><b>정리한 건수로 단언하지 않는다.</b> 표 전체를 도는 배치라 로컬에서 앱을 띄운 뒤
 * 시험을 돌리면 남의 자료까지 세어 건수가 달라진다. (#47 · #66 과 같은 결)
 */
@SpringBootTest
@Transactional
class SummaryRawTextSweeperTest {

    private static final String 원문 = "서울의 아침 공기는 축축했고 길 건너 목련이 먼저 피었다.";

    @Autowired
    private SummaryRawTextSweeper sweeper;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private ObjectMapper objectMapper;

    /** 롤링 창에서 옛 Pod 이 만든 것과 같은 모양의 행. */
    private long 원문이_남은_요약(TaskStatus status, String 요약결과) {
        long userId = 사용자(dsl);
        JSONB parameters = JSONB.valueOf(
                "{\"text\": \"" + 원문 + "\"}");
        JSONB result = 요약결과 == null ? null
                : JSONB.valueOf("{\"summary\": \"" + 요약결과 + "\", \"engine\": \"EXTRACTIVE\"}");

        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "/요약 " + 원문)
                .set(TASKS.REQUEST_SUMMARY, 원문.substring(0, 20))
                .set(TASKS.TASK_TYPE, TaskType.TEXT_SUMMARY.name())
                // 옛 행에는 이 값이 채워져 있었다. 지금은 쓰지 않지만 그때 모양 그대로 만든다.
                .set(TASKS.PROCESSING_ROUTE, "LLM_SERVICE")
                .set(TASKS.EXECUTION_TARGET, ExecutionTarget.BACKEND.name())
                .set(TASKS.STATUS, status.name())
                .set(TASKS.PARAMETERS, parameters)
                .set(TASKS.RESULT, result)
                .set(TASKS.CORRELATION_ID, UUID.randomUUID())
                .set(TASKS.COMPLETED_AT, SlashTime.now())
                .returning(TASKS.ID)
                .fetchOne()
                .getId();
    }

    private TasksRecord 조회(long id) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).fetchOne();
    }

    @Test
    @DisplayName("성공했는데 원문이 남은 요약을 치운다")
    void 남은_원문을_치운다() throws Exception {
        long id = 원문이_남은_요약(TaskStatus.SUCCEEDED, "고른 문장이다.");

        sweeper.sweep();

        TasksRecord 작업 = 조회(id);
        assertThat(작업.getInputText()).doesNotContain("목련");
        assertThat(작업.getInputText()).contains("요약 후 저장하지 않음");
        assertThat(작업.getParameters().data()).doesNotContain("목련");

        JsonNode parameters = objectMapper.readTree(작업.getParameters().data());
        assertThat(parameters.has("text")).isFalse();
        assertThat(parameters.path("inputLength").asInt()).isEqualTo(원문.length());

        // 뒤늦게 거둔 건도 화면에는 "원문이 아니다"로 보여야 한다. (#84)
        assertThat(taskService.inputTextIsOriginal(작업)).isFalse();
    }

    @Test
    @DisplayName("목록 한 줄은 요약 결과로 바꾸고 줄바꿈은 한 칸으로 줄인다")
    void 목록_요약을_결과로_바꾼다() {
        long id = 원문이_남은_요약(TaskStatus.SUCCEEDED, "첫 줄이다.\\n\\n둘째  줄이다.");

        sweeper.sweep();

        // V015 와 같은 규칙이어야 한다 — 같은 작업이 언제 정리됐는지에 따라 다르게 보이면 안 된다.
        assertThat(조회(id).getRequestSummary()).isEqualTo("첫 줄이다. 둘째 줄이다.");
    }

    @Test
    @DisplayName("성공하지 못한 요약은 건드리지 않는다")
    void 실패한_요약은_남긴다() {
        long id = 원문이_남은_요약(TaskStatus.FAILED, null);

        sweeper.sweep();

        // 사용자가 다시 누를 근거가 원문뿐이다.
        assertThat(조회(id).getInputText()).contains("목련");
        assertThat(조회(id).getParameters().data()).contains("목련");
    }

    @Test
    @DisplayName("이미 정리된 건은 다시 건드리지 않는다")
    void 정리된_건은_그대로_둔다() {
        long id = 원문이_남은_요약(TaskStatus.SUCCEEDED, "고른 문장이다.");
        sweeper.sweep();
        int 첫_정리_뒤_version = 조회(id).getVersion();

        sweeper.sweep();

        // parameters 에 text 가 없으면 조건에 걸리지 않는다. 돌 때마다 version 이 오르면
        // 아무것도 안 바뀌는데 낙관적 잠금만 흔드는 꼴이 된다.
        assertThat(조회(id).getVersion()).isEqualTo(첫_정리_뒤_version);
    }
}
