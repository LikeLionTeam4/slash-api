package com.likelion.slash.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityTag} 확인.
 *
 * <p>표준 형식은 따옴표로 감싼 값이지만 프론트가 응답 본문의 {@code version} 을 그대로 넣는
 * 경우가 흔하다. 그것을 거절해서 얻는 것이 없으므로 둘 다 받는다.
 */
class EntityTagTest {

    @Test
    @DisplayName("따옴표로 감싼 값을 읽는다")
    void 따옴표를_벗긴다() {
        assertThat(EntityTag.parseVersion("\"3\"")).isEqualTo(3);
    }

    @Test
    @DisplayName("따옴표가 없어도 읽는다")
    void 맨_숫자도_받는다() {
        assertThat(EntityTag.parseVersion("3")).isEqualTo(3);
    }

    @Test
    @DisplayName("약한 검증자(W/)도 값은 같게 읽는다")
    void 약한_검증자를_읽는다() {
        assertThat(EntityTag.parseVersion("W/\"3\"")).isEqualTo(3);
    }

    @Test
    @DisplayName("앞뒤 공백을 무시한다")
    void 공백을_무시한다() {
        assertThat(EntityTag.parseVersion("  \"3\"  ")).isEqualTo(3);
    }

    @Test
    @DisplayName("헤더가 없으면 400 이다")
    void 없으면_거부한다() {
        // HTTP 는 428 Precondition Required 를 두지만 계약 문서의 오류 코드 표에 없는 값이라
        // 프론트가 모르는 코드를 새로 만들지 않는다.
        assertThatThrownBy(() -> EntityTag.parseVersion(null))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("빈 값도 없는 것으로 본다")
    void 빈_값을_거부한다() {
        assertThatThrownBy(() -> EntityTag.parseVersion("  "))
                .isInstanceOf(SlashException.class);
    }

    @Test
    @DisplayName("숫자가 아니면 400 이다")
    void 숫자가_아니면_거부한다() {
        // 여러 ETag 를 콤마로 나열하는 형식(If-Match: "1", "2")도 여기서 걸린다.
        // 우리 자원은 version 하나뿐이라 그 형식을 받을 이유가 없다.
        assertThatThrownBy(() -> EntityTag.parseVersion("\"1\", \"2\""))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> EntityTag.parseVersion("*"))
                .isInstanceOf(SlashException.class);
    }
}
