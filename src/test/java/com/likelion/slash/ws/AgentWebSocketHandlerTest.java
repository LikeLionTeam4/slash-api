package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.dispatch.AgentDispatchRepository;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link AgentWebSocketHandler} 확인. (WBS W1-06)
 *
 * <p>특히 두 가지를 본다.
 * <ul>
 *   <li>서명 검증을 통과하기 전에는 소켓이 보관소에 등록되지 않는다 (프레임이 새지 않는다)</li>
 *   <li>남의 {@code dispatchId} 로 남의 전달을 마감시킬 수 없다 (문서 DV-04)</li>
 * </ul>
 */
class AgentWebSocketHandlerTest {

    private static final long 기기_PK = 42L;
    private static final UUID 기기_공개ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WsSessionRegistry registry = new WsSessionRegistry();

    private final DeviceRepository deviceRepository = mock(DeviceRepository.class);
    private final DeviceCapabilityRepository deviceCapabilityRepository = mock(DeviceCapabilityRepository.class);
    private final AgentDispatchRepository agentDispatchRepository = mock(AgentDispatchRepository.class);

    private final AgentWebSocketHandler handler = new AgentWebSocketHandler(
            objectMapper,
            registry,
            new AgentSignatureVerifier(),
            deviceRepository,
            deviceCapabilityRepository,
            agentDispatchRepository);

    private KeyPair 기기_키쌍;
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        기기_키쌍 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        DevicesRecord device = new DevicesRecord();
        device.setId(기기_PK);
        device.setPublicId(기기_공개ID);
        device.setStatus(DeviceStatus.OFFLINE.name());
        device.setPublicKey(Base64.getEncoder().encodeToString(기기_키쌍.getPublic().getEncoded()));

        when(deviceRepository.findByPublicId(기기_공개ID)).thenReturn(Optional.of(device));
        when(deviceRepository.findById(기기_PK)).thenReturn(Optional.of(device));

