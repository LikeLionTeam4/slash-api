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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.dispatch.AgentDispatchRepository;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link AgentWebSocketHandler} 확인. (WBS W1-06)
 *
 * <p>계약(slash-agent 의 {@code contracts/src/agentMessages.ts})과 맞는지를 본다. 특히
 * <ul>
 *   <li>서명 대상이 {@code challengeId:nonce:deviceId} 문자열인가</li>
 *   <li>RESULT 판정이 {@code status} 문자열인가 (boolean 필드는 계약에 없다)</li>
 *   <li>오류를 PROTOCOL_ERROR 로 알리는가 — 그냥 끊으면 Agent 가 이유를 모른다</li>
 *   <li>서명 검증 전에는 소켓이 보관소에 등록되지 않는가 (프레임이 새지 않는다)</li>
 *   <li>남의 {@code dispatchId} 로 남의 전달을 마감시킬 수 없는가 (문서 DV-04)</li>
 * </ul>
 */
class AgentWebSocketHandlerTest {

    private static final long 기기_PK = 42L;
    private static final UUID 기기_공개ID = UUID.randomUUID();
    private static final String 기기_TOKEN = "device-token-값";
    private static final int RAW_KEY_LENGTH = 32;

    /**
     * 애플리케이션이 쓰는 설정을 그대로 맞춘 Mapper.
     *
     * <p>시간 모듈이 없으면 {@code OffsetDateTime} 직렬화 자체가 실패하고,
     * 기준 시간대를 안 맞추면 {@code sentAt} 이 {@code Z} 로 나가 계약(한국 시각)과 어긋난다.
     * 시험이 실제 응답과 다른 Mapper 를 쓰면 이 차이를 잡지 못한다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultTimeZone(TimeZone.getTimeZone(SlashTime.ZONE))
            .build();

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
        device.setPublicKey(원시표기(기기_키쌍));

        when(deviceRepository.findByPublicId(기기_공개ID)).thenReturn(Optional.of(device));
        when(deviceRepository.findById(기기_PK)).thenReturn(Optional.of(device));
        when(deviceRepository.findByActiveTokenHash(eq(Sha256.hex(기기_TOKEN)), any()))
                .thenReturn(Optional.of(device));

        session = 세션();
        handler.afterConnectionEstablished(session);
    }

    // ------------------------------------------------------------------
    // 접속 자격 (W1-02 기기 Token)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("기기 Token 없이 접속하면 프레임을 주고받기 전에 끊는다")
    void 토큰_없이는_접속하지_못한다() throws Exception {
        WebSocketSession 무자격 = 세션(null);

        handler.afterConnectionEstablished(무자격);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(무자격).sendMessage(captor.capture());
        assertThat(objectMapper.readTree(captor.getValue().getPayload()).path("code").asText())
                .isEqualTo("AUTHENTICATION_FAILED");
        verify(무자격).close(종료코드(4400));
    }

    @Test
    @DisplayName("만료됐거나 없는 Token 으로 접속하면 끊는다")
    void 모르는_토큰은_거부한다() throws Exception {
        WebSocketSession 남의_토큰 = 세션("모르는-토큰");

        handler.afterConnectionEstablished(남의_토큰);

        verify(남의_토큰).close(종료코드(4400));
    }

    @Test
    @DisplayName("Token 의 주인과 다른 deviceId 를 밝히면 끊는다")
    void 토큰과_다른_기기는_거부한다() throws Exception {
        UUID 남의_기기_공개ID = UUID.randomUUID();
        DevicesRecord 남의_기기 = new DevicesRecord();
        남의_기기.setId(999L);
        남의_기기.setPublicId(남의_기기_공개ID);
        남의_기기.setStatus(DeviceStatus.OFFLINE.name());
        when(deviceRepository.findByPublicId(남의_기기_공개ID)).thenReturn(Optional.of(남의_기기));

        보낸다(프레임("HELLO", "\"deviceId\":\"" + 남의_기기_공개ID + "\""));

        assertThat(오류코드()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(registry.holds(WsTarget.DEVICE, 999L)).isFalse();
    }

    // ------------------------------------------------------------------
    // 계약 준수
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CHALLENGE 에 계약이 요구하는 필드를 모두 담는다")
    void 도전값_형식() throws Exception {
        보낸다(hello());

        JsonNode challenge = 마지막_응답();
        assertThat(challenge.path("type").asText()).isEqualTo("CHALLENGE");
        assertThat(challenge.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(challenge.path("eventId").asText()).isNotBlank();
        assertThat(challenge.path("sentAt").asText()).endsWith("+09:00");
        assertThat(challenge.path("challengeId").asText()).isNotBlank();
        assertThat(challenge.path("nonce").asText()).isNotBlank();
        assertThat(challenge.path("expiresAt").asText()).endsWith("+09:00");
    }

    @Test
    @DisplayName("계약 문자열에 서명하면 인증에 성공한다")
    void 인증에_성공한다() throws Exception {
        인증한다();

        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isTrue();
        verify(deviceRepository).updateConnectionState(기기_PK, DeviceStatus.ONLINE);
    }

    @Test
    @DisplayName("nonce 원본 바이트에 서명하면 거부한다")
    void 원본_바이트_서명은_거부한다() throws Exception {
        보낸다(hello());
        JsonNode challenge = 마지막_응답();

        byte[] 원본 = Base64.getDecoder().decode(challenge.path("nonce").asText());
        보낸다(프레임("AUTH",
                "\"challengeId\":\"" + challenge.path("challengeId").asText() + "\"",
                "\"signature\":\"" + 서명바이트(원본) + "\""));

        assertThat(오류코드()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isFalse();
    }

    @Test
    @DisplayName("다른 challengeId 를 들고 오면 거부한다")
    void 다른_도전값은_거부한다() throws Exception {
        보낸다(hello());
        JsonNode challenge = 마지막_응답();

        String 서명 = 서명(AgentProtocol.challengeSigningPayload(
                UUID.fromString(challenge.path("challengeId").asText()),
                challenge.path("nonce").asText(),
                기기_공개ID));

        보낸다(프레임("AUTH",
                "\"challengeId\":\"" + UUID.randomUUID() + "\"",
                "\"signature\":\"" + 서명 + "\""));

        assertThat(오류코드()).isEqualTo("CHALLENGE_EXPIRED");
    }

    @Test
    @DisplayName("계약 버전이 다르면 나머지를 보지 않고 거부한다")
    void 계약_버전이_다르면_거부한다() throws Exception {
        보낸다("{\"schemaVersion\":\"2.0\",\"type\":\"HELLO\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"deviceId\":\"" + 기기_공개ID + "\"}");

        assertThat(오류코드()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
        verify(session).close(종료코드(4400));
    }

    @Test
    @DisplayName("오류는 끊기 전에 PROTOCOL_ERROR 로 알린다")
    void 오류를_알리고_끊는다() throws Exception {
        보낸다(프레임("HEARTBEAT"));

        JsonNode error = 마지막_응답();
        assertThat(error.path("type").asText()).isEqualTo("PROTOCOL_ERROR");
        assertThat(error.path("code").asText()).isEqualTo("INVALID_CONNECTION_STATE");
        assertThat(error.path("closeConnection").asBoolean()).isTrue();
        assertThat(error.path("schemaVersion").asText()).isEqualTo("1.0");
        verify(session).close(종료코드(4400));
    }

    @Test
    @DisplayName("등록되지 않은 기기는 인증 실패로 알린다")
    void 모르는_기기는_끊는다() throws Exception {
        when(deviceRepository.findByPublicId(any())).thenReturn(Optional.empty());

        보낸다(프레임("HELLO", "\"deviceId\":\"" + UUID.randomUUID() + "\""));

        assertThat(오류코드()).isEqualTo("AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("등록이 해제된 기기는 DEVICE_REVOKED 로 알린다")
    void 해제된_기기는_끊는다() throws Exception {
        DevicesRecord revoked = new DevicesRecord();
        revoked.setId(기기_PK);
        revoked.setPublicId(기기_공개ID);
        revoked.setStatus(DeviceStatus.REVOKED.name());
        when(deviceRepository.findByPublicId(기기_공개ID)).thenReturn(Optional.of(revoked));

        보낸다(hello());

        assertThat(오류코드()).isEqualTo("DEVICE_REVOKED");
    }

    // ------------------------------------------------------------------
    // 연결 수명
    // ------------------------------------------------------------------

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

        handler.afterConnectionClosed(옛_연결, new CloseStatus(4409, "CONNECTION_SUPERSEDED"));

        assertThat(registry.holds(WsTarget.DEVICE, 기기_PK)).isTrue();
        verify(deviceRepository, never()).updateConnectionState(기기_PK, DeviceStatus.OFFLINE);
    }

    @Test
    @DisplayName("Heartbeat 는 마지막 확인 시각을 갱신한다")
    void Heartbeat_를_기록한다() throws Exception {
        인증한다();

        보낸다(프레임("HEARTBEAT", "\"cpuPercent\":12.5", "\"memoryPercent\":40"));

        verify(deviceRepository).touchLastSeen(기기_PK);
    }

    @Test
    @DisplayName("배치가 내려버린 기기를 Heartbeat 로 되살린다")
    void Heartbeat_가_기기를_되살린다() throws Exception {
        인증한다();
        보낸다(프레임("READY", "\"supportedTaskTypes\":[\"FILE_SEARCH\"]"));

        DevicesRecord offline = new DevicesRecord();
        offline.setId(기기_PK);
        offline.setStatus(DeviceStatus.OFFLINE.name());
        when(deviceRepository.findById(기기_PK)).thenReturn(Optional.of(offline));

        보낸다(프레임("HEARTBEAT"));

        verify(deviceRepository, org.mockito.Mockito.times(2))
                .updateConnectionState(기기_PK, DeviceStatus.READY);
    }

    // ------------------------------------------------------------------
    // 전달 반영
    // ------------------------------------------------------------------

    @Test
    @DisplayName("READY 를 받으면 지원 작업을 저장하고 READY 로 올린다")
    void READY_를_반영한다() throws Exception {
        인증한다();

        보낸다(프레임("READY",
                "\"maxConcurrentTasks\":1",
                "\"supportedTaskTypes\":[\"FILE_SEARCH\",\"SYSTEM_STATUS\",\"없는유형\"]",
                "\"searchFolders\":[]",
                "\"projectWorkspaces\":[]"));

        verify(deviceCapabilityRepository).replaceAll(eq(기기_PK), any());
        verify(deviceRepository).updateConnectionState(기기_PK, DeviceStatus.READY);
    }

    @Test
    @DisplayName("RESULT 의 status 가 SUCCEEDED 면 전달을 마감한다")
    void 성공_결과를_반영한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.DISPATCHED);

        보낸다(프레임("RESULT",
                "\"dispatchId\":\"" + dispatchId + "\"",
                "\"status\":\"SUCCEEDED\"",
                "\"result\":{\"files\":[]}",
                "\"error\":null"));

        verify(agentDispatchRepository).complete(7L);
        verify(agentDispatchRepository, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("RESULT 의 status 가 FAILED 면 사유와 함께 실패로 마감한다")
    void 실패_결과를_반영한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.DISPATCHED);

        보낸다(프레임("RESULT",
                "\"dispatchId\":\"" + dispatchId + "\"",
                "\"status\":\"FAILED\"",
                "\"result\":null",
                "\"error\":{\"code\":\"SEARCH_FOLDER_NOT_FOUND\",\"message\":\"없음\",\"retryable\":false}"));

        verify(agentDispatchRepository).fail(7L, "SEARCH_FOLDER_NOT_FOUND");
    }

    @Test
    @DisplayName("ACK 를 받으면 전달을 수락 처리한다")
    void ACK_를_반영한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.DISPATCHED);

        보낸다(프레임("ACK", "\"dispatchId\":\"" + dispatchId + "\"", "\"accepted\":true"));

        verify(agentDispatchRepository).acknowledge(7L);
    }

    @Test
    @DisplayName("다른 기기의 dispatchId 는 반영하지 않는다")
    void 남의_전달은_건드리지_못한다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(999L, AgentDispatchStatus.DISPATCHED);

        보낸다(프레임("RESULT", "\"dispatchId\":\"" + dispatchId + "\"", "\"status\":\"SUCCEEDED\""));

        verify(agentDispatchRepository, never()).complete(anyLong());
        verify(agentDispatchRepository, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("이미 마감된 전달에는 결과를 다시 반영하지 않는다")
    void 마감된_전달은_다시_반영하지_않는다() throws Exception {
        인증한다();
        UUID dispatchId = 전달을_준비한다(기기_PK, AgentDispatchStatus.COMPLETED);

        보낸다(프레임("RESULT", "\"dispatchId\":\"" + dispatchId + "\"", "\"status\":\"SUCCEEDED\""));

        verify(agentDispatchRepository, never()).complete(anyLong());
    }

    @Test
    @DisplayName("PROGRESS 는 흘려보내고 연결을 유지한다")
    void PROGRESS_는_연결을_끊지_않는다() throws Exception {
        인증한다();

        보낸다(프레임("PROGRESS", "\"stage\":\"SEARCHING\"", "\"percent\":40"));

        verify(session, never()).close(any());
    }

    @Test
    @DisplayName("Agent 가 보낸 PROTOCOL_ERROR 로는 연결을 끊지 않는다")
    void Agent_오류는_연결을_유지한다() throws Exception {
        인증한다();

        보낸다(프레임("PROTOCOL_ERROR", "\"code\":\"INVALID_MESSAGE\"", "\"message\":\"bad\"",
                "\"relatedEventId\":null", "\"closeConnection\":false"));

        verify(session, never()).close(any());
    }

    @Test
    @DisplayName("모르는 프레임은 무시한다 — Agent 가 새 프레임을 먼저 배포해도 끊기지 않는다")
    void 모르는_프레임은_무시한다() throws Exception {
        인증한다();

        보낸다(프레임("미래의_프레임"));

        verify(session, never()).close(any());
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private void 인증한다() throws Exception {
        보낸다(hello());
        JsonNode challenge = 마지막_응답();

        String payload = AgentProtocol.challengeSigningPayload(
                UUID.fromString(challenge.path("challengeId").asText()),
                challenge.path("nonce").asText(),
                기기_공개ID);

        보낸다(프레임("AUTH",
                "\"challengeId\":\"" + challenge.path("challengeId").asText() + "\"",
                "\"signature\":\"" + 서명(payload) + "\""));
    }

    private String hello() {
        return 프레임("HELLO", "\"deviceId\":\"" + 기기_공개ID + "\"", "\"agentVersion\":\"0.1.0\"");
    }

    /** 공통 필드를 채운 프레임을 만든다. 하나라도 빠지면 서버가 거부하는 것이 정상이다. */
    private String 프레임(String type, String... fields) {
        List<String> parts = new ArrayList<>();
        parts.add("\"schemaVersion\":\"1.0\"");
        parts.add("\"type\":\"" + type + "\"");
        parts.add("\"eventId\":\"" + UUID.randomUUID() + "\"");
        parts.add("\"sentAt\":\"2026-08-06T11:00:00+09:00\"");
        parts.addAll(Arrays.asList(fields));
        return "{" + String.join(",", parts) + "}";
    }

