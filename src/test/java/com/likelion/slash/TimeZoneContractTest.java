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

    // ------------------------------------------------------------------
    // DB 서버 자체의 기본 시간대는 여기서 확인하지 않는다
    //
    //   psql·관리 도구로 직접 조회할 때도 한국 시각으로 보여야 한다는 요구는 그대로다.
    //   (메시지 프로토콜 정의 3.5) 다만 그것을 이 시험으로 확인할 수는 없다.
    //
    //   pg_settings.reset_val 을 보는 시험이 있었는데, 그 값은 서버 설정이 아니라
    //   JDBC 가 접속할 때 startup 으로 보낸 시간대다 — 즉 시험을 돌리는 JVM 의 기본
    //   시간대다. 개발자 노트북(Asia/Seoul)에서는 통과하고 CI 러너(UTC)에서는 실패했다.
    //   서버는 양쪽 다 Asia/Seoul 로 떠 있었는데도 그랬다.
    //
    //   앱은 Hikari 의 connection-init-sql 로 연결마다 SET TIME ZONE 을 걸기 때문에
    //   (application.yml) 어떤 JDBC 연결에서도 서버 기본값을 볼 수 없다. setting 은
    //   SET 한 값이고, reset_val 은 startup 값이며, boot_val 은 컴파일 기본값(GMT)이다.
    //
    //   서버 기본 시간대는 인프라 설정이 보장한다.
    //     - 로컬  docker-compose.yml 의 postgres -c timezone=Asia/Seoul
    //     - CI    .github/workflows/test.yml 이 같은 값으로 컨테이너를 띄운다
    //     - 배포  RDS 파라미터 그룹 (slash-infra)
    //
    //   위 세 가지가 어긋나면 앱 동작이 아니라 장애 조사 때 DB 를 직접 볼 때 드러난다.
    // ------------------------------------------------------------------
}
