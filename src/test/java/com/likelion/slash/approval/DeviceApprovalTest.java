package com.likelion.slash.approval;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalDecision;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.device.DeviceSearchFolderRepository;
import com.likelion.slash.device.SearchFolder;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.task.TaskService;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * PC 작업의 실행 전 확인. (P0-C · 계획 문서 §1.5)
 *
 * <p>서버 작업({@link TaskApprovalTest})과 갈라 둔 이유는 <b>재개하는 방식이 다르기</b>
 * 때문이다. 서버 작업은 저장해 둔 입력값으로 다시 부르면 그만이지만, PC 작업은
 * <b>승인 전에 고른 기기로</b> 전달해야 한다 — 다시 고르면 사용자가 승인한 PC 가 아닌
 * 곳에서 실행될 수 있다.
 *
 * <p>승인이 실제로 붙을 작업(파일·코드 변경)이 아직 없어 파일 검색을 대상으로 삼는다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "slash.approval.required-task-types=FILE_SEARCH")
class DeviceApprovalTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskApprovalRepository approvalRepository;

    @Autowired
    private DeviceSearchFolderRepository searchFolderRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    private AuthenticatedUser 사용자;
    private long deviceId;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());

        this.deviceId = 준비된_기기(dsl, userId);
        searchFolderRepository.replaceAll(deviceId,
                List.of(new SearchFolder("sf-1", "문서", SearchFolder.INDEXED)));

        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "FILE_SEARCH",
                        Map.of("query", "회의록"), List.of(), null, 1.0, "SLASH"));
    }

    private CreateRequestResponse 파일을_찾는다() {
        return taskService.accept(사용자, new CreateRequestRequest("/file 회의록", null), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    private int 승인버전(long taskId) {
        return approvalRepository.findByTaskId(taskId).orElseThrow().getVersion();
    }

    @Test
    @DisplayName("PC 로 보내기 전에 멈추고, 어느 PC 인지도 이미 정해 둔다")
    void 보내기_전에_묻는다() {
        CreateRequestResponse 응답 = 파일을_찾는다();

        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);

        // 물어보기만 하고 PC 로 보내지 않는다.
        verify(taskDispatcher, never()).dispatch(any(), anyLong());

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.RUNNER.name());

        // 어느 PC 에서 실행되는지 모르면 승인할 수 없다. 기기까지 정해 두고 묻는다.
        assertThat(작업.getDeviceId()).isEqualTo(deviceId);

        // 서버가 채우는 값(searchFolderId)도 이미 들어 있다.
        assertThat(작업.getParameters().data()).contains("searchFolderId");
    }

    @Test
    @DisplayName("승인하면 승인 전에 고른 그 PC 로 보낸다")
    void 승인하면_그_PC_로_보낸다() {
        CreateRequestResponse 접수 = 파일을_찾는다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        TaskStatus 결과 = taskService.decideApproval(
                사용자, 작업, ApprovalDecision.APPROVE, 승인버전(작업.getId()));

        assertThat(결과).isEqualTo(TaskStatus.QUEUED);
        verify(taskDispatcher).dispatch(any(), anyLong());
        assertThat(작업조회(접수.taskId()).getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("승인을 기다리는 사이 PC 가 꺼지면 켜질 때까지 기다린다")
    void 그_사이_PC_가_꺼지면_기다린다() {
        CreateRequestResponse 접수 = 파일을_찾는다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        // 사용자가 승인을 고민하는 동안 PC 가 꺼질 수 있다. 그때 실패로 마감하면
        // "PC 가 꺼져 있어도 접수한다" 는 설계와 어긋난다.
        dsl.update(com.likelion.slash.jooq.Tables.DEVICES)
                .set(com.likelion.slash.jooq.Tables.DEVICES.STATUS, DeviceStatus.OFFLINE.name())
                .where(com.likelion.slash.jooq.Tables.DEVICES.ID.eq(deviceId))
                .execute();

        TaskStatus 결과 = taskService.decideApproval(
                사용자, 작업, ApprovalDecision.APPROVE, 승인버전(작업.getId()));

        assertThat(결과).isEqualTo(TaskStatus.WAITING_FOR_DEVICE);
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }
}
