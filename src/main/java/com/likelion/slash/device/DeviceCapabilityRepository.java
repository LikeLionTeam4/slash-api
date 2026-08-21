package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICE_CAPABILITIES;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.jooq.tables.records.DeviceCapabilitiesRecord;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code device_capabilities} 접근.
 *
 * <p>Agent 가 READY 프레임의 {@code supportedTaskTypes} 로 보고한 실행 가능 작업 유형이다.
 * 명령을 보내기 전 "이 PC 가 이 작업을 할 수 있는가" 판정에 쓴다. (문서 DV-05)
 *
 * <p>PK 가 {@code (device_id, task_type)} 이라 최신 보고가 이전 보고를 덮어쓴다.
 *
 * <p>관련 문서: 3.4.4 · WBS W1-03
 */
@Repository
public class DeviceCapabilityRepository {

    private final DSLContext dsl;

    public DeviceCapabilityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * READY 프레임의 보고 내용으로 기기의 지원 목록을 통째로 맞춘다.
     *
     * <p>Agent 를 낮은 버전으로 되돌리면 지원 목록이 줄어들 수 있으므로,
     * 보고에 없는 항목은 남겨두지 않고 지운다.
     *
     * <p>{@code ck_device_capabilities_task_type} 은 실행기가 처리할 수 있는 작업만 허용한다.
     * 그 목록은 {@link TaskType#isAgentCapability()} 가 들고 있다 — PC 가 반드시 필요한
     * 작업과 같지 않다({@code TEXT_SUMMARY}). Agent 가 계약에 없는 값을 보내도 제약 위반으로
     * 통째로 실패하지 않도록 여기서 먼저 거른다.
     *
     * @param reported Agent 가 보고한 작업 유형
     */
    public void replaceAll(long deviceId, Collection<TaskType> reported) {
        Set<TaskType> supported = EnumSet.noneOf(TaskType.class);
        reported.stream().filter(TaskType::isAgentCapability).forEach(supported::add);

        List<String> names = supported.stream().map(TaskType::name).toList();

        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            tx.deleteFrom(DEVICE_CAPABILITIES)
                    .where(DEVICE_CAPABILITIES.DEVICE_ID.eq(deviceId))
                    .and(names.isEmpty()
                            ? DSL.noCondition()
                            : DEVICE_CAPABILITIES.TASK_TYPE.notIn(names))
                    .execute();

            for (TaskType taskType : supported) {
                tx.insertInto(DEVICE_CAPABILITIES)
                        .set(DEVICE_CAPABILITIES.DEVICE_ID, deviceId)
                        .set(DEVICE_CAPABILITIES.TASK_TYPE, taskType.name())
                        .set(DEVICE_CAPABILITIES.AVAILABLE, true)
                        .set(DEVICE_CAPABILITIES.REPORTED_AT, SlashTime.now())
                        .onConflict(DEVICE_CAPABILITIES.DEVICE_ID, DEVICE_CAPABILITIES.TASK_TYPE)
                        .doUpdate()
                        .set(DEVICE_CAPABILITIES.AVAILABLE, true)
                        .set(DEVICE_CAPABILITIES.REPORTED_AT, SlashTime.now())
                        .execute();
            }
        });
    }

    public List<DeviceCapabilitiesRecord> findAllByDeviceId(long deviceId) {
        return dsl.selectFrom(DEVICE_CAPABILITIES)
                .where(DEVICE_CAPABILITIES.DEVICE_ID.eq(deviceId))
                .orderBy(DEVICE_CAPABILITIES.TASK_TYPE)
                .fetch();
    }

    /** 작업 전달 전 실행 가능 여부 판정. 보고가 없으면 지원하지 않는 것으로 본다. */
    public boolean supports(long deviceId, TaskType taskType) {
        return dsl.fetchExists(
                dsl.selectFrom(DEVICE_CAPABILITIES)
                        .where(DEVICE_CAPABILITIES.DEVICE_ID.eq(deviceId))
                        .and(DEVICE_CAPABILITIES.TASK_TYPE.eq(taskType.name()))
                        .and(DEVICE_CAPABILITIES.AVAILABLE.isTrue()));
    }
}
