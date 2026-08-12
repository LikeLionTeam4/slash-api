package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.dto.ConnectedEvent;
import com.likelion.slash.ws.dto.TaskResultAvailableEvent;
import com.likelion.slash.ws.dto.TaskStatusChangedEvent;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 브라우저로 나가는 이벤트의 <b>실제 직렬화 결과</b> 확인. (WBS W1-06)
 *
 * <p><b>애플리케이션 Mapper 를 그대로 쓴다.</b> 새 {@code ObjectMapper} 로 시험하면 이 시험의
 * 의미가 사라진다 — 여기서 잡으려는 것이 바로 애플리케이션 설정
 * ({@code default-property-inclusion: non_null}, {@code write-dates-as-timestamps: false},
 * {@code time-zone: Asia/Seoul})이 계약과 어긋나는 경우이기 때문이다.
 *
 * <p>프론트는 zod 로 프레임을 검증하고 <b>맞지 않으면 통째로 버린다.</b> 필드 하나가 빠지거나
 * 시각 형식이 다르면 화면이 조용히 갱신되지 않는다. 로그에도 남지 않아 원인을 찾기 어렵다.
 */
@SpringBootTest
class UserEventSerializationTest {

    private static final UUID 작업 = UUID.randomUUID();

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("결과가 없어도 resultPreview 필드는 남는다 — 계약은 nullable 이지 optional 이 아니다")
    void 빈_미리보기도_필드를_남긴다() throws Exception {
        JsonNode frame = 직렬화(TaskResultAvailableEvent.of(작업, TaskStatus.FAILED, null));

        // 애플리케이션 기본 설정(non_null)에 맡기면 이 필드가 통째로 빠진다.
        // 그러면 실패로 끝난 작업마다 프레임이 zod 에서 버려져 결과 도착을 알지 못한다.
        assertThat(frame.has("resultPreview")).isTrue();
        assertThat(frame.get("resultPreview").isNull()).isTrue();
    }

    @Test
    @DisplayName("결과가 있으면 미리보기를 담는다")
    void 미리보기를_담는다() throws Exception {
        JsonNode frame = 직렬화(
                TaskResultAvailableEvent.of(작업, TaskStatus.SUCCEEDED, JSONB.valueOf("{\"cpu\":12}")));

        assertThat(frame.get("type").asText()).isEqualTo("TASK_RESULT_AVAILABLE");
        assertThat(frame.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(frame.get("resultPreview").asText()).contains("cpu");
    }

    @Test
    @DisplayName("시각은 한국 시각 오프셋이 붙은 ISO 문자열이다")
    void 시각_형식() throws Exception {
        JsonNode 상태변경 = 직렬화(
                TaskStatusChangedEvent.of(작업, TaskStatus.QUEUED, TaskStatus.RUNNING));
        JsonNode 접속 = 직렬화(ConnectedEvent.create());

        // epoch 숫자로 나가면 계약의 datetime({offset:true}) 검증에서 버려진다.
        // W1-04 에서 NLU 로 epoch 이 나갔던 것과 같은 종류의 어긋남이다.
        assertThat(상태변경.get("occurredAt").asText()).endsWith("+09:00");
        assertThat(접속.get("serverTime").asText()).endsWith("+09:00");
    }

    @Test
    @DisplayName("상태는 이름 문자열로 나간다")
    void 상태_형식() throws Exception {
        JsonNode frame = 직렬화(
                TaskStatusChangedEvent.of(작업, TaskStatus.QUEUED, TaskStatus.RUNNING));

        assertThat(frame.get("from").asText()).isEqualTo("QUEUED");
        assertThat(frame.get("to").asText()).isEqualTo("RUNNING");
        assertThat(frame.get("taskId").asText()).isEqualTo(작업.toString());
    }

    private JsonNode 직렬화(Object event) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(event));
    }
}
