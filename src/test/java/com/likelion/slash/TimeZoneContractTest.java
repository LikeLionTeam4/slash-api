package com.likelion.slash;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.response.ApiResponse;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 프로젝트 시각 기준이 한국 시각인지 확인한다. (메시지 프로토콜 정의 3.5) */
@SpringBootTest
class TimeZoneContractTest {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private DSLContext dsl;

    @Test
    @DisplayName("API 응답 시각은 +09:00 으로 직렬화된다")
    void 응답_시각은_한국_시각으로_나간다() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.of("ok"));
        assertThat(json).contains("+09:00").doesNotContain("Z\"");
    }

    @Test
    @DisplayName("DB Session 시간대가 Asia/Seoul 이다")
    void db_session_시간대가_한국이다() {
        String tz = dsl.fetchValue("SHOW TIME ZONE").toString();
        assertThat(tz).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("DB 에서 읽은 시각도 한국 오프셋으로 온다")
    void db_시각도_한국_오프셋이다() {
        OffsetDateTime now = (OffsetDateTime) dsl.fetchValue("SELECT now()");
        assertThat(now.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }
}
