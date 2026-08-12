package com.likelion.slash.ws.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.UserProtocol;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 결과 미리보기 자르기 확인.
 *
 * <p>본문 전체를 알림에 싣지 않는 이유는 결과가 64KB 까지 커질 수 있어서다. 자르는 자리를
 * 잘못 잡으면 화면에 깨진 글자가 보인다.
 */
class TaskResultAvailableEventTest {

    private static final UUID 작업 = UUID.randomUUID();

    @Test
    @DisplayName("짧은 결과는 그대로 싣는다")
    void 짧으면_그대로() {
        var event = TaskResultAvailableEvent.of(작업, TaskStatus.SUCCEEDED, JSONB.valueOf("{\"a\":1}"));

        assertThat(event.resultPreview()).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("긴 결과는 상한까지만 싣는다")
    void 길면_자른다() {
        JSONB 큰_결과 = JSONB.valueOf("{\"v\":\"" + "x".repeat(500) + "\"}");

        var event = TaskResultAvailableEvent.of(작업, TaskStatus.SUCCEEDED, 큰_결과);

        assertThat(event.resultPreview()).hasSize(UserProtocol.RESULT_PREVIEW_LIMIT);
    }

    @Test
    @DisplayName("이모지를 반으로 쪼개지 않는다")
    void 서로게이트를_쪼개지_않는다() {
        // 자르는 자리에 이모지(char 2개)가 걸리도록 길이를 맞춘다.
        String 앞 = "y".repeat(UserProtocol.RESULT_PREVIEW_LIMIT - 1);
        JSONB 결과 = JSONB.valueOf("{\"v\":\"" + 앞 + "🙂" + "z".repeat(300) + "\"}");

        String preview = TaskResultAvailableEvent.of(작업, TaskStatus.SUCCEEDED, 결과).resultPreview();

        // 짝을 잃은 서로게이트가 남으면 화면에 깨진 글자로 보인다.
        assertThat(preview).doesNotMatch(".*[\\uD800-\\uDBFF]$");
        assertThat(preview.codePoints().allMatch(Character::isDefined)).isTrue();
    }

    @Test
    @DisplayName("실패로 끝나 결과가 없으면 null 이다")
    void 결과가_없으면_null() {
        var event = TaskResultAvailableEvent.of(작업, TaskStatus.FAILED, null);

        assertThat(event.resultPreview()).isNull();
        assertThat(event.status()).isEqualTo(TaskStatus.FAILED);
    }
}
