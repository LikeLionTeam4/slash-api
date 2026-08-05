package com.likelion.slash.auth;

import static com.likelion.slash.jooq.Tables.USERS;

import com.likelion.slash.common.enums.UserStatus;
import com.likelion.slash.jooq.tables.records.UsersRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code users} 접근.
 *
 * <p>비밀번호·토큰은 Cognito 가 관리하므로 이 표에는 계정 식별 정보만 둔다. (문서 AC-01)
 * {@code updated_at} 은 {@code trg_users_set_updated_at} 이 갱신하므로 직접 쓰지 않는다.
 *
 * <p>관련 문서: 3.2 · WBS W1-01
 */
@Repository
public class UserRepository {

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<UsersRecord> findById(long id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id))
                .fetchOptional();
    }

    /** API 로 노출한 식별자로 조회한다. 내부 PK 는 응답에 넣지 않는다. */
    public Optional<UsersRecord> findByPublicId(UUID publicId) {
        return dsl.selectFrom(USERS)
                .where(USERS.PUBLIC_ID.eq(publicId))
                .fetchOptional();
    }

    public Optional<UsersRecord> findByCognitoSub(String cognitoSub) {
        return dsl.selectFrom(USERS)
                .where(USERS.COGNITO_SUB.eq(cognitoSub))
                .fetchOptional();
    }

    /**
     * Cognito JWT 의 클레임으로 사용자 레코드를 만들거나 최신 값으로 맞춘다.
     *
     * <p>첫 로그인에 별도 가입 절차가 없으므로 {@code sub} 를 기준으로 삽입·갱신을 한 문장으로 처리한다.
     * 두 요청이 동시에 첫 로그인을 처리해도 {@code uk_users_cognito_sub} 덕분에 한 행만 남는다.
     *
     * <p>{@code displayName} 은 사용자가 화면에서 바꿀 수 있으므로 토큰에 값이 없을 때
     * 기존 값을 지우지 않는다.
     */
    public UsersRecord syncFromToken(String cognitoSub, String email, String displayName) {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, cognitoSub)
                .set(USERS.EMAIL, email)
                .set(USERS.DISPLAY_NAME, displayName)
                .onConflict(USERS.COGNITO_SUB)
                .doUpdate()
                .set(USERS.EMAIL, DSL.excluded(USERS.EMAIL))
                .set(USERS.DISPLAY_NAME, DSL.coalesce(DSL.excluded(USERS.DISPLAY_NAME), USERS.DISPLAY_NAME))
                .returning()
                .fetchOne();
    }

    /** 화면에서 바꾼 표시 이름과 시간대를 반영한다. */
    public Optional<UsersRecord> updateProfile(long id, String displayName, String timezone) {
        return dsl.update(USERS)
                .set(USERS.DISPLAY_NAME, displayName)
                .set(USERS.TIMEZONE, timezone)
                .where(USERS.ID.eq(id))
                .returning()
                .fetchOptional();
    }

    /** 이용 정지·탈퇴 처리. 감사 기록 보존을 위해 행을 지우지 않는다. */
    public boolean updateStatus(long id, UserStatus status) {
        return dsl.update(USERS)
                .set(USERS.STATUS, status.name())
                .where(USERS.ID.eq(id))
                .execute() == 1;
    }
}
