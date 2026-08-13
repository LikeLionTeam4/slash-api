package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceStatus;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/devices} 확인. (WBS W1-03 · slash-api #17)
 *
 * <p>이 경로가 없어서 지정 PC 관리 화면이 방금 등록한 기기만 브라우저 메모리에 들고 있었다.
 * 새로고침하면 목록이 비고, 다른 탭에서는 이미 등록한 PC 가 보이지 않았다.
 *
 * <p>시험은 {@code local} 프로필이라 임시 인증이 켜져 있다. {@code Bearer alice} 로 보내면
 * {@code sub = alice} 인 사용자로 처리되므로 실제 HTTP 흐름 그대로 확인할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("등록한 PC 를 돌려준다")
    void 등록한_PC_를_돌려준다() throws Exception {
        long userId = 로그인한_사용자("alice");
        준비된_기기(dsl, userId);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.devices[0].deviceId").exists())
                .andExpect(jsonPath("$.data.devices[0].name").value("시험용 PC"))
                .andExpect(jsonPath("$.data.devices[0].status").value("READY"))
                .andExpect(jsonPath("$.data.devices[0].os").value("MACOS"))
                .andExpect(jsonPath("$.data.devices[0].registeredAt").exists());
    }

    @Test
    @DisplayName("등록한 PC 가 없으면 빈 목록이다 — 오류가 아니다")
    void 없으면_빈_목록이다() throws Exception {
        로그인한_사용자("alice");

        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("남의 PC 는 보이지 않는다")
    void 남의_PC_는_보이지_않는다() throws Exception {
        로그인한_사용자("alice");
        준비된_기기(dsl, 사용자(dsl));

        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("해제한 PC 는 목록에 오지 않는다")
    void 해제한_PC_는_빠진다() throws Exception {
        long userId = 로그인한_사용자("alice");
        long deviceId = 준비된_기기(dsl, userId);
        dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.REVOKED.name())
                .set(DEVICES.REVOKED_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("공개키와 기기 Token 은 내보내지 않는다")
    void 비밀값은_나가지_않는다() throws Exception {
        long userId = 로그인한_사용자("alice");
        준비된_기기(dsl, userId);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].publicKey").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].deviceTokenHash").doesNotExist());
    }

    @Test
    @DisplayName("한 번도 연결된 적 없으면 Agent 가 보고하는 값이 응답에서 빠진다")
    void 보고받지_못한_값은_빠진다() throws Exception {
        long userId = 로그인한_사용자("alice");
        준비된_기기(dsl, userId);

        // 계약 §2 — null 인 필드는 응답에 넣지 않는다. 프론트가 옵셔널 체이닝으로 읽는 근거다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].osVersion").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].agentVersion").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].lastSeenAt").doesNotExist());
    }

    @Test
    @DisplayName("시각은 한국 시각으로 내보낸다")
    void 시각은_한국_시각이다() throws Exception {
        long userId = 로그인한_사용자("alice");
        long deviceId = 준비된_기기(dsl, userId);
        dsl.update(DEVICES)
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        // 계약 §2 — UTC(Z)로 보내지 않는다. 프론트가 그대로 new Date() 로 파싱한다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].lastSeenAt", Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.devices[0].registeredAt", Matchers.endsWith("+09:00")));
    }

    @Test
    @DisplayName("deviceId 는 등록 응답이 준 값과 같다 — 작업 접수에 그대로 쓴다")
    void 식별자가_등록_응답과_같다() throws Exception {
        long userId = 로그인한_사용자("alice");
        long deviceId = 준비된_기기(dsl, userId);
        UUID 등록이_알려준_값 = dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);

        // 페어링 상태 조회(CLAIMED)와 목록이 서로 다른 값을 주면 화면이 같은 PC 를 둘로 본다.
        // 작업 접수의 selectedDeviceId 도 이 값이다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].deviceId").value(등록이_알려준_값.toString()));
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 이다")
    void 인증이_없으면_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 임시 인증으로 한 번 호출해 사용자 레코드를 만들고 내부 식별자를 돌려준다.
     *
     * <p>기기를 만들려면 {@code devices.user_id} 가 필요한데, 그 사용자는 첫 요청 때 생긴다.
     */
    private long 로그인한_사용자(String token) throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        return dsl.select(USERS.ID).from(USERS)
                .where(USERS.COGNITO_SUB.eq(token))
                .fetchOne(USERS.ID);
    }
}
