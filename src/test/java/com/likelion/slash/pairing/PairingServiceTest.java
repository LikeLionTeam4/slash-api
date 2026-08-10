package com.likelion.slash.pairing;

import static com.likelion.slash.jooq.Tables.DEVICE_PAIRING_REQUESTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.PairingStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.pairing.dto.AgentPairRequest;
import com.likelion.slash.pairing.dto.AgentPairResponse;
import com.likelion.slash.pairing.dto.AgentPairVerifyRequest;
import com.likelion.slash.pairing.dto.AgentSessionRefreshRequest;
import com.likelion.slash.pairing.dto.AgentTokenResponse;
import com.likelion.slash.pairing.dto.PairingCodeResponse;
import com.likelion.slash.ws.AgentProtocol;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PairingService} 확인. (WBS W1-02)
 *
 * <p>사용자 인증이 없는 경로라 여기서 막지 못하면 남의 계정에 내 PC 를 등록시킬 수 있다.
 * 그래서 "되는 것"보다 <b>안 되어야 하는 것</b>을 더 많이 본다.
 *
 * <p>Valkey 상태(세션·nonce)는 트랜잭션 롤백 대상이 아니므로 시험마다 다른 값을 쓴다.
 */
@SpringBootTest
@Transactional
class PairingServiceTest {

    private static final int RAW_KEY_LENGTH = 32;
    private static final String 클라이언트 = "127.0.0.1";

    @Autowired
    private PairingService pairingService;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DSLContext dsl;

