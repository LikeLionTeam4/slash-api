package com.likelion.slash.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserRepository} 의 Cognito 동기화 동작 확인.
 *
 * <p>첫 로그인에 별도 가입 절차가 없으므로, 같은 {@code sub} 로 몇 번을 들어와도
 * 사용자 행이 하나만 유지되는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("첫 로그인이면 사용자를 새로 만든다")
    void 첫_로그인은_사용자를_만든다() {
        String cognitoSub = "sub-" + UUID.randomUUID();

        var user = userRepository.syncFromToken(cognitoSub, "user@example.com", "김멋사");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getPublicId()).isNotNull();
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getTimezone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("같은 sub 로 다시 로그인하면 행을 늘리지 않고 최신 값으로 맞춘다")
    void 재로그인은_같은_행을_갱신한다() {
        String cognitoSub = "sub-" + UUID.randomUUID();
        var first = userRepository.syncFromToken(cognitoSub, "before@example.com", "이전 이름");

        var second = userRepository.syncFromToken(cognitoSub, "after@example.com", "새 이름");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getEmail()).isEqualTo("after@example.com");
        assertThat(second.getDisplayName()).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("토큰에 표시 이름이 없으면 화면에서 바꾼 이름을 지우지 않는다")
    void 표시_이름이_없으면_기존_값을_지키다() {
        String cognitoSub = "sub-" + UUID.randomUUID();
        userRepository.syncFromToken(cognitoSub, "user@example.com", "내가 정한 이름");

        var synced = userRepository.syncFromToken(cognitoSub, "user@example.com", null);

        assertThat(synced.getDisplayName()).isEqualTo("내가 정한 이름");
    }

    @Test
    @DisplayName("공개 식별자로 조회할 수 있다")
    void 공개_식별자로_조회한다() {
        var user = userRepository.syncFromToken("sub-" + UUID.randomUUID(), "user@example.com", null);

        assertThat(userRepository.findByPublicId(user.getPublicId()))
                .get()
                .extracting(record -> record.getId())
                .isEqualTo(user.getId());
    }

    @Test
    @DisplayName("없는 사용자를 찾으면 비어 있다")
    void 없는_사용자는_비어_있다() {
        assertThat(userRepository.findByCognitoSub("존재하지-않는-sub")).isEmpty();
    }
}
