package com.likelion.slash.auth;

import com.likelion.slash.jooq.tables.records.UsersRecord;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 인증된 사용자. 다른 도메인이 사용하는 표준 형태다.
 *
 * <p>jOOQ 생성 Record 를 서비스 밖으로 내보내지 않기 위한 경계이기도 하다.
 *
 * @param id        내부 PK. Repository 호출에만 쓰고 API 응답에 넣지 않는다.
 * @param publicId  API 로 노출하는 식별자
 */
public record AuthenticatedUser(
        long id,
        UUID publicId,
        String email,
        String displayName,
        String timezone,
        String status,
        OffsetDateTime createdAt) {

    static AuthenticatedUser from(UsersRecord record) {
        return new AuthenticatedUser(
                record.getId(),
                record.getPublicId(),
                record.getEmail(),
                record.getDisplayName(),
                record.getTimezone(),
                record.getStatus(),
                record.getCreatedAt());
    }
}