    private KeyPair 키쌍;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        키쌍 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        userId = 사용자(dsl);
    }

    // ------------------------------------------------------------------
    // 코드 발급
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6자리 코드를 발급하고 원문이 아니라 해시를 저장한다")
    void 코드를_발급한다() {
        PairingCodeResponse response = pairingService.issueCode(userId);

        assertThat(response.pairingCode()).hasSize(6).containsOnlyDigits();
        assertThat(response.expiresAt()).isAfter(SlashTime.now());

        String 저장된_해시 = dsl.select(DEVICE_PAIRING_REQUESTS.CODE_HASH)
                .from(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.PUBLIC_ID.eq(response.pairingRequestId()))
                .fetchOne(DEVICE_PAIRING_REQUESTS.CODE_HASH);

        assertThat(저장된_해시).isEqualTo(Sha256.hex(response.pairingCode()));
        assertThat(저장된_해시).isNotEqualTo(response.pairingCode());
    }

    @Test
    @DisplayName("새 코드를 받으면 이전 코드는 쓸 수 없다")
    void 마지막_코드만_유효하다() {
        PairingCodeResponse 이전 = pairingService.issueCode(userId);
        pairingService.issueCode(userId);

        assertThatThrownBy(() -> pairingService.pair(요청(이전.pairingCode()), 클라이언트))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.PAIRING_CODE_INVALID);
    }

    // ------------------------------------------------------------------
    // 등록
    // ------------------------------------------------------------------

    @Test
    @DisplayName("코드로 기기를 만들고 도전값을 돌려준다 — 아직 Token 은 없다")
    void 등록을_시작한다() {
        AgentPairResponse response = 등록_시작();

        assertThat(response.deviceId()).isNotNull();
        assertThat(response.challengeId()).isNotNull();
        assertThat(response.nonce()).isNotBlank();

        var device = deviceRepository.findByPublicId(response.deviceId()).orElseThrow();
        assertThat(device.getUserId()).isEqualTo(userId);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.OFFLINE.name());
        // 소유가 증명되기 전에는 접속 자격을 주지 않는다.
        assertThat(device.getDeviceTokenHash()).isNull();
    }

    @Test
    @DisplayName("틀린 코드는 거부한다")
    void 틀린_코드는_거부한다() {
        pairingService.issueCode(userId);

        assertThatThrownBy(() -> pairingService.pair(요청("000000"), 클라이언트))
                .isInstanceOf(SlashException.class);
    }

    // ------------------------------------------------------------------
    // 소유 증명
    // ------------------------------------------------------------------

    @Test
    @DisplayName("서명을 검증하면 Token 을 발급하고 코드를 소비한다")
    void 등록을_마친다() throws Exception {
        AgentPairResponse pair = 등록_시작();

        AgentTokenResponse token = pairingService.verify(검증_요청(pair), 클라이언트);

        assertThat(token.deviceToken()).isNotBlank();
        assertThat(token.expiresIn()).isPositive();
        assertThat(token.wsUrl()).contains("/ws/agent");

        // 저장된 것은 해시뿐이라, 받은 Token 으로 조회가 되어야 한다.
        assertThat(deviceRepository.findByActiveTokenHash(Sha256.hex(token.deviceToken()), SlashTime.now()))
                .isPresent();

        assertThat(dsl.select(DEVICE_PAIRING_REQUESTS.STATUS)
                .from(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.USER_ID.eq(userId))
                .fetchOne(DEVICE_PAIRING_REQUESTS.STATUS))
                .isEqualTo(PairingStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("다른 키로 만든 서명은 거부한다")
    void 남의_서명은_거부한다() throws Exception {
        AgentPairResponse pair = 등록_시작();
        KeyPair 공격자 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        String 서명 = 서명(공격자, AgentProtocol.challengeSigningPayload(
                pair.challengeId(), pair.nonce(), pair.deviceId()));

        assertThatThrownBy(() -> pairingService.verify(
                new AgentPairVerifyRequest(pair.pairingSessionId(), pair.challengeId(), 서명), 클라이언트))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.AGENT_AUTH_FAILED);
    }

    @Test
    @DisplayName("같은 도전값으로 두 번 증명할 수 없다")
    void 도전값은_1회용이다() throws Exception {
        AgentPairResponse pair = 등록_시작();
        AgentPairVerifyRequest 요청 = 검증_요청(pair);

        pairingService.verify(요청, 클라이언트);

        assertThatThrownBy(() -> pairingService.verify(요청, 클라이언트))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 코드로 시작한 두 기기 중 하나만 등록을 마칠 수 있다")
    void 코드는_기기_하나만_등록한다() throws Exception {
        PairingCodeResponse code = pairingService.issueCode(userId);

        KeyPair 다른_PC = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        AgentPairResponse 첫_기기 = pairingService.pair(요청(code.pairingCode(), 키쌍), 클라이언트);
        AgentPairResponse 둘째_기기 = pairingService.pair(요청(code.pairingCode(), 다른_PC), 클라이언트);

        pairingService.verify(검증_요청(첫_기기, 키쌍), 클라이언트);

        assertThatThrownBy(() -> pairingService.verify(검증_요청(둘째_기기, 다른_PC), 클라이언트))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.PAIRING_CODE_INVALID);
    }

    // ------------------------------------------------------------------
    // 재등록
    // ------------------------------------------------------------------

    @Test
    @DisplayName("연결을 해제한 PC 를 같은 주인이 다시 등록할 수 있다")
    void 재등록할_수_있다() throws Exception {
        AgentPairResponse 처음 = 등록_시작();
        pairingService.verify(검증_요청(처음), 클라이언트);

        var device = deviceRepository.findByPublicId(처음.deviceId()).orElseThrow();
        deviceRepository.revoke(처음.deviceId(), userId, device.getVersion());

        // 같은 PC(같은 공개키)로 다시 등록한다. uk_devices_public_key 때문에 행은 새로 생기지 않는다.
        AgentPairResponse 다시 = 등록_시작();

        assertThat(다시.deviceId()).isEqualTo(처음.deviceId());

        AgentTokenResponse token = pairingService.verify(검증_요청(다시), 클라이언트);
        var 되살아난_기기 = deviceRepository.findByPublicId(처음.deviceId()).orElseThrow();

        assertThat(되살아난_기기.getStatus()).isEqualTo(DeviceStatus.OFFLINE.name());
        assertThat(되살아난_기기.getRevokedAt()).isNull();
        assertThat(token.deviceToken()).isNotBlank();
    }

    @Test
    @DisplayName("남의 계정에 등록된 PC 는 내 코드로 가져올 수 없다")
    void 남의_PC_는_가져올_수_없다() throws Exception {
        // 다른 사용자가 이 PC 를 먼저 등록해 둔 상태를 만든다.
        long 남 = 사용자(dsl);
        PairingCodeResponse 남의_코드 = pairingService.issueCode(남);
        AgentPairResponse 남의_등록 = pairingService.pair(요청(남의_코드.pairingCode(), 키쌍), 클라이언트);
        pairingService.verify(검증_요청(남의_등록, 키쌍), 클라이언트);

        PairingCodeResponse 내_코드 = pairingService.issueCode(userId);

        assertThatThrownBy(() -> pairingService.pair(요청(내_코드.pairingCode(), 키쌍), 클라이언트))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // 세션 재발급
    // ------------------------------------------------------------------

    @Test
    @DisplayName("서명으로 재증명하면 새 Token 을 준다 — 이전 Token 은 못 쓴다")
    void 재발급한다() throws Exception {
        AgentPairResponse pair = 등록_시작();
        AgentTokenResponse 첫_Token = pairingService.verify(검증_요청(pair), 클라이언트);

        AgentTokenResponse 새_Token = pairingService.refresh(재발급_요청(pair.deviceId(), UUID.randomUUID(), SlashTime.now()));

        assertThat(새_Token.deviceToken()).isNotEqualTo(첫_Token.deviceToken());
        assertThat(새_Token.wsUrl()).isNull();
        assertThat(deviceRepository.findByActiveTokenHash(Sha256.hex(첫_Token.deviceToken()), SlashTime.now()))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 nonce 로 두 번 재발급받을 수 없다 — 재전송 공격 차단")
    void nonce_는_1회용이다() throws Exception {
        AgentPairResponse pair = 등록_시작();
        pairingService.verify(검증_요청(pair), 클라이언트);

        UUID nonce = UUID.randomUUID();
        pairingService.refresh(재발급_요청(pair.deviceId(), nonce, SlashTime.now()));

        assertThatThrownBy(() -> pairingService.refresh(재발급_요청(pair.deviceId(), nonce, SlashTime.now())))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.AGENT_AUTH_FAILED);
    }

    @Test
    @DisplayName("오래된 requestedAt 은 거부한다 — 가로챈 요청의 재생 차단")
    void 오래된_요청은_거부한다() throws Exception {
        AgentPairResponse pair = 등록_시작();
        pairingService.verify(검증_요청(pair), 클라이언트);

        OffsetDateTime 한참_전 = SlashTime.now().minusMinutes(10);

        assertThatThrownBy(() -> pairingService.refresh(재발급_요청(pair.deviceId(), UUID.randomUUID(), 한참_전)))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.AGENT_AUTH_FAILED);
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private AgentPairResponse 등록_시작() {
        return pairingService.pair(요청(pairingService.issueCode(userId).pairingCode()), 클라이언트);
    }

    private AgentPairRequest 요청(String code) {
        return 요청(code, 키쌍);
    }

    private AgentPairRequest 요청(String code, KeyPair keyPair) {
        return new AgentPairRequest(
                code,
                원시표기(keyPair),
                new AgentPairRequest.Device("시험용 PC", "MACOS", "ARM64", "15.0", "0.1.0"),
                List.of("FILE_SEARCH"));
    }

    private AgentPairVerifyRequest 검증_요청(AgentPairResponse pair) throws Exception {
        return 검증_요청(pair, 키쌍);
    }

    private AgentPairVerifyRequest 검증_요청(AgentPairResponse pair, KeyPair keyPair) throws Exception {
        String 서명 = 서명(keyPair, AgentProtocol.challengeSigningPayload(
                pair.challengeId(), pair.nonce(), pair.deviceId()));

        return new AgentPairVerifyRequest(pair.pairingSessionId(), pair.challengeId(), 서명);
    }

    private AgentSessionRefreshRequest 재발급_요청(UUID deviceId, UUID nonce, OffsetDateTime requestedAt)
            throws Exception {
        String 서명 = 서명(키쌍, deviceId + ":" + nonce + ":" + requestedAt);
        return new AgentSessionRefreshRequest(deviceId, nonce, requestedAt, 서명);
    }

    private static String 원시표기(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        return Base64.getEncoder()
                .encodeToString(Arrays.copyOfRange(encoded, encoded.length - RAW_KEY_LENGTH, encoded.length));
    }

    private static String 서명(KeyPair keyPair, String payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
