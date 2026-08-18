package com.likelion.slash.pairing;

import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceArchitecture;
import com.likelion.slash.common.enums.DeviceOs;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.PairingStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.jooq.tables.records.DevicePairingRequestsRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.pairing.dto.AgentPairRequest;
import com.likelion.slash.pairing.dto.AgentPairResponse;
import com.likelion.slash.pairing.dto.AgentPairVerifyRequest;
import com.likelion.slash.pairing.dto.AgentSessionRefreshRequest;
import com.likelion.slash.pairing.dto.AgentTokenResponse;
import com.likelion.slash.pairing.dto.PairingCodeResponse;
import com.likelion.slash.pairing.dto.PairingStatusResponse;
import com.likelion.slash.ws.AgentProtocol;
import com.likelion.slash.ws.AgentSignatureVerifier;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PC 등록(페어링). (WBS W1-02 · 메시지 스펙 §8.1)
 *
 * <pre>
 *   1. 사용자 화면  POST /pairing-requests        → 6자리 코드 (5분)
 *   2. Agent        POST /agent/pair              → 기기 생성 + 도전값 (아직 Token 없음)
 *   3. Agent        POST /agent/pair/verify       → 서명 검증 → 기기 Token 발급
 *   4. Agent        POST /agent/sessions/refresh  → 서명으로 재증명 → Token 재발급
 * </pre>
 *
 * <p><b>2단계에서 기기를 먼저 만든다.</b> 서명 대상 문자열에 {@code deviceId} 가 들어가기 때문에
 * 식별자가 먼저 있어야 한다. 그래서 증명 전에도 기기 행이 생긴다.
 * 다만 Token 이 없으므로 그 상태로는 아무것도 할 수 없고, 사용자 목록에는 OFFLINE 으로 보인다.
 *
 * <p><b>코드도 Token 도 원문을 저장하지 않는다.</b> 해시로만 대조한다. (V003 · V008)
 */
@Service
public class PairingService {

    private static final Logger log = LoggerFactory.getLogger(PairingService.class);

    private static final int PAIRING_CODE_DIGITS = 6;
    private static final int DEVICE_TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    private final PairingRequestRepository pairingRequestRepository;
    private final DeviceRepository deviceRepository;
    private final PairingSessionStore sessionStore;
    private final PairingAttemptLimiter attemptLimiter;
    private final RefreshNonceStore refreshNonceStore;
    private final AgentSignatureVerifier signatureVerifier;

    private final Duration codeTtl;
    private final Duration sessionTtl;
    private final Duration tokenTtl;
    private final Duration refreshSkew;
    private final String agentWsUrl;

    public PairingService(PairingRequestRepository pairingRequestRepository,
                          DeviceRepository deviceRepository,
                          PairingSessionStore sessionStore,
                          PairingAttemptLimiter attemptLimiter,
                          RefreshNonceStore refreshNonceStore,
                          AgentSignatureVerifier signatureVerifier,
                          @Value("${slash.pairing.code-ttl}") Duration codeTtl,
                          @Value("${slash.pairing.session-ttl}") Duration sessionTtl,
                          @Value("${slash.device.token-ttl}") Duration tokenTtl,
                          @Value("${slash.pairing.refresh-skew}") Duration refreshSkew,
                          @Value("${slash.agent.ws-url}") String agentWsUrl) {
        this.pairingRequestRepository = pairingRequestRepository;
        this.deviceRepository = deviceRepository;
        this.sessionStore = sessionStore;
        this.attemptLimiter = attemptLimiter;
        this.refreshNonceStore = refreshNonceStore;
        this.signatureVerifier = signatureVerifier;
        this.codeTtl = codeTtl;
        this.sessionTtl = sessionTtl;
        this.tokenTtl = tokenTtl;
        this.refreshSkew = refreshSkew;
        this.agentWsUrl = agentWsUrl;
    }

    // ------------------------------------------------------------------
    // 1. 사용자 화면
    // ------------------------------------------------------------------

    /**
     * 등록 코드를 발급한다.
     *
     * <p>{@code uk_pairing_active_per_user} 때문에 사용자당 활성 코드는 한 건이다.
     * 기존 활성 코드 무효화는 {@link PairingRequestRepository#issue} 가 같은 트랜잭션에서 처리한다.
     * 그래서 사용자는 항상 마지막으로 받은 코드만 쓸 수 있다.
     */
    @Transactional
    public PairingCodeResponse issueCode(long userId) {
        String code = randomCode();
        OffsetDateTime expiresAt = SlashTime.now().plus(codeTtl);

        DevicePairingRequestsRecord request =
                pairingRequestRepository.issue(userId, Sha256.hex(code), expiresAt);

        // 코드 원문은 로그에도 남기지 않는다.
        log.info("등록 코드 발급 userId={} pairingRequestId={}", userId, request.getPublicId());

        return new PairingCodeResponse(request.getPublicId(), code, expiresAt);
    }

