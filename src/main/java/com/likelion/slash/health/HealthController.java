package com.likelion.slash.health;

import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.health.dto.DependencyHealthResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 의존 서비스 연결 점검. (WBS W1-00)
 *
 * <p>Kubernetes Probe 는 {@code /actuator/health} 를 사용한다.
 * 이 Endpoint 는 배포 확인과 장애 조사에서 어느 의존 서비스가 끊겼는지
 * 공통 응답 형식으로 확인하기 위한 것이다.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final DependencyHealthService dependencyHealthService;

    public HealthController(DependencyHealthService dependencyHealthService) {
        this.dependencyHealthService = dependencyHealthService;
    }

    @GetMapping("/dependencies")
    public ResponseEntity<ApiResponse<DependencyHealthResponse>> dependencies() {
        DependencyHealthResponse health = dependencyHealthService.check();

        // 하나라도 끊겼으면 503 으로 알리되, 어느 쪽이 문제인지 본문으로 함께 전달한다.
        HttpStatus status = health.allUp()
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(ApiResponse.of(health));
    }
}
