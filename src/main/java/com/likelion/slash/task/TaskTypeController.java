package com.likelion.slash.task;

import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.task.dto.TaskTypeCatalogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업 유형 기준 목록. (slash-api #9)
 *
 * <p>Backend·NLU·Agent·LLM 이 각자 작업 유형 목록을 들고 있어서, 한쪽만 바뀌면 다른 서비스가
 * 요청을 처리하지 못한다. {@link com.likelion.slash.common.enums.TaskType} 을 단일 기준으로 삼고
 * 이 Endpoint 로 노출해 나머지 서비스가 자기 목록과 맞춰볼 수 있게 한다.
 *
 * <p>사용자별로 달라지지 않는 고정 목록이지만 공개 경로로 두지 않았다.
 * 인증이 기본이고 예외만 {@code SecurityConfig.PUBLIC_PATHS} 에 두는 규칙을 지킨다.
 * 로컬에서는 임시 인증으로 아무 문자열이나 Bearer 로 보내면 조회된다.
 */
@RestController
@RequestMapping("/api/v1")
public class TaskTypeController {

    @GetMapping("/task-types")
    public ApiResponse<TaskTypeCatalogResponse> taskTypes() {
        return ApiResponse.of(TaskTypeCatalogResponse.of());
    }
}
