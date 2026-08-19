package com.likelion.slash.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.device.DeviceSearchFolderRepository;
import com.likelion.slash.device.SearchFolder;
import com.likelion.slash.dispatch.AgentDispatchRepository;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import com.likelion.slash.task.TaskService;
import com.likelion.slash.task.TaskStateWriter;
import com.likelion.slash.ws.dto.ChallengeFrame;
import com.likelion.slash.ws.dto.ProtocolErrorFrame;
import com.likelion.slash.ws.dto.ResultAckFrame;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
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
 *   Agent  HELLO      {deviceId, agentVersion, os, ...}   →
 *          ←  CHALLENGE {challengeId, nonce, expiresAt}      1회용 도전값
 *   Agent  AUTH       {challengeId, signature}           →  challengeId:nonce:deviceId 에 서명
 *          (검증 성공 → devices.status = ONLINE, 소켓 등록)
 *   Agent  READY      {supportedTaskTypes, ...}          →  devices.status = READY
 *   Agent  HEARTBEAT                                     →  30초마다. last_seen_at 갱신
 *          ←  TASK      {dispatchId, taskType, ...}         다른 Pod 에서 발행된 것도 여기로 나간다
 *   Agent  ACK        {dispatchId, accepted}             →  tasks → RUNNING
 *   Agent  PROGRESS   {stage, percent}                   →
 *   Agent  RESULT     {dispatchId, status, result}       →  tasks → SUCCEEDED·FAILED
 *          ←  RESULT_ACK {persisted, taskStatus}            받아야 Agent 가 결과 캐시를 비운다
 * </pre>
 *
 * <p><b>계약은 slash-agent 저장소가 원본이다.</b> 2026-08-12 확인 기준으로 Python 으로 재작성돼
 * {@code slash-python-agent/src/slash_agent/protocol.py}(봉투·상수·서명 대상)와
 * {@code agent.py} 의 {@code _build_ready()}(READY 구성)에 있다. 주고받는 JSON 예시는
 * {@code docs/MESSAGE_GUIDE.md} 다. 모든 메시지에 {@code schemaVersion}·{@code eventId}·
 * {@code sentAt} 이 필수이고 하나라도 빠지면 메시지 전체가 거부된다.
 * 값과 규칙은 {@link AgentProtocol} 에 모아 두었다.
 *
 * <p><b>인증은 이 핸들러가 직접 한다.</b> 접속 시점에는 아직 누구인지 모르므로 Spring Security 의
 * 인증 대상이 아니다. {@code /ws/**} 를 공개 경로로 열어 두고 프로토콜 안에서 검증한다.
 * 검증 전에는 소켓을 보관소에 넣지 않으므로, 인증되지 않은 연결로는 어떤 프레임도 나가지 않는다.
 *
 * <p><b>두 겹으로 확인한다.</b> 접속 시점의 기기 Token(W1-02)과 연결마다 새로 받는 서명이다.
 * Token 은 훔칠 수 있지만 개인키는 PC 밖으로 나오지 않고, 서명만으로는 Token 이 해지됐는지
 * 알 수 없다. 둘 중 하나만으로는 부족해서 둘 다 본다.
 *
 * <p><b>남은 것</b>
 * <ul>
 *   <li>READY 의 {@code projectWorkspaces} — 저장할 표가 없다. CODE_ANALYSIS 의
 *       {@code workspaceId} 가 {@code searchFolderId} 와 같은 자리라 P1 에서 같은 방식으로 만든다.</li>
 *   <li>핸드셰이크 시간 제한 — HELLO 없이 붙어만 있는 연결이 쌓이는 것을 막아야 한다.</li>
 *   <li>PROGRESS 를 화면에 전달하는 것 — 지금은 기록만 남기고 흘려보낸다.</li>
 * </ul>
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private static final String ATTR_STATE = "slash.state";
    private static final String ATTR_DEVICE_ID = "slash.deviceId";
    /** 접속 Token 이 가리키는 기기. HELLO 의 deviceId 가 이것과 같아야 한다. */
    private static final String ATTR_TOKEN_DEVICE_ID = "slash.tokenDeviceId";
    private static final String ATTR_DEVICE_PUBLIC_ID = "slash.devicePublicId";
    private static final String ATTR_CHALLENGE_ID = "slash.challengeId";
    private static final String ATTR_CHALLENGE_NONCE = "slash.challengeNonce";
    private static final String ATTR_CHALLENGE_EXPIRES = "slash.challengeExpiresAt";
    private static final String ATTR_READY_REPORTED = "slash.readyReported";

    /** 애플리케이션 정의 종료 코드. 참조 구현이 PROTOCOL_ERROR 와 함께 쓰는 값과 맞춘다. */

    /** 같은 기기가 다시 접속해 옛 연결을 밀어낼 때. 오류가 아니므로 별도 코드를 쓴다. */
    private static final CloseStatus CLOSE_SUPERSEDED = new CloseStatus(4409, "CONNECTION_SUPERSEDED");

    private static final int NONCE_BYTES = 32;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_QUERY_PARAMETER = "deviceToken";

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
    private final DeviceSearchFolderRepository deviceSearchFolderRepository;
    private final AgentDispatchRepository agentDispatchRepository;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;

    /**
     * ACK 를 받은 뒤 실행에 주는 기한.
     *
     * <p>전달 기한({@code slash.dispatch.ttl})과 다른 값이다. 전달은 켜져 있는 기기에만 만들어
     * 60초면 충분하지만, 실행은 작업에 따라 그보다 오래 걸린다.
     */
    private final Duration executionTtl;

    public AgentWebSocketHandler(ObjectMapper objectMapper,
                                 WsSessionRegistry registry,
                                 AgentSignatureVerifier signatureVerifier,
                                 DeviceRepository deviceRepository,
                                 DeviceCapabilityRepository deviceCapabilityRepository,
                                 DeviceSearchFolderRepository deviceSearchFolderRepository,
                                 AgentDispatchRepository agentDispatchRepository,
                                 TaskService taskService,
                                 TaskRepository taskRepository,
                                 TaskStateWriter stateWriter,
                                 @Value("${slash.dispatch.execution-ttl}") Duration executionTtl) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.signatureVerifier = signatureVerifier;
        this.deviceRepository = deviceRepository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.deviceSearchFolderRepository = deviceSearchFolderRepository;
        this.agentDispatchRepository = agentDispatchRepository;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.executionTtl = executionTtl;
    }

    // ------------------------------------------------------------------
    // 연결 수명
    // ------------------------------------------------------------------

    /**
     * 접속 시점에 기기 Token 을 먼저 확인한다. (메시지 스펙 §8.1 · W1-02)
     *
     * <p>Token 이 없거나 만료됐으면 프레임을 한 번도 주고받지 않고 끊는다.
     * 뒤이은 서명 검증은 <b>이 연결이 정말 그 기기인지</b>를 증명하는 별개의 단계다.
     * Token 은 훔칠 수 있지만 개인키는 PC 밖으로 나오지 않으므로 둘을 함께 본다.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Optional<String> token = deviceToken(session);
        Optional<DevicesRecord> device = token
                .flatMap(value -> deviceRepository.findByActiveTokenHash(Sha256.hex(value), SlashTime.now()));

        if (device.isEmpty()) {
            // 거부하는 이유를 가려서 알려준다. 해제된 기기에 AUTHENTICATION_FAILED 를 주면
            // Agent 는 그것을 Token 문제로 보고 재접속을 반복한다 — 등록을 해제한 목적이
            // 재연결을 영구히 막는 것인데 그 신호가 전달되지 않는다. (이슈 #26)
            boolean revoked = revoked(token);
            fail(session,
                    revoked ? AgentProtocol.ERROR_DEVICE_REVOKED : AgentProtocol.ERROR_AUTHENTICATION_FAILED,
                    revoked ? "device revoked" : "invalid or missing device token",
                    null, true);
            return;
        }

        session.getAttributes().put(ATTR_TOKEN_DEVICE_ID, device.get().getId());
        session.getAttributes().put(ATTR_STATE, State.AWAITING_HELLO);
    }

    /**
     * 접속 요청에서 기기 Token 을 꺼낸다.
     *
     * <p>헤더를 우선하고 질의 문자열도 받는다. 브라우저 WebSocket API 로는 헤더를 붙일 수 없어
     * 참조 구현도 둘 다 받는다. 다만 <b>질의 문자열은 중간 경로의 접근 로그에 남는다.</b>
     * Agent 는 헤더를 쓰는 것이 맞고, 질의 문자열은 시험·디버깅용으로 남겨 둔다.
     */
    private Optional<String> deviceToken(WebSocketSession session) {
        List<String> authorization = session.getHandshakeHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isEmpty()) {
            String value = authorization.get(0);
            if (value != null && value.startsWith(BEARER_PREFIX)) {
                return Optional.of(value.substring(BEARER_PREFIX.length()).trim());
            }
        }

        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return Optional.empty();
        }

        return Arrays.stream(uri.getQuery().split("&"))
                .filter(parameter -> parameter.startsWith(TOKEN_QUERY_PARAMETER + "="))
                .map(parameter -> URLDecoder.decode(
                        parameter.substring(TOKEN_QUERY_PARAMETER.length() + 1), StandardCharsets.UTF_8))
                .filter(token -> !token.isBlank())
                .findFirst();
    }

    /**
     * 이 Token 의 주인이 등록 해제된 기기인지 본다.
     *
     * <p><b>접속을 거부하기로 정한 뒤에만 부른다.</b> 정상 접속은 {@code findByActiveTokenHash}
     * 한 번으로 끝나고, 질의가 하나 느는 것은 거부하는 경우뿐이다.
     */
    private boolean revoked(Optional<String> token) {
        return token
                .flatMap(value -> deviceRepository.findByTokenHash(Sha256.hex(value)))
                .map(device -> DeviceStatus.REVOKED.name().equals(device.getStatus()))
                .orElse(false);
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
            fail(session, AgentProtocol.ERROR_INVALID_MESSAGE, "malformed JSON", null, true);
            return;
        }

        UUID eventId = parseUuid(frame.path("eventId").asText(null)).orElse(null);

        // 계약 버전이 다르면 나머지 필드를 신뢰할 수 없다. 먼저 거른다.
        if (!AgentProtocol.SCHEMA_VERSION.equals(frame.path("schemaVersion").asText(null))) {
            fail(session, AgentProtocol.ERROR_UNSUPPORTED_SCHEMA_VERSION,
                    "expected schemaVersion " + AgentProtocol.SCHEMA_VERSION, eventId, true);
            return;
        }

        String type = frame.path("type").asText("");
        State state = (State) session.getAttributes().get(ATTR_STATE);

        switch (type) {
            case AgentProtocol.TYPE_HELLO -> handleHello(session, frame, state, eventId);
            case AgentProtocol.TYPE_AUTH -> handleAuth(session, frame, state, eventId);
            case AgentProtocol.TYPE_READY -> handleReady(session, frame, state, eventId);
            case AgentProtocol.TYPE_HEARTBEAT -> handleHeartbeat(session, state, eventId);
            case AgentProtocol.TYPE_ACK -> handleAck(session, frame, state, eventId);
            case AgentProtocol.TYPE_RESULT -> handleResult(session, frame, state, eventId);
            case AgentProtocol.TYPE_PROGRESS -> handleProgress(session, frame, state);
            case AgentProtocol.TYPE_PROTOCOL_ERROR ->
                // Agent 가 우리 메시지를 거부했다. 연결은 유지하고 기록만 남긴다.
                // 여기서 끊으면 원인을 남길 기회 없이 재접속만 반복된다.
                log.warn("Agent 가 프레임을 거부했다 code={} message={}",
                        frame.path("code").asText(null), frame.path("message").asText(null));
            default ->
                // 모르는 프레임은 무시한다. Agent 가 새 프레임을 추가해도 옛 Pod 이 연결을 끊지 않도록
                // 하기 위해서다. 끊으면 배포 순서에 따라 전체 기기가 접속하지 못하는 상황이 생긴다.
                log.debug("알 수 없는 Agent 프레임 type={} sessionId={}", type, session.getId());
        }
    }

    /** 기기를 밝힌다. 아직 증명은 하지 않았으므로 도전값만 돌려준다. */
    private void handleHello(WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {
        if (state != State.AWAITING_HELLO) {
            fail(session, AgentProtocol.ERROR_INVALID_CONNECTION_STATE, "HELLO in wrong state", eventId, true);
            return;
        }

        Optional<UUID> devicePublicId = parseUuid(frame.path("deviceId").asText(null));
        Optional<DevicesRecord> device = devicePublicId.flatMap(deviceRepository::findByPublicId);

        if (device.isEmpty()) {
            // 없는 기기와 잘못된 형식을 구분해서 알리지 않는다. 식별자를 넣어 보며
            // 등록 여부를 알아내는 것을 막는다. (문서 DV-04)
            fail(session, AgentProtocol.ERROR_AUTHENTICATION_FAILED, "unknown device", eventId, true);
            return;
        }

        // 접속에 쓴 Token 의 주인과 스스로 밝힌 기기가 다르다.
        // Token 을 훔친 쪽이 남의 기기 행세를 하는 경우이므로 여기서 끊는다.
        if (!device.get().getId().equals(session.getAttributes().get(ATTR_TOKEN_DEVICE_ID))) {
            fail(session, AgentProtocol.ERROR_AUTHENTICATION_FAILED, "deviceId mismatch", eventId, true);
            return;
        }
        if (DeviceStatus.REVOKED.name().equals(device.get().getStatus())) {
            fail(session, AgentProtocol.ERROR_DEVICE_REVOKED, "device revoked", eventId, true);
            return;
        }

        byte[] nonceBytes = new byte[NONCE_BYTES];
        random.nextBytes(nonceBytes);

        UUID challengeId = UUID.randomUUID();
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        OffsetDateTime expiresAt = SlashTime.now().plusSeconds(AgentProtocol.CHALLENGE_TTL_SECONDS);

        session.getAttributes().put(ATTR_DEVICE_ID, device.get().getId());
        session.getAttributes().put(ATTR_DEVICE_PUBLIC_ID, device.get().getPublicId());
        session.getAttributes().put(ATTR_CHALLENGE_ID, challengeId);
        session.getAttributes().put(ATTR_CHALLENGE_NONCE, nonce);
        session.getAttributes().put(ATTR_CHALLENGE_EXPIRES, expiresAt);
        session.getAttributes().put(ATTR_STATE, State.AWAITING_AUTH);

        send(session, ChallengeFrame.of(challengeId, nonce, expiresAt));
    }

    /** 도전값 서명을 검증한다. 통과하면 이때부터 이 소켓으로 프레임이 나갈 수 있다. */
    private void handleAuth(WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {
        if (state != State.AWAITING_AUTH) {
            fail(session, AgentProtocol.ERROR_INVALID_CONNECTION_STATE, "AUTH before HELLO", eventId, true);
            return;
        }

        UUID challengeId = (UUID) session.getAttributes().get(ATTR_CHALLENGE_ID);
        String nonce = (String) session.getAttributes().get(ATTR_CHALLENGE_NONCE);
        OffsetDateTime expiresAt = (OffsetDateTime) session.getAttributes().get(ATTR_CHALLENGE_EXPIRES);

        // 다른 도전값에 대한 서명을 들고 오는 경우와 시간이 지난 경우를 함께 거른다.
        boolean matchesChallenge = challengeId.equals(parseUuid(frame.path("challengeId").asText(null)).orElse(null));
        if (!matchesChallenge || expiresAt.isBefore(SlashTime.now())) {
            fail(session, AgentProtocol.ERROR_CHALLENGE_EXPIRED, "no matching challenge", eventId, true);
            return;
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);
        UUID devicePublicId = (UUID) session.getAttributes().get(ATTR_DEVICE_PUBLIC_ID);

        Optional<DevicesRecord> device = deviceRepository.findById(deviceId);
        String payload = AgentProtocol.challengeSigningPayload(challengeId, nonce, devicePublicId);

        if (device.isEmpty()
                || !signatureVerifier.verify(
                        device.get().getPublicKey(), payload, frame.path("signature").asText(""))) {
            fail(session, AgentProtocol.ERROR_AUTHENTICATION_FAILED, "signature verification failed", eventId, true);
            return;
        }

        // 도전값은 1회용이다. 남겨 두면 같은 연결에서 재사용될 여지가 생긴다.
        session.getAttributes().remove(ATTR_CHALLENGE_ID);
        session.getAttributes().remove(ATTR_CHALLENGE_NONCE);
        session.getAttributes().remove(ATTR_CHALLENGE_EXPIRES);
        session.getAttributes().put(ATTR_STATE, State.AUTHENTICATED);

        // 같은 기기의 옛 연결을 끊는다. PC 가 절전에서 깨어난 직후처럼 옛 소켓이 아직
        // 살아 있는 것처럼 보이는 구간이 있는데, 그대로 두면 작업이 죽은 소켓으로 나간다.
        List<WebSocketSession> superseded = registry.register(WsTarget.DEVICE, deviceId, session);
        superseded.forEach(previous -> close(previous, CLOSE_SUPERSEDED));

        deviceRepository.updateConnectionState(deviceId, DeviceStatus.ONLINE);

        log.info("Agent 인증 성공 deviceId={} 밀어낸연결={}", deviceId, superseded.size());
    }

    /** 실행 가능한 작업 유형을 보고받는다. 여기까지 와야 작업을 받을 수 있다. */
    private void handleReady(WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {
        if (state != State.AUTHENTICATED) {
            fail(session, AgentProtocol.ERROR_INVALID_CONNECTION_STATE, "READY before AUTH", eventId, true);
            return;
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);

        List<TaskType> reported = new ArrayList<>();
        for (JsonNode node : frame.path("supportedTaskTypes")) {
            // 계약에 없는 값은 버린다. Agent 가 새 유형을 먼저 배포해도 READY 전체가 실패하면 안 된다.
            parseTaskType(node.asText()).ifPresent(reported::add);
        }

        List<SearchFolder> searchFolders = parseSearchFolders(frame.path("searchFolders"));

        deviceCapabilityRepository.replaceAll(deviceId, reported);
        deviceSearchFolderRepository.replaceAll(deviceId, searchFolders);
        deviceRepository.updateConnectionState(deviceId, DeviceStatus.READY);
        session.getAttributes().put(ATTR_READY_REPORTED, Boolean.TRUE);

        // projectWorkspaces·maxConcurrentTasks 는 저장할 표가 없어 아직 버린다.
        // CODE_ANALYSIS 의 workspaceId 가 searchFolderId 와 같은 자리라 P1 에서 같은 방식으로 만든다.
        log.info("Agent READY deviceId={} 지원작업={} 검색폴더={}개 동시작업={}",
                deviceId, reported, searchFolders.size(),
                frame.path("maxConcurrentTasks").asInt(0));

        // PC 가 꺼져 있는 동안 접수된 작업을 이제 내보낸다. (WBS W1-04)
        //
        // 여기서 실패해도 READY 자체는 성공으로 둔다. 기기는 이미 작업을 받을 수 있는 상태이고,
        // 밀린 작업은 다음 연결이나 재발행 스윕이 다시 집어 간다. 연결 수립을 실패시킬 일이 아니다.
        try {
            taskService.dispatchWaiting(deviceId);
        } catch (Exception e) {
            log.warn("대기 작업 전달 실패 deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * READY 의 {@code searchFolders} 를 읽는다.
     *
     * <p>계약은 slash-agent 의 {@code file_index.py} 가 원본이다. <b>실제 경로는 오지 않는다</b> —
     * Agent 가 자기만 들고 있고 서버에는 식별자·표시 이름·색인 상태만 보낸다.
     *
     * <p>모양이 어긋난 항목은 {@link SearchFolder#isStorable()} 이 저장 단계에서 거른다.
     * 여기서 끊지 않는 이유는 {@code supportedTaskTypes} 와 같다 — Agent 가 새 필드를 먼저
     * 배포해도 READY 전체가 실패하면 안 된다.
     */
    private List<SearchFolder> parseSearchFolders(JsonNode reported) {
        List<SearchFolder> folders = new ArrayList<>();
        for (JsonNode node : reported) {
            folders.add(new SearchFolder(
                    node.path("searchFolderId").asText(null),
                    node.path("displayName").asText(null),
                    node.path("indexStatus").asText(null)));
        }
        return folders;
    }

    private void handleHeartbeat(WebSocketSession session, State state, UUID eventId) throws IOException {
        if (state != State.AUTHENTICATED) {
            fail(session, AgentProtocol.ERROR_INVALID_CONNECTION_STATE, "HEARTBEAT before AUTH", eventId, true);
            return;
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);
        deviceRepository.touchLastSeen(deviceId);

        // Heartbeat 만료 배치가 OFFLINE 으로 내렸는데 연결은 살아 있는 경우가 있다.
        // 그대로 두면 붙어 있는 기기가 영영 작업을 받지 못한다. 보고를 마친 기기만 되살린다.
        if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_READY_REPORTED))
                && deviceRepository.findById(deviceId)
                        .map(device -> DeviceStatus.OFFLINE.name().equals(device.getStatus()))
                        .orElse(false)) {
            deviceRepository.updateConnectionState(deviceId, DeviceStatus.READY);
            log.info("Heartbeat 로 기기를 되살렸다 deviceId={}", deviceId);
        }
    }

    /** 실행 중 진행 상황. 화면 표시는 W1-04 에서 붙인다. 여기서는 흘려보낸다. */
    private void handleProgress(WebSocketSession session, JsonNode frame, State state) {
        if (state != State.AUTHENTICATED) {
            return;
        }
        log.debug("Agent PROGRESS dispatchId={} stage={} percent={}",
                frame.path("dispatchId").asText(null),
                frame.path("stage").asText(null),
                frame.path("percent").asInt(0));
    }

    /**
     * Agent 가 작업을 받아들였거나 거부했다.
     *
     * <p>전달 원장과 {@code tasks} 를 함께 옮긴다. 원장만 고치면 화면은 접수 직후 상태에 멈춘
     * 채로 남는다 — 사용자에게는 아무 일도 일어나지 않은 것으로 보인다.
     */
    private void handleAck(WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {
        Optional<AgentDispatchesRecord> dispatch = findOwnedDispatch(session, frame, state, eventId);
        if (dispatch.isEmpty() || !isActive(dispatch.get())) {
            return;
        }

        long id = dispatch.get().getId();
        long taskId = dispatch.get().getTaskId();

        if (frame.path("accepted").asBoolean(false)) {
            // 전달 기한(60초)은 여기서 끝난다. 이제부터는 실행 기한이다 —
            // 그대로 두면 오래 걸리는 작업이 실행 도중에 만료 스윕에 걸린다.
            agentDispatchRepository.acknowledge(id, SlashTime.now().plus(executionTtl));
            stateWriter.move(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, null, "PC 가 작업을 시작했습니다.");
            return;
        }

        // 거부. 사유는 Agent 가 보낸 것을 쓰되 계약에 있는 값만 받는다.
        ErrorCode reason = ErrorCode.fromAgentReason(
                frame.path("reasonCode").asText(null), ErrorCode.AGENT_REJECTED);

        agentDispatchRepository.fail(id, reason.name());
        stateWriter.failFromWorker(taskId, reason, reason.defaultMessage());
    }

    /**
     * 실행 결과. 전달을 마감해야 그 기기가 다음 작업을 받을 수 있다.
     *
     * <p>성공 여부는 {@code status} 문자열이 유일한 기준이다. 계약에 boolean 판정 필드는 없다.
     */
    private void handleResult(WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {
        Optional<AgentDispatchesRecord> owned = findOwnedDispatch(session, frame, state, eventId);
        if (owned.isEmpty()) {
            return;
        }

        AgentDispatchesRecord dispatch = owned.get();
        long id = dispatch.getId();
        long taskId = dispatch.getTaskId();

        // 이미 마감된 전달이다. 다시 반영하지는 않지만 RESULT_ACK 는 돌려줘야 한다.
        // 첫 RESULT_ACK 가 유실된 경우가 여기로 오는데, 아무 응답도 하지 않으면 Agent 는
        // 결과 캐시를 비울 방법이 없어 재연결마다 같은 결과를 영영 다시 보낸다.
        if (!isActive(dispatch)) {
            log.debug("이미 마감된 전달에 RESULT 가 다시 왔다 dispatchId={}", dispatch.getPublicId());
            sendResultAck(session, taskId, dispatch.getPublicId(), true);
            return;
        }

        boolean succeeded = AgentProtocol.RESULT_SUCCEEDED.equals(frame.path("status").asText(null));

        boolean persisted;
        if (succeeded) {
            agentDispatchRepository.complete(id);
            persisted = persistResult(taskId, frame);
        } else {
            ErrorCode reason = ErrorCode.fromAgentReason(
                    frame.path("error").path("code").asText(null), ErrorCode.AGENT_TASK_FAILED);

            agentDispatchRepository.fail(id, reason.name());
            persisted = stateWriter.failFromWorker(taskId, reason, agentMessage(frame, reason));
        }

        sendResultAck(session, taskId, dispatch.getPublicId(), persisted);
    }

    /**
     * 결과 본문을 {@code tasks} 에 저장한다.
     *
     * <p><b>상한을 넘는 결과에 연결을 잃지 않는다.</b> {@code ck_tasks_result_size}(64KB)를 넘으면
     * DB 가 거부하는데, 예외를 그대로 두면 프레임 처리 밖으로 나가 연결이 끊긴다. 작업은 어떤
     * 상태로도 마감되지 않은 채 남아 사용자에게는 무한 대기로 보인다. 결과를 못 담더라도
     * 마감은 시키는 편이 낫다.
     *
     * @return 결과를 실제로 담았는지. 이미 마감된 작업이거나 상한을 넘겼으면 거짓이다.
     */
    private boolean persistResult(long taskId, JsonNode frame) {
        JsonNode result = frame.path("result");
        JSONB body = result.isMissingNode() || result.isNull() ? null : JSONB.valueOf(result.toString());

        try {
            return stateWriter.succeed(taskId, body, "작업을 마쳤습니다.");

        } catch (DataAccessException e) {
            log.warn("결과를 저장하지 못해 실패로 마감한다 taskId={}: {}", taskId, e.getMessage());
            stateWriter.failFromWorker(taskId, ErrorCode.AGENT_TASK_FAILED,
                    "결과가 너무 커서 저장하지 못했습니다.");

            // 마감은 했지만 결과를 담지는 못했다. persisted 는 담았는지를 뜻하므로 거짓이다.
            // Agent 가 다시 보내더라도 그때는 전달이 이미 마감돼 있어 RESULT_ACK 로 정리된다.
            return false;
        }
    }

    /** 실패 사유 문구. Agent 가 보낸 설명을 쓰되 없으면 코드의 기본 문구로 대신한다. */
    private String agentMessage(JsonNode frame, ErrorCode reason) {
        String message = frame.path("error").path("message").asText(null);
        return message == null || message.isBlank() ? reason.defaultMessage() : message;
    }

    /**
     * 결과를 받았음을 Agent 에게 알린다.
     *
     * <p>이것을 보내지 않으면 Agent 는 결과 캐시를 비우지 못해 재연결할 때마다 같은 결과를
     * 다시 보낸다. 중복 반영은 {@link #isActive} 가 막지만 프레임이 계속 오가는 것은 그대로다.
     *
     * <p>보내지 못해도 반영은 이미 끝나 있다. 여기서 예외를 올리면 방금 마감한 작업의 연결까지
     * 끊게 되므로 기록만 남긴다. 못 보낸 것은 Agent 가 다시 보낼 때 정리된다.
     */
    private void sendResultAck(WebSocketSession session, long taskId, UUID dispatchPublicId, boolean persisted) {
        Optional<TasksRecord> task = taskRepository.findById(taskId);
        if (task.isEmpty()) {
            log.warn("RESULT_ACK 를 보낼 작업을 찾지 못했다 taskId={}", taskId);
            return;
        }

        try {
            send(session, ResultAckFrame.of(
                    task.get().getPublicId(),
                    dispatchPublicId,
                    task.get().getCorrelationId(),
                    persisted,
                    TaskStatus.valueOf(task.get().getStatus())));

        } catch (IOException e) {
            log.warn("RESULT_ACK 를 보내지 못했다 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 프레임이 가리키는 전달을 찾되, <b>그것이 이 연결의 기기 것인지 확인한다.</b>
     *
     * <p>확인하지 않으면 A 사용자의 PC 가 남의 {@code dispatchId} 를 보내 그 작업을 실패로
     * 마감시킬 수 있다. 소유권 격리(DV-04) 위반이다.
     *
     * <p><b>마감 여부는 여기서 보지 않는다.</b> 마감된 전달에 무엇을 할지가 프레임마다 다르다 —
     * ACK 는 그냥 버리면 되지만 RESULT 는 응답을 돌려줘야 Agent 가 결과 캐시를 비운다.
     * 판단은 부르는 쪽이 {@link #isActive} 로 한다.
     */
    private Optional<AgentDispatchesRecord> findOwnedDispatch(
            WebSocketSession session, JsonNode frame, State state, UUID eventId) throws IOException {

        if (state != State.AUTHENTICATED) {
            fail(session, AgentProtocol.ERROR_INVALID_CONNECTION_STATE, "task frame before AUTH", eventId, true);
            return Optional.empty();
        }

        long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);

        Optional<AgentDispatchesRecord> dispatch = parseUuid(frame.path("dispatchId").asText(null))
                .flatMap(agentDispatchRepository::findByPublicId)
                .filter(record -> record.getDeviceId() == deviceId);

        if (dispatch.isEmpty()) {
            // 없는 전달이거나 남의 것이다. 어느 쪽인지 알려 주지 않고 조용히 버린다.
            // 끊지는 않는다 — 만료는 정상적으로 일어난다.
            log.warn("처리할 수 없는 전달 참조 deviceId={} dispatchId={}",
                    deviceId, frame.path("dispatchId").asText(null));
        }

        return dispatch;
    }

    /**
     * 아직 진행 중인 전달인가.
     *
     * <p>거짓이면 같은 ACK·RESULT 를 두 번 받은 것이다. 반영은 한 번만 한다는 계약을
     * Repository 의 조건절과 함께 이중으로 지킨다.
     */
    private boolean isActive(AgentDispatchesRecord dispatch) {
        return AgentDispatchStatus.valueOf(dispatch.getStatus()).isActive();
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    /**
     * 프레임 하나를 이 연결에만 보낸다.
     *
     * <p>인증을 마친 뒤에는 Pub/Sub 스레드가 같은 소켓으로 TASK 를 밀어 넣으므로 보관소가 감싼
     * 세션을 거친다. 인증 전(CHALLENGE·PROTOCOL_ERROR)에는 보관소에 없어 원본 그대로 나간다.
     * ({@link WsSessionRegistry#guarded})
     *
     * <p>{@code ATTR_DEVICE_ID} 는 HELLO 시점에 붙고 등록은 AUTH 성공 뒤라, 이 값이 있다고
     * 등록된 것은 아니다. 그 구간은 {@code guarded} 가 원본을 돌려주는 것으로 처리된다.
     */
    private void send(WebSocketSession session, Object frame) throws IOException {
        Long deviceId = (Long) session.getAttributes().get(ATTR_DEVICE_ID);
        WebSocketSession target = deviceId == null ? session : registry.guarded(WsTarget.DEVICE, deviceId, session);

        target.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
    }

    /**
     * 오류를 계약이 정한 방식으로 알린다.
     *
     * <p>소켓을 그냥 닫지 않는다. 이유를 모르면 Agent 는 같은 실패를 반복하며 재접속만 한다.
     */
    private void fail(WebSocketSession session, String code, String message, UUID relatedEventId, boolean close)
            throws IOException {
        log.debug("PROTOCOL_ERROR {} sessionId={}: {}", code, session.getId(), message);

        send(session, ProtocolErrorFrame.of(code, message, relatedEventId, close));

        if (close) {
            close(session, new CloseStatus(AgentProtocol.CLOSE_CODE_PROTOCOL_ERROR, code));
        }
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
