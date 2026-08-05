package com.likelion.slash.auth.dto;

import com.likelion.slash.auth.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code GET /api/v1/me} 응답.
 *
 * <p>내부 PK 와 Cognito {@code sub} 는 넣지 않는다. 화면이 쓸 일이 없고,
 * 노출하면 다른 사용자의 식별자를 추측할 실마리가 된다.
 *
 * @param userId 이후 모든 API 에서 사용자를 가리키는 식별자
 */
public record MeResponse(
        UUID userId,
        String email,
        String displayName,
        String timezone,
        String status,
        OffsetDateTime createdAt) {

    public static MeResponse from(AuthenticatedUser user) {
        return new MeResponse(
                user.publicId(),
                user.email(),
                user.displayName(),
                user.timezone(),
                user.status(),
                user.createdAt());
    }
}
