package com.likelion.slash.auth;

import com.likelion.slash.auth.dto.MeResponse;
import com.likelion.slash.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 정보. (WBS W1-01)
 *
 * <p>프론트가 로그인 직후 한 번 호출해 {@code userId} 와 표시 이름을 받는다.
 * 첫 호출이면 이 시점에 사용자 레코드가 만들어진다.
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AuthenticatedUserService authenticatedUserService;

    public MeController(AuthenticatedUserService authenticatedUserService) {
        this.authenticatedUserService = authenticatedUserService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.of(MeResponse.from(authenticatedUserService.current()));
    }
}
