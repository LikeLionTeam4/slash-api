package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.ws.WsMessagePublisher;
import java.util.UUID;
import org.hamcrest.Matchers;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/devices} 확인. (WBS W1-03 · slash-api #17)
 *
 * <p>이 경로가 없어서 지정 PC 관리 화면이 방금 등록한 기기만 브라우저 메모리에 들고 있었다.
 * 새로고침하면 목록이 비고, 다른 탭에서는 이미 등록한 PC 가 보이지 않았다.
 *
 * <p>시험은 {@code local} 프로필이라 임시 인증이 켜져 있다. {@code Authorization} 에 담은
 * 문자열이 그대로 {@code sub} 이 되므로 실제 HTTP 흐름 그대로 확인할 수 있다.
 *
 * <p><b>이 시험만 쓰는 {@code sub} 을 둔다.</b> (이슈 #47) 예전에는 {@code alice} 를 썼는데,
 * 로컬에서 종단을 확인할 때 실물 Agent 도 같은 이름으로 페어링한다
 * ({@code docs/frontend-api-contract.md} §5.1). 그러면 <b>개발용으로 등록해 둔 기기와 시험이
 * 만든 기기가 같은 사용자 아래 섞여</b> 목록 개수를 세는 단언이 깨진다. 시험이 만든 것은
 * {@code @Transactional} 로 되돌려지지만, 시험 밖에서 이미 커밋된 자료는 그대로 남는다.
 * CI 는 매번 빈 DB 라 초록이고 <b>로컬에서만, Agent 를 붙여 둔 사람에게만</b> 재현된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceControllerTest {

    /** 이 시험만 쓰는 사용자. 개발용으로 붙여 둔 실제 자료와 섞이지 않게 한다. (이슈 #47) */
    private static final String SUB = "device-controller-tester";

    private static final String 인증 = "Bearer " + SUB;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private DeviceSearchFolderRepository searchFolderRepository;

    /** 실제 Valkey 발행 대신 호출만 확인한다. 발행 자체는 W1-06 시험이 맡는다. */
    @MockitoBean
    private WsMessagePublisher wsMessagePublisher;

    @Test
    @DisplayName("등록한 PC 를 돌려준다")
    void 등록한_PC_를_돌려준다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        준비된_기기(dsl, userId);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
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
        로그인한_사용자(SUB);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("PC 목록에 검색 폴더를 표시 이름과 함께 싣는다")
    void 검색_폴더를_함께_돌려준다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        searchFolderRepository.replaceAll(deviceId, List.of(
                new SearchFolder("sf-work", "업무 문서", SearchFolder.INDEXED),
                new SearchFolder("sf-photo", "사진", SearchFolder.INDEXING)));

        // 파일 검색 결과에는 searchFolderId 만 온다. 화면이 그것을 사람이 읽을 이름으로
        // 바꾸려면 이 목록이 필요하다. (이슈 #25)
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].searchFolders", Matchers.hasSize(2)))
                // 이름 순이라 "사진" 이 먼저다.
                .andExpect(jsonPath("$.data.devices[0].searchFolders[0].searchFolderId").value("sf-photo"))
                .andExpect(jsonPath("$.data.devices[0].searchFolders[0].displayName").value("사진"))
                .andExpect(jsonPath("$.data.devices[0].searchFolders[0].indexStatus").value("INDEXING"))
                .andExpect(jsonPath("$.data.devices[0].searchFolders[1].displayName").value("업무 문서"));
    }

    @Test
    @DisplayName("한 번도 연결된 적 없는 PC 는 검색 폴더가 빈 배열이다")
    void 폴더가_없으면_빈_배열이다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        준비된_기기(dsl, userId);

        // null 이 아니라 빈 배열이어야 화면이 분기 없이 그릴 수 있다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].searchFolders", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("남의 PC 는 보이지 않는다")
    void 남의_PC_는_보이지_않는다() throws Exception {
        로그인한_사용자(SUB);
        준비된_기기(dsl, 사용자(dsl));

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("해제한 PC 는 목록에 오지 않는다")
    void 해제한_PC_는_빠진다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.REVOKED.name())
                .set(DEVICES.REVOKED_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("공개키와 기기 Token 은 내보내지 않는다")
    void 비밀값은_나가지_않는다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        준비된_기기(dsl, userId);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].publicKey").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].deviceTokenHash").doesNotExist());
    }

    @Test
    @DisplayName("한 번도 연결된 적 없으면 Agent 가 보고하는 값이 응답에서 빠진다")
    void 보고받지_못한_값은_빠진다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        준비된_기기(dsl, userId);

        // 계약 §2 — null 인 필드는 응답에 넣지 않는다. 프론트가 옵셔널 체이닝으로 읽는 근거다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].osVersion").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].agentVersion").doesNotExist())
                .andExpect(jsonPath("$.data.devices[0].lastSeenAt").doesNotExist());
    }

    @Test
    @DisplayName("시각은 한국 시각으로 내보낸다")
    void 시각은_한국_시각이다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        dsl.update(DEVICES)
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        // 계약 §2 — UTC(Z)로 보내지 않는다. 프론트가 그대로 new Date() 로 파싱한다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].lastSeenAt", Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.devices[0].registeredAt", Matchers.endsWith("+09:00")));
    }

    @Test
    @DisplayName("deviceId 는 등록 응답이 준 값과 같다 — 작업 접수에 그대로 쓴다")
    void 식별자가_등록_응답과_같다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        UUID 등록이_알려준_값 = dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);

        // 페어링 상태 조회(CLAIMED)와 목록이 서로 다른 값을 주면 화면이 같은 PC 를 둘로 본다.
        // 작업 접수의 selectedDeviceId 도 이 값이다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].deviceId").value(등록이_알려준_값.toString()));
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 이다")
    void 인증이_없으면_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 등록 해제 (#23)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("해제하면 목록에서 빠지고 붙어 있는 연결을 끊는다")
    void 해제한다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.REVOKED.name());

        // 연결을 끊는 것은 커밋된 뒤라 이 시험(롤백)에서는 일어나지 않는다.
        // 그 부분은 DeviceRevokeCloseTest 가 실제로 커밋하며 확인한다.
        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(jsonPath("$.data.devices", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("If-Match 가 없으면 400 이다")
    void 헤더가_없으면_거부한다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(deviceId))
                        .header("Authorization", 인증))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.READY.name());
    }

    @Test
    @DisplayName("낡은 If-Match 로는 해제하지 못한다 — 그 사이 다른 탭에서 바뀐 것이다")
    void 낡은_버전은_412() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"99\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_MISMATCH"));

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.READY.name());
        verify(wsMessagePublisher, never()).sendAndClose(any(), anyLong(), any());
    }

    @Test
    @DisplayName("남의 PC 는 해제할 수 없다 — 있는지도 알려주지 않는다")
    void 남의_PC_는_해제하지_못한다() throws Exception {
        로그인한_사용자(SUB);
        long 남의_기기 = 준비된_기기(dsl, 사용자(dsl));

        // 403 으로 답하면 식별자를 넣어 보며 남의 기기가 존재하는지 알아낼 수 있다. (DV-04)
        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(남의_기기))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(상태(남의_기기)).isEqualTo(DeviceStatus.READY.name());
    }

    @Test
    @DisplayName("이미 해제한 PC 를 다시 해제하면 404 다")
    void 이미_해제된_것은_404() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        해제한다(deviceId);

        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 작업 수신 중지 (#24)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("작업 수신을 끄면 연결 상태는 그대로 두고 acceptingTasks 만 바뀐다")
    void 수신을_끈다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingTasks").value(false))
                // 연결을 끊는 것이 아니다. 상태는 READY 그대로여야 한다.
                .andExpect(jsonPath("$.data.status").value("READY"))
                // 다음 수정에 쓸 값이 함께 온다. 목록을 다시 부르지 않아도 된다.
                .andExpect(jsonPath("$.data.version").value(1));

        verify(wsMessagePublisher, never()).sendAndClose(any(), anyLong(), any());
    }

    @Test
    @DisplayName("수신 설정 응답도 목록과 같은 모양이다 — 검색 폴더를 함께 준다")
    void 수신_설정_응답에도_폴더가_온다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        searchFolderRepository.replaceAll(deviceId,
                List.of(new SearchFolder("sf-work", "업무 문서", SearchFolder.INDEXED)));

        // 화면은 이 응답으로 목록의 한 줄을 갱신한다. 여기서만 폴더가 비어 있으면
        // 토글을 누르는 순간 그 줄의 폴더 이름이 사라진다.
        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchFolders", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.searchFolders[0].displayName").value("업무 문서"));
    }

    @Test
    @DisplayName("다시 켤 수 있다 — 해제와 달리 되돌릴 수 있다")
    void 다시_켠다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        수신을(deviceId, false);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingTasks").value(true));
    }

    @Test
    @DisplayName("같은 값을 다시 보내도 결과가 같다 — 토글이 아니라 원하는 상태를 보낸다")
    void 같은_값은_결과가_같다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": false}"))
                .andExpect(status().isOk());

        // 낡은 화면이 토글을 보내면 사용자가 의도한 것과 반대로 뒤집힌다. 값을 지정해 그것을 막는다.
        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptingTasks").value(false));
    }

    @Test
    @DisplayName("같은 version 을 두 번 쓰면 두 번째는 412 다 — 낙관적 잠금")
    void 같은_버전을_두_번_쓰지_못한다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": false}"))
                .andExpect(status().isOk());

        // 두 화면이 같은 목록을 보고 동시에 눌렀을 때 뒤엣것이 앞엣것을 덮어쓰면 안 된다.
        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": true}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("해제한 뒤에는 수신 설정도 걸리지 않는다 — 같은 version 이어도")
    void 해제가_먼저면_수신설정은_막힌다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(delete("/api/v1/devices/{id}", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());

        // 해제와 수신 설정이 경쟁하면 해제가 이겨야 한다. 해제된 기기가 되살아나면 안 된다.
        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": true}"))
                .andExpect(status().isNotFound());

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.REVOKED.name());
    }

    @Test
    @DisplayName("accepting 이 없으면 400 이다")
    void 값이_없으면_거부한다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("해제한 PC 에는 수신 설정을 걸 수 없다")
    void 해제한_PC_에는_걸_수_없다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        long deviceId = 준비된_기기(dsl, userId);
        해제한다(deviceId);

        mockMvc.perform(patch("/api/v1/devices/{id}/task-intake", 공개식별자(deviceId))
                        .header("Authorization", 인증)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepting\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("목록에도 수신 여부가 함께 온다")
    void 목록에_수신_여부가_온다() throws Exception {
        long userId = 로그인한_사용자(SUB);
        수신을(준비된_기기(dsl, userId), false);

        mockMvc.perform(get("/api/v1/devices").header("Authorization", 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices[0].acceptingTasks").value(false))
                .andExpect(jsonPath("$.data.devices[0].version").exists());
    }

    private UUID 공개식별자(long deviceId) {
        return dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);
    }

    private String 상태(long deviceId) {
        return dsl.select(DEVICES.STATUS).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.STATUS);
    }

    private void 해제한다(long deviceId) {
        dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.REVOKED.name())
                .set(DEVICES.REVOKED_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();
    }

    private void 수신을(long deviceId, boolean accepting) {
        dsl.update(DEVICES)
                .set(DEVICES.ACCEPTING_TASKS, accepting)
                .where(DEVICES.ID.eq(deviceId))
                .execute();
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
