package com.likelion.slash.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST /api/v1/requests} 요청 본문. (WBS W1-04)
 *
 * <p>브라우저는 오직 이 Endpoint 로만 작업을 접수한다. 슬래시 명령과 자연어를 가르지 않고
 * 입력창의 한 줄을 그대로 보낸다 — 가르는 일은 slash-api 와 NLU 가 한다.
 *
 * @param text             입력창 원문. {@code /status} 처럼 슬래시로 시작해도 된다.
 * @param selectedDeviceId 실행할 PC. 비우면 등록된 PC 중에서 slash-api 가 고른다.
 *                         PC 가 필요 없는 작업({@code /weather}·{@code /summary})에서는 무시된다.
 */
public record CreateRequestRequest(

        @NotBlank(message = "요청 내용을 입력해 주세요.")
        @Size(max = 2000, message = "요청은 2000자를 넘을 수 없습니다.")
        String text,

        UUID selectedDeviceId) {
}
