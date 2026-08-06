package com.likelion.slash.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.dispatch.AgentDispatchRepository;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 로컬 에이전트 WSS {@code /ws/agent}. (WBS W1-06)
 *
 * <p>연결 흐름
 *
 * <pre>
 *   Agent  HELLO      {deviceId}                 →
 *          ←  CHALLENGE {nonce}                     서버가 만든 1회용 도전값
 *   Agent  AUTH       {signature}                →  개인키로 nonce 에 서명
 *          (검증 성공 → devices.status = ONLINE, 소켓 등록)
 *   Agent  READY      {supportedTaskTypes}       →  devices.status = READY
 *   Agent  HEARTBEAT                             →  30초마다. last_seen_at 갱신
 *          ←  TASK      {dispatchId, ...}           다른 Pod 에서 발행된 것도 여기로 나간다
 *   Agent  ACK        {dispatchId, accepted}     →
 *   Agent  RESULT     {dispatchId, ok, error}    →
 * </pre>
 *
 * <p><b>인증은 이 핸들러가 직접 한다.</b> 접속 시점에는 아직 누구인지 모르므로 Spring Security 의
 * 인증 대상이 아니다. {@code /ws/**} 를 공개 경로로 열어 두고 프로토콜 안에서 검증한다.
 * 검증 전에는 소켓을 보관소에 넣지 않으므로, 인증되지 않은 연결로는 어떤 프레임도 나가지 않는다.
 *
 * <p><b>확인 필요</b> — 프레임의 필드 이름은 메시지 프로토콜 정의(3.4.2 · 3.6)를 읽고 맞춘 것이
 * 아니라 스키마와 담당 범위에서 유추한 것이다. slash-agent 와 대조해 확정해야 한다.
 * 라우팅 구조는 이름이 바뀌어도 영향을 받지 않는다.
 *
 * <p><b>남은 것</b>
 * <ul>
 *   <li>기기 Token 검증 — 발급이 W1-03 이라 아직 붙이지 않았다. 서명 검증만으로 통과한다.</li>
 *   <li>재연결 시 미완료 전달 재전송 — TASK 프레임 본문이 W1-04 에서 정해진다.</li>
 *   <li>ACK·RESULT 에 따른 Task 상태 전이 — W1-04. 여기서는 전달 원장만 갱신한다.</li>
 *   <li>핸드셰이크 시간 제한 — HELLO 를 보내지 않고 붙어만 있는 연결이 쌓이는 것을 막아야 한다.
 *       인증 전 연결은 보관소에 없어 프레임이 새지는 않지만 소켓 자원은 붙잡는다.</li>
 * </ul>
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    // 연결 상태. 순서를 건너뛴 프레임은 프로토콜 위반으로 끊는다.
    private static final String ATTR_STATE = "slash.state";
    private static final String ATTR_DEVICE_ID = "slash.deviceId";
    private static final String ATTR_CHALLENGE = "slash.challenge";

    /** 애플리케이션 정의 종료 코드. 4000~4999 는 WebSocket 규격이 애플리케이션에 열어 둔 범위다. */
    private static final CloseStatus CLOSE_AUTH_FAILED = new CloseStatus(4401, "AGENT_AUTH_FAILED");
    private static final CloseStatus CLOSE_PROTOCOL_VIOLATION = new CloseStatus(4400, "PROTOCOL_VIOLATION");
    private static final CloseStatus CLOSE_SUPERSEDED = new CloseStatus(4409, "CONNECTION_SUPERSEDED");

    private static final int CHALLENGE_BYTES = 32;

    private enum State {
        /** 접속 직후. HELLO 만 받는다. */
        AWAITING_HELLO,
        /** 도전값을 보냈다. AUTH 만 받는다. */
        AWAITING_AUTH,
        /** 서명 검증을 통과했다. */
        AUTHENTICATED
    }

    private final SecureRandom random = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final WsSessionRegistry registry;
    private final AgentSignatureVerifier signatureVerifier;
    private final DeviceRepository deviceRepository;
    private final DeviceCapabilityRepository deviceCapabilityRepository;
    private final AgentDispatchRepository agentDispatchRepository;

    public AgentWebSocketHandler(ObjectMapper objectMapper,
                                 WsSessionRegistry registry,
                                 AgentSignatureVerifier signatureVerifier,
                                 DeviceRepository deviceRepository,
                                 DeviceCapabilityRepository deviceCapabilityRepository,
                                 AgentDispatchRepository agentDispatchRepository) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.signatureVerifier = signatureVerifier;
        this.deviceRepository = deviceRepository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.agentDispatchRepository = agentDispatchRepository;
    }

    // ------------------------------------------------------------------
    // 연결 수명
    // ------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(ATTR_STATE, State.AWAITING_HELLO);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);
        if (deviceId == null) {
            return;
        }

        registry.unregister(WsTarget.DEVICE, deviceId, session.getId());

        // 재연결이 먼저 붙어 옛 연결을 밀어낸 경우에는 새 연결이 살아 있다.
        // 그때 OFFLINE 으로 내리면 방금 붙은 기기가 작업을 받지 못한다.
        if (!registry.holds(WsTarget.DEVICE, deviceId)) {
            deviceRepository.updateConnectionState(deviceId, DeviceStatus.OFFLINE);
        }

        log.debug("Agent 연결 종료 deviceId={} status={}", deviceId, status);
    }

    // ------------------------------------------------------------------
    // 프레임 처리
    // ------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return;
        }

        String type = frame.path("type").asText("");
        State state = (State) session.getAttributes().get(ATTR_STATE);

        switch (type) {
            case "HELLO" -> handleHello(session, frame, state);
            case "AUTH" -> handleAuth(session, frame, state);
            case "READY" -> handleReady(session, frame, state);
            case "HEARTBEAT" -> handleHeartbeat(session, state);
            case "ACK" -> handleAck(session, frame, state);
            case "RESULT" -> handleResult(session, frame, state);
            default ->
                // 모르는 프레임은 무시한다. Agent 가 새 프레임을 추가해도 옛 Pod 이 연결을 끊지 않도록
                // 하기 위해서다. 끊으면 배포 순서에 따라 전체 기기가 접속하지 못하는 상황이 생긴다.
                log.debug("알 수 없는 Agent 프레임 type={} sessionId={}", type, session.getId());
        }
    }

    /** 기기를 밝힌다. 아직 증명은 하지 않았으므로 도전값만 돌려준다. */
    private void handleHello(WebSocketSession session, JsonNode frame, State state) throws IOException {
        if (state != State.AWAITING_HELLO) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return;
        }

        Optional<DevicesRecord> device = parseUuid(frame.path("deviceId").asText(null))
                .flatMap(deviceRepository::findByPublicId);

        // 없는 기기와 해제된 기기를 구분해서 알리지 않는다. 식별자를 넣어 보며 등록 여부를
        // 알아내는 것을 막는다. (문서 DV-04)
        if (device.isEmpty() || DeviceStatus.REVOKED.name().equals(device.get().getStatus())) {
            close(session, CLOSE_AUTH_FAILED);
            return;
        }

        byte[] challenge = new byte[CHALLENGE_BYTES];
        random.nextBytes(challenge);

        session.getAttributes().put(ATTR_DEVICE_ID, device.get().getId());
        session.getAttributes().put(ATTR_CHALLENGE, challenge);
        session.getAttributes().put(ATTR_STATE, State.AWAITING_AUTH);

        send(session, Map.of(
                "type", "CHALLENGE",
                "nonce", Base64.getEncoder().encodeToString(challenge),
                "issuedAt", SlashTime.now().toString()));
    }

    /** 도전값 서명을 검증한다. 통과하면 이때부터 이 소켓으로 프레임이 나갈 수 있다. */
    private void handleAuth(WebSocketSession session, JsonNode frame, State state) throws IOException {
        if (state != State.AWAITING_AUTH) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return;
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);
        byte[] challenge = (byte[]) session.getAttributes().get(ATTR_CHALLENGE);

        Optional<DevicesRecord> device = deviceRepository.findById(deviceId);
        if (device.isEmpty()
                || !signatureVerifier.verify(
                        device.get().getPublicKey(), challenge, frame.path("signature").asText(""))) {
            close(session, CLOSE_AUTH_FAILED);
            return;
        }

        // 도전값은 1회용이다. 남겨 두면 같은 연결에서 재사용될 여지가 생긴다.
        session.getAttributes().remove(ATTR_CHALLENGE);
        session.getAttributes().put(ATTR_STATE, State.AUTHENTICATED);

        // 같은 기기의 옛 연결을 끊는다. PC 가 절전에서 깨어난 직후처럼 옛 소켓이 아직
        // 살아 있는 것처럼 보이는 구간이 있는데, 그대로 두면 작업이 죽은 소켓으로 나간다.
        List<WebSocketSession> superseded = registry.register(WsTarget.DEVICE, deviceId, session);
        superseded.forEach(previous -> close(previous, CLOSE_SUPERSEDED));

        deviceRepository.updateConnectionState(deviceId, DeviceStatus.ONLINE);

        log.info("Agent 인증 성공 deviceId={} 밀어낸연결={}", deviceId, superseded.size());
    }

    /** 실행 가능한 작업 유형을 보고받는다. 여기까지 와야 작업을 받을 수 있다. */
    private void handleReady(WebSocketSession session, JsonNode frame, State state) throws IOException {
        if (state != State.AUTHENTICATED) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return;
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);

        List<TaskType> reported = new ArrayList<>();
        for (JsonNode node : frame.path("supportedTaskTypes")) {
            // 계약에 없는 값은 버린다. Agent 가 새 유형을 먼저 배포해도 READY 전체가 실패하면 안 된다.
            parseTaskType(node.asText()).ifPresent(reported::add);
        }

        deviceCapabilityRepository.replaceAll(deviceId, reported);
        deviceRepository.updateConnectionState(deviceId, DeviceStatus.READY);

        log.info("Agent READY deviceId={} 지원작업={}", deviceId, reported);
    }

    private void handleHeartbeat(WebSocketSession session, State state) throws IOException {
        if (state != State.AUTHENTICATED) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return;
        }
        deviceRepository.touchLastSeen((Long) session.getAttributes().get(ATTR_DEVICE_ID));
    }

    /** Agent 가 작업을 받아들였거나 거부했다. */
    private void handleAck(WebSocketSession session, JsonNode frame, State state) throws IOException {
        Optional<AgentDispatchesRecord> dispatch = findOwnedDispatch(session, frame, state);
        if (dispatch.isEmpty()) {
            return;
        }

        long id = dispatch.get().getId();

        if (frame.path("accepted").asBoolean(false)) {
            agentDispatchRepository.acknowledge(id);
        } else {
            agentDispatchRepository.fail(id, frame.path("reasonCode").asText(null));
        }
    }

    /** 실행 결과. 전달을 마감해야 그 기기가 다음 작업을 받을 수 있다. */
    private void handleResult(WebSocketSession session, JsonNode frame, State state) throws IOException {
        Optional<AgentDispatchesRecord> dispatch = findOwnedDispatch(session, frame, state);
        if (dispatch.isEmpty()) {
            return;
        }

        long id = dispatch.get().getId();

        if (frame.path("ok").asBoolean(false)) {
            agentDispatchRepository.complete(id);
        } else {
            agentDispatchRepository.fail(id, frame.path("error").path("code").asText(null));
        }

        // 결과 본문을 tasks 에 반영하고 상태를 전이시키는 것은 W1-04 다.
        // 여기서 하지 않는 이유는 Task 상태 기계가 아직 없기 때문이다.
    }

    /**
     * 프레임이 가리키는 전달을 찾되, <b>그것이 이 연결의 기기 것인지 확인한다.</b>
     *
     * <p>확인하지 않으면 A 사용자의 PC 가 남의 {@code dispatchId} 를 보내 그 작업을 실패로
     * 마감시킬 수 있다. 소유권 격리(DV-04) 위반이다.
     */
    private Optional<AgentDispatchesRecord> findOwnedDispatch(
            WebSocketSession session, JsonNode frame, State state) throws IOException {

        if (state != State.AUTHENTICATED) {
            close(session, CLOSE_PROTOCOL_VIOLATION);
            return Optional.empty();
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);

        Optional<AgentDispatchesRecord> dispatch = parseUuid(frame.path("dispatchId").asText(null))
                .flatMap(agentDispatchRepository::findByPublicId)
                .filter(record -> record.getDeviceId() == deviceId);

        if (dispatch.isEmpty()) {
            // 이미 만료돼 마감된 전달일 수도 있고, 남의 것을 보낸 것일 수도 있다.
            // 어느 쪽이든 반영하지 않는다. 끊지는 않는다. (만료는 정상적으로 일어난다)
            log.warn("처리할 수 없는 전달 참조 deviceId={} dispatchId={}",
                    deviceId, frame.path("dispatchId").asText(null));
            return Optional.empty();
        }

        // 이미 마감된 전달에 다시 반영하지 않는다. 같은 ACK·RESULT 를 두 번 받아도
        // 한 번만 반영된다는 계약을 Repository 의 조건절과 함께 이중으로 지킨다.
        if (!AgentDispatchStatus.valueOf(dispatch.get().getStatus()).isActive()) {
            return Optional.empty();
        }

        return dispatch;
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private void send(WebSocketSession session, Map<String, Object> frame) throws IOException {
        // 순서를 유지해 로그에서 읽기 좋게 둔다.
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(new LinkedHashMap<>(frame))));
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("소켓 종료 실패 sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<TaskType> parseTaskType(String value) {
        try {
            return Optional.of(TaskType.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
