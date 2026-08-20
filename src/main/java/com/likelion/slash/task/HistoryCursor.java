package com.likelion.slash.task;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 이력 목록의 다음 쪽을 가리키는 표식. ({@code GET /api/v1/tasks})
 *
 * <p><b>OFFSET 을 쓰지 않는 이유.</b> 목록을 넘기는 동안 새 작업이 생기면 OFFSET 방식은
 * 항목이 한 칸씩 밀려 같은 작업을 두 번 보거나 건너뛴다. 마지막으로 읽은 행을 가리키면
 * 그런 일이 없다. 정렬 기준이 {@code (created_at DESC, id DESC)} 이므로 표식도 두 값을 함께 담는다.
 *
 * <p><b>{@code created_at} 만으로는 부족하다.</b> 같은 순간에 접수된 요청이 둘 이상이면
 * 시각이 같아 순서를 정할 수 없다. 그래서 {@code id} 를 함께 싣는다.
 *
 * <p>밖으로는 뜻을 알 수 없는 한 덩어리 문자열로 나간다. 프론트는 받은 값을 그대로 돌려주면 되고,
 * 서버는 나중에 담는 내용을 바꿔도 계약을 깨지 않는다. <b>가려서 지키는 값이 아니다</b> —
 * 조회에는 언제나 사용자 조건이 함께 붙어서, 표식을 지어내도 자기 작업 밖으로는 나가지 못한다.
 */
public record HistoryCursor(OffsetDateTime createdAt, long id) {

    private static final String DELIMITER = "|";

    /** 시각은 나노초까지 그대로 적는다. 밀리초로 줄이면 같은 밀리초 안의 행을 건너뛴다. */
    public String encode() {
        String raw = createdAt + DELIMITER + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 표식을 되읽는다. 빈 값이면 첫 쪽이라는 뜻으로 {@code null} 을 준다.
     *
     * <p>망가진 값은 조용히 첫 쪽으로 돌리지 않고 {@code VALIDATION_ERROR} 로 알린다.
     * 말없이 처음으로 되돌리면 프론트가 같은 쪽을 끝없이 다시 부르게 된다.
     */
    public static HistoryCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int at = raw.lastIndexOf(DELIMITER);
            if (at < 0) {
                throw new SlashException(ErrorCode.VALIDATION_ERROR);
            }
            return new HistoryCursor(
                    OffsetDateTime.parse(raw.substring(0, at)),
                    Long.parseLong(raw.substring(at + DELIMITER.length())));

        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
