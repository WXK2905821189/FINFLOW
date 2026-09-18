package com.finance.system.preference;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号级界面偏好端点（V35 表格内核口径③）。
 *
 * <p>鉴权只要求「已登录」：偏好是账号私有数据，读写都以当前主体 id 定位，不存在跨账号授权问题，
 * 故不挂 {@code @PreAuthorize} 权限码——它不属于可授给别人的业务权限。</p>
 */
@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class AccountPreferenceController {

    private final AccountPreferenceService accountPreferenceService;

    public AccountPreferenceController(AccountPreferenceService accountPreferenceService) {
        this.accountPreferenceService = accountPreferenceService;
    }

    @GetMapping("/preferences/{scope}")
    @Operation(summary = "读取当前账号在某个网格 scope 下的界面偏好快照；从未保存过返回 payload=null（非 404）")
    public ApiResponse<AccountPreferenceResponse> get(
            @PathVariable String scope,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(accountPreferenceService.get(principal.getId(), scope));
    }

    @PutMapping("/preferences/{scope}")
    @Operation(summary = "保存当前账号在某个网格 scope 下的界面偏好快照（存在则覆盖，跨设备生效）")
    public ApiResponse<AccountPreferenceResponse> save(
            @PathVariable String scope,
            @Valid @RequestBody AccountPreferenceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(accountPreferenceService.save(principal.getId(), scope, request.payload()));
    }
}