    public PairingStatusResponse findStatus(UUID pairingRequestId, long userId) {
        DevicePairingRequestsRecord request = pairingRequestRepository
                .findByPublicIdAndUserId(pairingRequestId, userId)
                .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!PairingStatus.COMPLETED.name().equals(request.getStatus()) || request.getConsumedDeviceId() == null) {
            return PairingStatusResponse.pending();
        }

        return deviceRepository.findById(request.getConsumedDeviceId())
                .map(device -> PairingStatusResponse.claimed(device.getPublicId()))
                .orElseGet(PairingStatusResponse::pending);
    }

    // ------------------------------------------------------------------
    // 2. Agent — 등록 시작
    // ------------------------------------------------------------------

    /**
     * 등록 코드를 받아 기기를 만들고 도전값을 돌려준다.
     *
     * @param client 시도 횟수를 세는 기준 (호출자 주소)
     */
    @Transactional
    public AgentPairResponse pair(AgentPairRequest request, String client) {
        if (attemptLimiter.isBlocked(client)) {
            // 코드를 대조하지도 않는다. 대조하면 맞고 틀림이 응답 시간으로 새어 나간다.
            log.warn("페어링 시도 횟수 초과 client={}", client);
            throw new SlashException(ErrorCode.PAIRING_CODE_INVALID);
        }

        Optional<DevicePairingRequestsRecord> pairingRequest =
                pairingRequestRepository.findUsableByCodeHash(Sha256.hex(request.pairingCode()));

        if (pairingRequest.isEmpty() || pairingRequest.get().getExpiresAt().isBefore(SlashTime.now())) {
            attemptLimiter.recordFailure(client);
            throw new SlashException(ErrorCode.PAIRING_CODE_INVALID);
        }

        DevicesRecord device = createDevice(pairingRequest.get().getUserId(), request);

        UUID pairingSessionId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        String nonce = randomNonce();
        OffsetDateTime expiresAt = SlashTime.now().plus(sessionTtl);

        sessionStore.save(new PairingSession(
                pairingSessionId,
                pairingRequest.get().getId(),
                device.getId(),
                device.getPublicId(),
                challengeId,
                nonce,
                request.publicKey(),
                expiresAt), sessionTtl);

        log.info("페어링 시작 deviceId={} userId={}", device.getPublicId(), pairingRequest.get().getUserId());

        return new AgentPairResponse(pairingSessionId, device.getPublicId(), challengeId, nonce, expiresAt);
    }

    /**
     * 등록 코드를 소비하지 않은 채 기기를 만든다.
     *
     * <p>코드는 서명 검증까지 끝난 뒤에 소비한다. 여기서 소비하면 서명이 틀렸을 때
     * 사용자가 코드를 다시 발급받아야 한다. Agent 를 잘못 만든 쪽 잘못을 사용자가 떠안는다.
     *
     * <p><b>이미 등록된 적 있는 공개키</b>는 주인이 같을 때만 되살린다.
     * {@code uk_devices_public_key} 는 위장 등록을 막는 제약이므로, 주인이 다르면 거절한다.
     * 반대로 주인이 같으면 되살려야 한다 — 그러지 않으면 연결을 해제한 PC 를 다시 등록할 수 없다.
     */
    private DevicesRecord createDevice(long userId, AgentPairRequest request) {
        DeviceOs os;
        DeviceArchitecture architecture;
        try {
            os = DeviceOs.valueOf(request.device().os());
            architecture = DeviceArchitecture.valueOf(request.device().architecture());
        } catch (IllegalArgumentException e) {
            // 계약에 없는 os·architecture 값이다. DB 제약에 걸리기 전에 여기서 막는다.
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }

        Optional<DevicesRecord> registered = deviceRepository.findByPublicKey(request.publicKey());

        if (registered.isPresent()) {
            if (registered.get().getUserId() != userId) {
                // 남의 계정에 등록된 PC 다. 이 코드로는 가져올 수 없다.
                log.warn("다른 사용자에게 등록된 공개키로 등록을 시도했다 deviceId={}",
                        registered.get().getPublicId());
                throw new SlashException(ErrorCode.FORBIDDEN);
            }

            return deviceRepository.reclaim(
                    registered.get().getId(), userId,
                    request.device().name(), os, architecture,
                    request.device().osVersion(), request.device().agentVersion())
                    .orElseThrow(() -> new SlashException(ErrorCode.FORBIDDEN));
        }

        try {
            return deviceRepository.insert(
                    userId,
                    request.device().name(),
                    request.publicKey(),
                    os,
                    architecture,
                    request.device().osVersion(),
                    request.device().agentVersion());

        } catch (DuplicateKeyException e) {
            // 위 조회와 저장 사이에 같은 공개키가 먼저 들어온 경우다. 흔치 않지만
            // 그대로 두면 500 이 나가므로 계약된 오류로 바꾼다.
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ------------------------------------------------------------------
    // 3. Agent — 소유 증명
    // ------------------------------------------------------------------

    /**
     * 도전값 서명을 검증하고 기기 Token 을 발급한다.
     *
     * <p>세션은 조회와 동시에 지워진다. 같은 서명을 두 번 쓸 수 없다.
     */
    @Transactional
    public AgentTokenResponse verify(AgentPairVerifyRequest request, String client) {
        PairingSession session = sessionStore.consume(request.pairingSessionId())
                .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!session.challengeId().equals(request.challengeId())
                || session.expiresAt().isBefore(SlashTime.now())) {
            throw new SlashException(ErrorCode.AGENT_AUTH_FAILED);
        }

        String payload = AgentProtocol.challengeSigningPayload(
                session.challengeId(), session.nonce(), session.devicePublicId());

        if (!signatureVerifier.verify(session.publicKey(), payload, request.signature())) {
            throw new SlashException(ErrorCode.AGENT_AUTH_FAILED);
        }

        // 여기까지 와야 코드를 소비한다. 이 시점부터 그 코드는 다시 쓸 수 없다.
        //
        // 반영되지 않았다면 다른 기기가 같은 코드로 먼저 등록을 마친 것이다.
        // pair 단계의 행 잠금은 그 요청이 끝나면 풀리므로, 증명이 오기 전까지는 같은 코드로
        // 세션이 둘 생길 수 있다. 여기서 확인하지 않으면 코드 하나로 기기 둘이 등록된다.
        if (!pairingRequestRepository.complete(session.pairingRequestId(), session.deviceId())) {
            throw new SlashException(ErrorCode.PAIRING_CODE_INVALID);
        }

        attemptLimiter.reset(client);

        log.info("페어링 완료 deviceId={}", session.devicePublicId());

        return issueToken(session.deviceId(), true);
    }

    // ------------------------------------------------------------------
    // 4. Agent — 세션 재발급
    // ------------------------------------------------------------------

    /**
     * Token 만료 전에 새 Token 을 받는다.
     *
     * <p>기존 Token 을 제시하는 방식이 아니라 <b>매번 새 nonce 에 서명</b>해 개인키 보유를
     * 다시 증명한다. Token 만으로 연장하면 훔친 Token 이 영구히 유효해진다.
     */
    @Transactional
    public AgentTokenResponse refresh(AgentSessionRefreshRequest request) {
        DevicesRecord device = deviceRepository.findByPublicId(request.deviceId())
                .filter(found -> found.getDeviceTokenHash() != null)
                .orElseThrow(() -> new SlashException(ErrorCode.AUTH_REQUIRED));

        if (DeviceStatus.REVOKED.name().equals(device.getStatus())) {
            // 재접속하는 Agent 가 가장 먼저 닿는 곳이다. 여기서 사유를 뭉뚱그리면
            // Agent 는 재페어링을 시도하다 실패하고, 사용자는 이유를 알 수 없다. (이슈 #26)
            throw new SlashException(ErrorCode.DEVICE_REVOKED);
        }

        Duration skew = Duration.between(request.requestedAt(), SlashTime.now()).abs();
        if (skew.compareTo(refreshSkew) > 0) {
            // 오래된 요청을 그대로 재생하는 것을 막는다.
            throw new SlashException(ErrorCode.AGENT_AUTH_FAILED);
        }

        if (!refreshNonceStore.useOnce(request.deviceId(), request.refreshNonce())) {
            throw new SlashException(ErrorCode.AGENT_AUTH_FAILED);
        }

        String payload = request.deviceId() + ":" + request.refreshNonce() + ":" + request.requestedAt();
        if (!signatureVerifier.verify(device.getPublicKey(), payload, request.signature())) {
            throw new SlashException(ErrorCode.AGENT_AUTH_FAILED);
        }

        log.info("기기 Token 재발급 deviceId={}", request.deviceId());

        return issueToken(device.getId(), false);
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private AgentTokenResponse issueToken(long deviceId, boolean includeWsUrl) {
        byte[] tokenBytes = new byte[DEVICE_TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        OffsetDateTime issuedAt = SlashTime.now();

        if (!deviceRepository.issueToken(deviceId, Sha256.hex(token), issuedAt.plus(tokenTtl))) {
            // 해제된 기기다. 발급하면 해제가 무력화된다.
            throw new SlashException(ErrorCode.DEVICE_REVOKED);
        }

        long expiresIn = tokenTtl.toSeconds();

        return includeWsUrl
                ? AgentTokenResponse.issued(token, expiresIn, issuedAt, agentWsUrl)
                : AgentTokenResponse.refreshed(token, expiresIn, issuedAt);
    }

    /** 앞자리가 0 이어도 6자리를 유지한다. 사용자가 눈으로 읽고 입력하는 값이다. */
    private String randomCode() {
        return String.format("%0" + PAIRING_CODE_DIGITS + "d", random.nextInt(1_000_000));
    }

    private String randomNonce() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }
}
