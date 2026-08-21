package com.likelion.slash.approval.dto;

import com.likelion.slash.common.enums.ApprovalDecision;
import jakarta.validation.constraints.NotNull;

/**
 * 사용자의 결정. {@code POST /api/v1/tasks/{taskId}/approval}
 *
 * <p>토글이 아니라 원하는 결정을 그대로 보낸다. 같은 값을 두 번 보내도 결과가 같다 —
 * 화면이 들고 있는 값이 낡았을 때 의도와 반대로 뒤집히는 것을 막는다.
 * (기기 작업 수신 켜기·끄기와 같은 방식)
 */
public record ApprovalDecisionRequest(@NotNull ApprovalDecision decision) {
}
