package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICE_PROJECT_WORKSPACES;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.DeviceProjectWorkspacesRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code device_project_workspaces} 접근. (P0-B · CODE_ANALYSIS)
 *
 * <p>Agent 가 READY 프레임의 {@code projectWorkspaces} 로 보고한 프로젝트 폴더다.
 * {@code CODE_ANALYSIS}(/code) 의 필수 인자 {@code workspaceId} 를 서버가 여기서 고른다.
 *
 * <p>{@link DeviceSearchFolderRepository} 와 같은 방식이다. PK 가
 * {@code (device_id, workspace_id)} 라 최신 보고가 이전 보고를 덮어쓴다.
 */
@Repository
public class DeviceProjectWorkspaceRepository {

    private final DSLContext dsl;

    public DeviceProjectWorkspaceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * READY 보고 내용으로 기기의 프로젝트 폴더 목록을 통째로 맞춘다.
     *
     * <p>보고에 없는 폴더는 지운다. 사용자가 Agent 에서 폴더를 뺐는데 목록에 남아 있으면,
     * 서버가 <b>더 이상 없는 폴더를 골라</b> 작업을 보내고 Agent 는 그때마다
     * {@code WORKSPACE_NOT_FOUND} 로 거절한다. 사용자 눈에는 이유 없는 실패로 보인다.
     *
     * <p>계약에 없는 값은 {@link ProjectWorkspace#isStorable()} 과
     * {@link ProjectWorkspace#storableAdapters()} 가 먼저 거른다. 제약 위반으로 트랜잭션이
     * 깨지면 READY 처리가 통째로 실패해 연결이 끊긴다.
     */
    public void replaceAll(long deviceId, Collection<ProjectWorkspace> reported) {
        List<ProjectWorkspace> storable = reported.stream().filter(ProjectWorkspace::isStorable).toList();
        List<String> ids = storable.stream().map(ProjectWorkspace::workspaceId).toList();

        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            tx.deleteFrom(DEVICE_PROJECT_WORKSPACES)
                    .where(DEVICE_PROJECT_WORKSPACES.DEVICE_ID.eq(deviceId))
                    .and(ids.isEmpty()
                            ? DSL.noCondition()
                            : DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID.notIn(ids))
                    .execute();

            for (ProjectWorkspace workspace : storable) {
                String[] adapters = workspace.storableAdapters().toArray(String[]::new);

                tx.insertInto(DEVICE_PROJECT_WORKSPACES)
                        .set(DEVICE_PROJECT_WORKSPACES.DEVICE_ID, deviceId)
                        .set(DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID, workspace.workspaceId())
                        .set(DEVICE_PROJECT_WORKSPACES.DISPLAY_NAME, workspace.displayNameForStorage())
                        .set(DEVICE_PROJECT_WORKSPACES.WORKSPACE_TYPE, workspace.workspaceType())
                        .set(DEVICE_PROJECT_WORKSPACES.AVAILABLE_CODE_ADAPTERS, adapters)
                        .set(DEVICE_PROJECT_WORKSPACES.REPORTED_AT, SlashTime.now())
                        .onConflict(DEVICE_PROJECT_WORKSPACES.DEVICE_ID, DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID)
                        .doUpdate()
                        .set(DEVICE_PROJECT_WORKSPACES.DISPLAY_NAME, workspace.displayNameForStorage())
                        .set(DEVICE_PROJECT_WORKSPACES.WORKSPACE_TYPE, workspace.workspaceType())
                        .set(DEVICE_PROJECT_WORKSPACES.AVAILABLE_CODE_ADAPTERS, adapters)
                        .set(DEVICE_PROJECT_WORKSPACES.REPORTED_AT, SlashTime.now())
                        .execute();
            }
        });
    }

    /**
     * 이 기기에서 지금 분석할 수 있는 폴더 하나를 고른다.
     *
     * <p><b>도구가 하나도 없는 폴더는 고르지 않는다.</b> Agent 의 {@code _resolve_code_adapter}
     * 가 그런 폴더를 {@code CODE_AGENT_NOT_CONFIGURED} 로 거절하므로, 골라 보내 봐야 실패한다.
     * 폴더는 있는데 CLI 가 설치되지 않은 PC 에서 그렇다.
     *
     * <p>고르는 기준은 이름 순이다. 폴더를 고르는 화면이 아직 없어 서버가 정해야 하는데,
     * 순서가 매번 달라지면 같은 명령이 다른 프로젝트를 분석한다.
     * <b>사용자가 고르는 화면이 생기면 이 메서드는 사라진다.</b>
     * ({@link DeviceSearchFolderRepository#pickSearchable} 과 같은 판단이다)
     *
     * @return 고른 폴더의 식별자. 비어 있으면 분석할 수 있는 폴더가 하나도 없다
     */
    public Optional<String> pickAnalyzable(long deviceId) {
        return dsl.select(DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID)
                .from(DEVICE_PROJECT_WORKSPACES)
                .where(DEVICE_PROJECT_WORKSPACES.DEVICE_ID.eq(deviceId))
                .and(DSL.cardinality(DEVICE_PROJECT_WORKSPACES.AVAILABLE_CODE_ADAPTERS).gt(0))
                .orderBy(DEVICE_PROJECT_WORKSPACES.DISPLAY_NAME, DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID)
                .limit(1)
                .fetchOptional(DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID);
    }

    public List<DeviceProjectWorkspacesRecord> findAllByDeviceId(long deviceId) {
        return dsl.selectFrom(DEVICE_PROJECT_WORKSPACES)
                .where(DEVICE_PROJECT_WORKSPACES.DEVICE_ID.eq(deviceId))
                .orderBy(DEVICE_PROJECT_WORKSPACES.DISPLAY_NAME, DEVICE_PROJECT_WORKSPACES.WORKSPACE_ID)
                .fetch();
    }
}
