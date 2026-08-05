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

    @Test
    @DisplayName("DB 서버 기본 시간대도 Asia/Seoul 이다")
    void db_서버_기본_시간대도_한국이다() {
        // 앱은 JDBC Session 에서 시간대를 걸지만, psql·관리 도구로 직접 조회할 때도
        // 별도 변환 없이 한국 시각으로 보여야 한다. (메시지 프로토콜 정의 3.5)
        // 로컬은 docker-compose 의 postgres -c timezone, 배포 환경은 RDS 파라미터 그룹으로 맞춘다.
        //
        // setting 은 Session 에서 SET 한 값이라 서버 기본값을 확인할 수 없고,
        // boot_val 은 컴파일 기본값(GMT)이라 설정과 무관하다.
        // reset_val 이 Session 설정과 무관하게 서버·데이터베이스 기본값을 유지한다.
        String serverDefault = dsl.fetchValue(
                "SELECT reset_val FROM pg_settings WHERE name = 'TimeZone'").toString();

        assertThat(serverDefault).isEqualTo("Asia/Seoul");
    }
}
