package com.likelion.slash.device;

import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.dto.DeviceListResponse;
import com.likelion.slash.device.dto.DeviceResponse;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.ws.AgentProtocol;
import com.likelion.slash.ws.WsMessagePublisher;
import com.likelion.slash.ws.WsTarget;
import com.likelion.slash.ws.dto.ProtocolErrorFrame;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자 화면의 기기 관리. (WBS W1-03 · slash-api #17)
 *
 * <p>등록은 {@link com.likelion.slash.pairing.PairingService} 가, 연결 상태 갱신은
 * {@link com.likelion.slash.ws.AgentWebSocketHandler} 가 한다. 여기는 <b>사용자가 화면에서
 * 자기 PC 를 들여다보는 경로</b>다.
 *
 * <p>관련 문서: 3.4.4
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;
    private final WsMessagePublisher wsMessagePublisher;

    public DeviceService(DeviceRepository deviceRepository, WsMessagePublisher wsMessagePublisher) {
        this.deviceRepository = deviceRepository;
        this.wsMessagePublisher = wsMessagePublisher;
    }

    /**
     * 내 PC 목록.
     *
     * <p><b>{@code status} 는 저장된 값을 그대로 내보낸다.</b> 조회 시점에 다시 계산하지 않는다.
     * 연결이 끊긴 기기를 내리는 것은 {@link DeviceOfflineSweeper} 의 몫이라, 여기서 또 판정하면
     * 같은 규칙이 두 곳에 생기고 목록과 작업 전달이 서로 다른 답을 내게 된다.
     *
     * <p>대신 판정이 스윕 주기만큼 늦을 수 있다. 최대 지연은
     * {@code offline-threshold + offline-sweep.interval} 이다. 정확한 시각이 필요하면
     * {@code lastSeenAt} 을 함께 보면 된다.
     */
    @Transactional(readOnly = true)
    public DeviceListResponse findMyDevices(long userId) {
        return DeviceListResponse.from(deviceRepository.findActiveByUserId(userId));
    }

    /**
     * PC 등록을 해제한다. (#23)
     *
     * <p>행을 지우지 않고 {@code REVOKED} 로 남긴다. 작업 이력이 기기를 참조하기 때문이다.
     * 그 상태의 기기는 재접속·Token 갱신·Token 재발급이 모두 막힌다.
     *
     * <p><b>이미 붙어 있는 연결은 DB 만 바꿔서는 끊기지 않는다.</b> 그대로 두면 해제한 PC 가
     * Token 이 만료될 때까지(최대 24시간) 계속 붙어 있게 된다. 사유를 실어 보내고 끊는다 —
     * 그냥 닫으면 Agent 는 이유를 모른 채 재접속을 반복한다.
     *
     * <p>연결을 어느 Pod 이 들고 있는지 모르므로 Valkey 로 전체에 발행한다. 발행이 실패해도
     * 해제 자체는 유효하다. 다음 재접속에서 거부되고, Token 갱신도 막힌다.
     *
     * <p><b>끊는 것은 커밋된 뒤다.</b> 트랜잭션 안에서 발행하면 롤백됐을 때 <b>해제되지 않은
     * 기기의 연결만 끊긴다.</b> 사용자에게는 요청이 실패했다고 답해 놓고 PC 는 떨어져 나간 꼴이다.
     * {@link com.likelion.slash.ws.UserEventPublisher} 가 상태 전이 알림에 쓰는 것과 같은 이유다.
     */
    @Transactional
    public void revoke(long userId, UUID deviceId, int expectedVersion) {
        Optional<DevicesRecord> revoked = deviceRepository.revoke(deviceId, userId, expectedVersion);
        if (revoked.isEmpty()) {
            throw notFoundOrConflict(userId, deviceId);
        }

        long internalId = revoked.get().getId();
        afterCommit(() -> wsMessagePublisher.sendAndClose(WsTarget.DEVICE, internalId, ProtocolErrorFrame.of(
                AgentProtocol.ERROR_DEVICE_REVOKED, "등록이 해제되었습니다.", null, true)));

        log.info("PC 등록 해제 deviceId={} userId={}", deviceId, userId);
    }

    /**
     * 커밋된 뒤에 실행한다. 트랜잭션 밖에서 부르면 곧바로 실행한다.
     *
     * <p>{@link com.likelion.slash.ws.UserEventPublisher} 와 같은 방식이다. 쓰는 곳이 하나 더
     * 늘면 공통으로 빼는 편이 낫다.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 새 작업을 받을지 켜고 끈다. (#24)
     *
     * <p>해제와 다르다. 연결은 그대로 두고 <b>작업 전달만</b> 멈춘다. 되돌릴 수 있고, 그동안
     * 접수된 요청은 {@code WAITING_FOR_DEVICE} 로 쌓였다가 다시 켜면 나간다.
     *
     * <p>실행 중인 작업은 멈추지 않는다. 이미 그 PC 가 붙들고 있는 것이라 중간에 끊으면
     * 결과를 받을 자리가 없어진다. "새 작업만 안 받기" 가 이 기능의 정의다.
     *
     * <p>해제한 기기에는 걸지 않는다. 켜 두어도 어차피 작업이 나가지 않아 사용자가 잘못
     * 이해하게 된다.
     */
    @Transactional
    public DeviceResponse setTaskIntake(long userId, UUID deviceId, boolean accepting, int expectedVersion) {
        Optional<DevicesRecord> updated =
                deviceRepository.setAcceptingTasks(deviceId, userId, accepting, expectedVersion);
        if (updated.isEmpty()) {
            throw notFoundOrConflict(userId, deviceId);
        }

        log.info("작업 수신 {} deviceId={} userId={}", accepting ? "켬" : "끔", deviceId, userId);
        return DeviceResponse.from(updated.get());
    }

    /**
     * 갱신이 반영되지 않은 이유를 가른다.
     *
     * <p>대상이 아예 없거나 남의 기기면 404, 있는데 version 이 어긋났으면 412 다.
     * <b>남의 기기도 404 로 답한다</b> — 403 으로 답하면 식별자를 넣어 보며 남의 기기가
     * 존재하는지 알아낼 수 있다. (문서 DV-04)
     *
     * <p>해제된 기기는 {@code rename}·{@code revoke} 가 상태 조건으로 걸러내므로 여기로 온다.
     * 이미 해제된 것을 다시 해제하려는 경우라 404 가 맞다.
     */
    private SlashException notFoundOrConflict(long userId, UUID deviceId) {
        return deviceRepository.findByPublicIdAndUserId(deviceId, userId)
                .filter(device -> !DeviceStatus.REVOKED.name().equals(device.getStatus()))
                .<SlashException>map(device -> new SlashException(ErrorCode.RESOURCE_VERSION_MISMATCH))
                .orElseGet(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
