package com.likelion.slash.device.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/v1/devices/{deviceId}/task-intake} 요청. (#24)
 *
 * <p>토글이 아니라 <b>원하는 상태를 그대로 보낸다.</b> 토글은 화면이 들고 있는 값이 낡았을 때
 * 사용자가 의도한 것과 반대로 뒤집힌다. 값을 지정하면 몇 번을 보내도 결과가 같다.
 *
 * @param accepting 참이면 새 작업을 받고, 거짓이면 연결은 유지한 채 받지 않는다
 */
public record TaskIntakeRequest(@NotNull Boolean accepting) {
}