        session = 세션();
        handler.afterConnectionEstablished(session);
    }

    // ------------------------------------------------------------------
    // 인증
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HELLO 를 받으면 1회용 도전값을 돌려준다")
    void 도전값을_보낸다() throws Exception {
        보낸다(hello());

        assertThat(마지막_응답().path("type").asText()).isEqualTo("CHALLENGE");
        assertThat(마지막_응답().path("nonce").asText()).isNotBlank();
    }

    @Test
    @DisplayName("등록되지 않은 기기는 인증 실패로 끊는다")
    void 모르는_기기는_끊는다() throws Exception {
        when(deviceRepository.findByPublicId(any())).thenReturn(Optional.empty());

        보낸다("{\"type\":\"HELLO\",\"deviceId\":\"" + UUID.randomUUID() + "\"}");

        verify(session).close(종료코드(4401));
    }

    @Test
    @DisplayName("등록이 해제된 기기는 인증 실패로 끊는다")
    void 해제된_기기는_끊는다() throws Exception {
        DevicesRecord revoked = new DevicesRecord();
        revoked.setId(기기_PK);
        revoked.setStatus(DeviceStatus.REVOKED.name());
        when(deviceRepository.findByPublicId(기기_공개ID)).thenReturn(Optional.of(revoked));

        보낸다(hello());

        verify(session).close(종료코드(4401));
    }

    @Test
    @DisplayName("서명이 맞으면 소켓을 보관소에 등록하고 ONLINE 으로 올린다")
    void 인증에_성공한다() throws Exception {
        인증한다();

        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isTrue();
        verify(deviceRepository).updateConnectionState(기기_PK, DeviceStatus.ONLINE);
    }

    @Test
    @DisplayName("서명이 틀리면 끊고, 보관소에 등록하지 않는다")
    void 서명이_틀리면_등록하지_않는다() throws Exception {
        보낸다(hello());
        보낸다("{\"type\":\"AUTH\",\"signature\":\"" + Base64.getEncoder().encodeToString("가짜".getBytes()) + "\"}");

        verify(session).close(종료코드(4401));
        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isFalse();
    }

    @Test
    @DisplayName("HELLO 없이 보낸 프레임은 프로토콜 위반으로 끊는다")
    void 순서를_건너뛰면_끊는다() throws Exception {
        보낸다("{\"type\":\"HEARTBEAT\"}");

        verify(session).close(종료코드(4400));
        verify(deviceRepository, never()).touchLastSeen(anyLong());
    }

    @Test
    @DisplayName("같은 기기가 다시 접속하면 이전 연결을 끊는다")
    void 재접속이_이전_연결을_밀어낸다() throws Exception {
        인증한다();
        WebSocketSession 옛_연결 = session;

        session = 세션();
        handler.afterConnectionEstablished(session);
        인증한다();

        verify(옛_연결).close(종료코드(4409));
        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isTrue();
    }

    // ------------------------------------------------------------------
    // 연결 종료
    // ------------------------------------------------------------------

    @Test
    @DisplayName("연결이 끊기면 OFFLINE 으로 내린다")
    void 종료하면_OFFLINE() throws Exception {
        인증한다();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isFalse();
        verify(deviceRepository).updateConnectionState(기기_PK, DeviceStatus.OFFLINE);
    }

    @Test
    @DisplayName("재접속에 밀려난 옛 연결이 닫혀도 OFFLINE 으로 내리지 않는다")
    void 밀려난_연결은_기기를_내리지_않는다() throws Exception {
        인증한다();
        WebSocketSession 옛_연결 = session;

        session = 세션();
        handler.afterConnectionEstablished(session);
        인증한다();

        // 새 연결이 등록된 뒤에 옛 연결의 종료 처리가 도착하는 순서다.
        handler.afterConnectionClosed(옛_연결, new CloseStatus(4409, "CONNECTION_SUPERSEDED"));

        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isTrue();
        verify(deviceRepository, never()).updateConnectionState(기기_PK, DeviceStatus.OFFLINE);
    }

    // ------------------------------------------------------------------
    // 전달 반영
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ACK 를 받으면 전달을 수락 처리한다")
    void ACK_를_반영한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.DISPATCHED);

        보낸다("{\"type\":\"ACK\",\"dispatchId\":\"" + dispatchId + "\",\"accepted\":true}");

        verify(agentDispatchRepository).acknowledge(7L);
    }

    @Test
    @DisplayName("다른 기기의 dispatchId 는 반영하지 않는다")
    void 남의_전달은_건드리지_못한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(999L, AgentDispatchStatus.DISPATCHED);

        보낸다("{\"type\":\"RESULT\",\"dispatchId\":\"" + dispatchId + "\",\"ok\":false,"
                + "\"error\":{\"code\":\"SEARCH_FOLDER_NOT_FOUND\"}}");

        verify(agentDispatchRepository, never()).fail(anyLong(), any());
        verify(agentDispatchRepository, never()).complete(anyLong());
    }

    @Test
    @DisplayName("이미 마감된 전달에는 결과를 다시 반영하지 않는다")
    void 마감된_전달은_다시_반영하지_않는다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.COMPLETED);

        보낸다("{\"type\":\"RESULT\",\"dispatchId\":\"" + dispatchId + "\",\"ok\":true}");

        verify(agentDispatchRepository, never()).complete(anyLong());
    }

    @Test
    @DisplayName("READY 를 받으면 지원 작업을 저장하고 READY 로 올린다")
    void READY_를_반영한다() throws Exception {
        인증한다();

        보낸다("{\"type\":\"READY\",\"supportedTaskTypes\":[\"FILE_SEARCH\",\"SYSTEM_STATUS\",\"없는유형\"]}");

        verify(deviceCapabilityRepository).replaceAll(eq(기기_PK), any());
        verify(deviceRepository).updateConnectionState(기기_PK, DeviceStatus.READY);
    }

    @Test
    @DisplayName("모르는 프레임은 무시한다 — Agent 가 새 프레임을 먼저 배포해도 끊기지 않는다")
    void 모르는_프레임은_무시한다() throws Exception {
        인증한다();

        보낸다("{\"type\":\"미래의_프레임\"}");

        verify(session, never()).close(any());
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private void 인증한다() throws Exception {
        보낸다(hello());
        보낸다("{\"type\":\"AUTH\",\"signature\":\"" + 서명(도전값()) + "\"}");
    }

    private String hello() {
        return "{\"type\":\"HELLO\",\"deviceId\":\"" + 기기_공개ID + "\"}";
    }

    private void 보낸다(String frame) throws Exception {
        handler.handleMessage(session, new TextMessage(frame));
    }

    private byte[] 도전값() {
        return (byte[]) session.getAttributes().get("slash.challenge");
    }

    private String 서명(byte[] challenge) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(기기_키쌍.getPrivate());
        signature.update(challenge);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private UUID 전달을_준비한다(long deviceId, AgentDispatchStatus status) {
        UUID publicId = UUID.randomUUID();

        AgentDispatchesRecord dispatch = new AgentDispatchesRecord();
        dispatch.setId(7L);
        dispatch.setPublicId(publicId);
        dispatch.setDeviceId(deviceId);
        dispatch.setStatus(status.name());

        when(agentDispatchRepository.findByPublicId(publicId)).thenReturn(Optional.of(dispatch));
        return publicId;
    }

    private JsonNode 마지막_응답() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return objectMapper.readTree(captor.getValue().getPayload());
    }

    /**
     * 종료 코드만 확인하는 Mockito 인자 조건.
     *
     * <p>{@link CloseStatus} 는 사유 문자열까지 같아야 동등하다. 사유는 사람이 읽는 값이라
     * 문구가 바뀌었다고 시험이 깨지면 안 되므로 코드만 본다.
     */
    private static CloseStatus 종료코드(int code) {
        return argThat(status -> status != null && status.getCode() == code);
    }

    private static WebSocketSession 세션() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
