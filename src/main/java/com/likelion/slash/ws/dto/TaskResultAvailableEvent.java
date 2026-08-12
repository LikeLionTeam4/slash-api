package com.likelion.slash.ws.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.UserProtocol;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * 서버 → 브라우저 결과 도착. (계약 {@code taskResultAvailableEventSchema})
 *
 * <p>작업이 최종 상태에 닿았을 때 보낸다. 프론트는 이걸 신호로 REST 를 다시 조회한다.
 *
 * <p><b>본문 전체를 싣지 않는다.</b> 결과는 64KB 까지 커질 수 있는데({@code ck_tasks_result_size})
 * 그것을 열려 있는 모든 탭에 밀어 넣는 것은 알림 채널이 할 일이 아니다. 화면을 먼저 바꿀 수
 * 있을 만큼만 잘라 보내고 본문은 REST 로 받는다.
 *
 * <p><b>{@code null} 이어도 필드를 지우지 않는다.</b> 애플리케이션 기본 설정은
 * {@code default-property-inclusion: non_null} 이라 값이 없는 필드가 통째로 빠지는데,
 * 계약의 {@code resultPreview} 는 {@code z.string().nullable()} 이다. zod 의
 * {@code nullable} 은 <b>"있고 null"</b> 을 뜻하며 없는 것은 통과시키지 않는다. 그대로 두면
 * <b>실패로 끝난 작업마다 이 프레임이 브라우저에서 통째로 버려져</b> 화면이 결과 도착을
 * 영영 알지 못한다. ({@code optional} 이 아니라 {@code nullable} 인 것이 요점이다)
 *
 * @param resultPreview 결과 앞부분 ({@link UserProtocol#RESULT_PREVIEW_LIMIT}자).
 *                      실패로 끝났으면 결과가 없으므로 {@code null} 이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TaskResultAvailableEvent(
        String type,
        UUID taskId,
        TaskStatus status,
        String resultPreview) {

    public static TaskResultAvailableEvent of(UUID taskId, TaskStatus status, JSONB result) {
        return new TaskResultAvailableEvent(
                UserProtocol.TYPE_TASK_RESULT_AVAILABLE, taskId, status, preview(result));
    }

    private static String preview(JSONB result) {
        if (result == null || result.data() == null) {
            return null;
        }
        String data = result.data();
        if (data.length() <= UserProtocol.RESULT_PREVIEW_LIMIT) {
            return data;
        }

        // 이모지처럼 보조 평면에 있는 글자는 char 두 개로 저장된다. 그 가운데서 자르면 짝을 잃은
        // 서로게이트가 남아 화면에 깨진 글자로 보인다. 파일 검색 결과의 파일 이름에 이모지가
        // 들어가는 것은 드문 일이 아니다. 한 칸 물러나 온전한 글자에서 끊는다.
        int cut = UserProtocol.RESULT_PREVIEW_LIMIT;
        if (Character.isHighSurrogate(data.charAt(cut - 1))) {
            cut--;
        }
        return data.substring(0, cut);
    }
}
