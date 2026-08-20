package com.likelion.slash.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 작업 이력 목록. ({@code GET /api/v1/tasks} · P0-B)
 *
 * <p>이력이 없어도 오류가 아니다. {@code items} 가 빈 배열로 온다.
 *
 * @param nextCursor 다음 쪽을 부를 때 {@code cursor} 로 그대로 넘길 값.
 *                   <b>마지막 쪽이면 없다</b> — 이 값이 없을 때 그만 부르면 된다.
 *                   항목 수가 {@code limit} 과 같은지로 판단하면 안 된다. 마지막 쪽이 정확히
 *                   {@code limit} 개일 수 있어서다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskHistoryResponse(List<TaskSummaryResponse> items, String nextCursor) {
}
