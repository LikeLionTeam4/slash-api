package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.ws.WsMessagePublisher;
import com.likelion.slash.ws.WsTarget;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 해제가 연결을 끊는 시점 확인. (#23)
 *
 * <p><b>이 시험만 트랜잭션으로 감싸지 않는다.</b> 끊는 것은 커밋된 뒤에 일어나므로
 * ({@code DeviceService.afterCommit}) 롤백되는 시험 안에서는 아예 실행되지 않는다.
 * 나머지 해제 동작은 {@link DeviceControllerTest} 가 본다.
 *
 * <p>확인하려는 것은 두 가지다.
 * <ul>
 *   <li>커밋되면 끊는다 — DB 만 바꾸면 해제한 PC 가 Token 이 만료될 때까지(최대 24시간) 붙어 있다</li>
 *   <li><b>실패하면 끊지 않는다</b> — 요청이 실패했다고 답해 놓고 PC 만 떨어져 나가면 안 된다</li>
 * </ul>
 */
@SpringBootTest
class DeviceRevokeCloseTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private WsMessagePublisher wsMessagePublisher;

    private Long 사용자_PK;

    @AfterEach
    void tearDown() {
        // 트랜잭션이 되돌려 주지 않으므로 직접 지운다. FK 때문에 기기 → 사용자 순서다.
        if (사용자_PK != null) {
            dsl.deleteFrom(DEVICES).where(DEVICES.USER_ID.eq(사용자_PK)).execute();
            dsl.deleteFrom(USERS).where(USERS.ID.eq(사용자_PK)).execute();
        }
    }

    @Test
    @DisplayName("커밋되면 붙어 있는 연결을 끊는다")
    void 커밋되면_끊는다() {
        사용자_PK = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, 사용자_PK);

        deviceService.revoke(사용자_PK, 공개식별자(deviceId), 0);

        verify(wsMessagePublisher).sendAndClose(eq(WsTarget.DEVICE), eq(deviceId), any());
    }

    @Test
    @DisplayName("해제하지 못했으면 연결을 끊지 않는다")
    void 실패하면_끊지_않는다() {
        사용자_PK = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, 사용자_PK);

        // version 이 어긋나 412 로 끝난다. 트랜잭션이 되돌려지므로 발행도 일어나면 안 된다.
        assertThatThrownBy(() -> deviceService.revoke(사용자_PK, 공개식별자(deviceId), 99))
                .isInstanceOf(SlashException.class);

        verify(wsMessagePublisher, never()).sendAndClose(any(), anyLong(), any());
    }

    private UUID 공개식별자(long deviceId) {
        return dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);
    }
}
