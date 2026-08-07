package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java 열거형과 DB 허용 목록이 같은지 확인한다. (slash-api #9)
 *
 * <p>둘은 서로 다른 파일에 손으로 적혀 있어서 한쪽만 바뀌기 쉽다.
 * enum 에만 값을 추가하면 저장 시점에 {@code DataIntegrityViolationException} 이 나고,
 * {@code CHECK} 에만 추가하면 NLU 가 보낸 값을 서버가 해석하지 못한다.
 * 둘 다 배포하고 나서야 드러나는 종류라 여기서 미리 막는다.
 *
 * <p>이 시험은 로컬 PostgreSQL 만 있으면 돌아간다. 다른 저장소나 기동 중인 서비스가 필요 없으므로
 * 기존 {@code ./gradlew test} 에 그대로 포함한다. 서비스 간 비교(NLU·Agent·LLM)는
 * 각 저장소의 별도 contract-check 가 맡는다.
 */
@SpringBootTest
@Transactional
class TaskTypeSchemaContractTest {

    /** CHECK 정의에서 작은따옴표로 묶인 대문자 상수만 뽑는다. (::character varying 같은 꼬리표는 소문자다) */
    private static final Pattern ALLOWED_VALUE = Pattern.compile("'([A-Z][A-Z_]*)'");

    @Autowired
    private DSLContext dsl;

    private Set<String> 제약이_허용하는_값(String constraintName) {
        Object definition = dsl.fetchValue(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
                constraintName);

        assertThat(definition)
                .as("%s 제약이 존재해야 한다", constraintName)
                .isNotNull();

        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = ALLOWED_VALUE.matcher(definition.toString());
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private List<String> 이름_목록(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    @Test
    @DisplayName("TaskType 과 tasks.task_type 허용 목록이 같다")
    void 작업_유형이_db_허용_목록과_같다() {
        assertThat(제약이_허용하는_값("ck_tasks_task_type"))
                .containsExactlyInAnyOrderElementsOf(이름_목록(TaskType.values()));
    }

    @Test
    @DisplayName("ProcessingRoute 와 tasks.processing_route 허용 목록이 같다")
    void 처리_경로가_db_허용_목록과_같다() {
        assertThat(제약이_허용하는_값("ck_tasks_processing_route"))
                .containsExactlyInAnyOrderElementsOf(이름_목록(ProcessingRoute.values()));
    }

    @Test
    @DisplayName("모든 작업 유형이 처리 경로와 함께 실제로 저장된다")
    void 모든_작업_유형이_저장된다() {
        // 목록 비교만으로는 제약 표현이 잘못됐을 때(따옴표·대소문자)나
        // 작업 유형과 처리 경로의 조합을 막는 다른 제약이 있을 때 드러나지 않는다.
        // 실제 INSERT 로 한 번 더 확인한다. (@Transactional 이라 시험 뒤 되돌아간다)
        for (TaskType taskType : TaskType.values()) {
            Long userId = dsl.insertInto(USERS)
                    .set(USERS.COGNITO_SUB, "task-type-" + taskType.name())
                    .set(USERS.EMAIL, taskType.name().toLowerCase() + "@example.com")
                    .returning(USERS.ID)
                    .fetchOne()
                    .getId();

            Long deviceId = null;
            if (taskType.requiresDevice()) {
                deviceId = dsl.insertInto(DEVICES)
                        .set(DEVICES.USER_ID, userId)
                        .set(DEVICES.NAME, "대상 PC")
                        .set(DEVICES.PUBLIC_KEY, "key-" + taskType.name())
                        .set(DEVICES.OS, "MACOS")
                        .set(DEVICES.ARCHITECTURE, "ARM64")
                        .returning(DEVICES.ID)
                        .fetchOne()
                        .getId();
            }

            int inserted = dsl.insertInto(TASKS)
                    .set(TASKS.USER_ID, userId)
                    .set(TASKS.DEVICE_ID, deviceId)
                    .set(TASKS.INPUT_TEXT, "계약 확인")
                    .set(TASKS.TASK_TYPE, taskType.name())
                    .set(TASKS.PROCESSING_ROUTE, taskType.processingRoute().name())
                    .execute();

            assertThat(inserted)
                    .as("%s 를 저장할 수 있어야 한다", taskType.name())
                    .isEqualTo(1);
        }
    }
}
