package com.likelion.slash.task;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.dto.TaskHistoryResponse;
import com.likelion.slash.task.dto.TaskSummaryResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 이력 조회. ({@code GET /api/v1/tasks} · P0-B "사용자별 작업 이력과 카테고리·상태·기기 필터")
 *
 * <p>접수·전달을 맡은 {@link TaskService} 와 나눠 둔다. 이쪽은 읽기만 하고 상태를 옮기지 않는다.
 */
@Service
public class TaskHistoryService {

    /** 한 쪽에 담을 기본 개수. */
    static final int DEFAULT_LIMIT = 20;

    /** 한 번에 가져갈 수 있는 최대 개수. 목록에는 결과 본문이 없어 이 정도는 가볍다. */
    static final int MAX_LIMIT = 100;

    private final TaskRepository taskRepository;
    private final DeviceRepository deviceRepository;

    public TaskHistoryService(TaskRepository taskRepository, DeviceRepository deviceRepository) {
        this.taskRepository = taskRepository;
        this.deviceRepository = deviceRepository;
    }

    /**
     * 내 작업 이력을 최신순으로 읽는다.
     *
     * @param taskType 작업 유형 이름. 비우면 전체
     * @param status   작업 상태 이름. 비우면 전체
     * @param deviceId 대상 PC 의 공개 식별자. 비우면 전체
     * @param cursor   이전 응답의 {@code nextCursor}. 첫 쪽이면 비운다
     * @param limit    한 쪽 개수. 비우면 {@value #DEFAULT_LIMIT}
     */
    @Transactional(readOnly = true)
    public TaskHistoryResponse find(long userId,
                                    String taskType,
                                    String status,
                                    UUID deviceId,
                                    String cursor,
                                    Integer limit) {

        // 넘어온 값을 모두 먼저 확인한다. 뒤로 미루면 조건 조합에 따라 검사가 건너뛰어져,
        // 같은 잘못을 보내도 어떨 때는 400 이고 어떨 때는 빈 목록이 된다.
        int size = pageSize(limit);
        HistoryCursor from = HistoryCursor.decode(cursor);
        Optional<TaskHistoryFilter> filter = filterOf(userId, taskType, status, deviceId);

        // 내 기기가 아닌 값으로 좁히면 볼 것이 없다. 기기가 있는지 알려 주지 않기 위해 빈 목록으로 답한다.
        if (filter.isEmpty()) {
            return new TaskHistoryResponse(List.of(), null);
        }

        // 한 건 더 읽어 다음 쪽이 있는지 본다. 개수만으로 판단하면 마지막 쪽이 정확히 size 일 때 틀린다.
        List<TasksRecord> found = taskRepository.findRecent(userId, filter.get(), from, size + 1);

        boolean hasNext = found.size() > size;
        List<TasksRecord> page = hasNext ? found.subList(0, size) : found;

        Map<Long, UUID> devicePublicIds = devicePublicIds(userId);
        List<TaskSummaryResponse> items = page.stream()
                .map(task -> TaskSummaryResponse.from(task, devicePublicIds.get(task.getDeviceId())))
                .toList();

        return new TaskHistoryResponse(items, hasNext ? nextCursor(page) : null);
    }

    private String nextCursor(List<TasksRecord> page) {
        TasksRecord last = page.get(page.size() - 1);
        return new HistoryCursor(last.getCreatedAt(), last.getId()).encode();
    }

    /**
     * 기기 내부 PK 를 공개 식별자로 바꿀 표를 만든다.
     *
     * <p>작업마다 기기를 따로 조회하면 목록 한 쪽에 스무 번을 더 묻게 된다. 등록할 수 있는 PC 는
     * 몇 대뿐이라 통째로 한 번 읽는 편이 싸다.
     *
     * <p><b>해제한 기기도 포함해야 한다.</b> 그 PC 로 실행했던 작업은 이력에 그대로 남아 있는데,
     * 활성 기기만 읽으면 그 줄의 기기가 사라져 보인다.
     */
    private Map<Long, UUID> devicePublicIds(long userId) {
        return deviceRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(DevicesRecord::getId, DevicesRecord::getPublicId));
    }

    /** 남의 기기이거나 없는 기기를 가리키면 비어 있다. */
    private Optional<TaskHistoryFilter> filterOf(long userId, String taskType, String status, UUID deviceId) {
        // 이름부터 확인한다. 기기를 먼저 보면, 볼 수 없는 기기를 가리켰을 때 빈 목록으로 끝나
        // 함께 보낸 잘못된 갈래·상태 이름이 조용히 넘어간다. 같은 요청이 조건 조합에 따라
        // 400 이 되기도 하고 200 이 되기도 하는 것을 막는다.
        TaskType type = parse(taskType, TaskType::valueOf);
        TaskStatus taskStatus = parse(status, TaskStatus::valueOf);

        Long internalDeviceId = null;
        if (deviceId != null) {
            Optional<DevicesRecord> device = deviceRepository.findByPublicIdAndUserId(deviceId, userId);
            if (device.isEmpty()) {
                return Optional.empty();
            }
            internalDeviceId = device.get().getId();
        }

        return Optional.of(new TaskHistoryFilter(type, taskStatus, internalDeviceId));
    }

    /**
     * 이름을 갈래 값으로 옮긴다.
     *
     * <p>모르는 이름은 조용히 무시하지 않고 {@code VALIDATION_ERROR} 로 알린다. 무시하면 필터가
     * 걸린 줄 알았던 사용자가 전체 목록을 보고 잘못된 판단을 하게 된다.
     *
     * <p>대문자로 올릴 때 {@link Locale#ROOT} 를 쓴다. 서버 기본 로케일을 따르면 터키어에서
     * {@code i} 가 {@code İ} 로 올라가 소문자로 보낸 이름이 갑자기 어긋난다.
     */
    private <T> T parse(String name, Function<String, T> converter) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return converter.apply(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** 범위를 벗어난 값은 거절한다. 조용히 줄이면 프론트가 다 받은 줄 알고 다음 쪽을 부르지 않는다. */
    private int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }
        return limit;
    }
}