    private void 보낸다(String frame) throws Exception {
        handler.handleMessage(session, new TextMessage(frame));
    }

    /** 마지막으로 소켓에 나간 프레임. */
    private JsonNode 마지막_응답() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        List<TextMessage> sent = captor.getAllValues();
        return objectMapper.readTree(sent.get(sent.size() - 1).getPayload());
    }

    private String 오류코드() throws Exception {
        JsonNode frame = 마지막_응답();
        assertThat(frame.path("type").asText()).isEqualTo("PROTOCOL_ERROR");
        return frame.path("code").asText();
    }

    private String 서명(String payload) throws Exception {
        return 서명바이트(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String 서명바이트(byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(기기_키쌍.getPrivate());
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String 원시표기(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        return Base64.getEncoder()
                .encodeToString(Arrays.copyOfRange(encoded, encoded.length - RAW_KEY_LENGTH, encoded.length));
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

    /**
     * 종료 코드만 확인하는 Mockito 인자 조건.
     *
     * <p>{@link CloseStatus} 는 사유 문자열까지 같아야 동등하다. 사유는 사람이 읽는 값이라
     * 문구가 바뀌었다고 시험이 깨지면 안 되므로 코드만 본다.
     */
    private static CloseStatus 종료코드(int code) {
        return argThat(status -> status != null && status.getCode() == code);
    }

    /** 유효한 기기 Token 으로 접속한 연결. */
    private static WebSocketSession 세션() {
        return 세션(기기_TOKEN);
    }

    private static WebSocketSession 세션(String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);

        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        when(session.getHandshakeHeaders()).thenReturn(headers);

        return session;
    }
}
