package com.finance.system.feishu;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.feishu.dto.FeishuConnectionRequest;
import com.finance.system.feishu.dto.FeishuDestinationRequest;
import com.finance.system.feishu.dto.FeishuOverviewResponse;
import com.finance.system.feishu.dto.NotificationDeliveryResponse;
import com.finance.system.feishu.dto.NotificationPolicyRequest;
import com.finance.system.feishu.dto.NotificationRequest;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feishu")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Feishu collaboration", description = "Controlled Feishu mock collaboration API")
public class FeishuController {
    private final FeishuService service;
    public FeishuController(FeishuService service) { this.service = service; }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('feishu:view')")
    @Operation(summary = "Read Feishu collaboration configuration")
    public ApiResponse<FeishuOverviewResponse> overview(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.overview(principal.getId()));
    }

    @PostMapping("/connections")
    @PreAuthorize("hasAuthority('feishu:manage')")
    @Operation(summary = "Create a simulated Feishu connection")
    public ApiResponse<FeishuOverviewResponse.ConnectionItem> connection(@Valid @RequestBody FeishuConnectionRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("模拟飞书连接已创建", service.createConnection(principal.getId(), request));
    }

    @PostMapping("/destinations")
    @PreAuthorize("hasAuthority('feishu:manage')")
    @Operation(summary = "Create a Feishu notification destination")
    public ApiResponse<FeishuOverviewResponse.DestinationItem> destination(@Valid @RequestBody FeishuDestinationRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("飞书接收对象已创建", service.createDestination(principal.getId(), request));
    }

    @PostMapping("/policies")
    @PreAuthorize("hasAuthority('feishu:manage')")
    @Operation(summary = "Create or update a Feishu notification policy")
    public ApiResponse<FeishuOverviewResponse.PolicyItem> policy(@Valid @RequestBody NotificationPolicyRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("飞书通知策略已保存", service.savePolicy(principal.getId(), request));
    }

    @PostMapping("/notifications")
    @PreAuthorize("hasAuthority('feishu:notify')")
    @Operation(summary = "Send a controlled simulated Feishu notification")
    public ApiResponse<NotificationDeliveryResponse> notification(@Valid @RequestBody NotificationRequest request,
                                                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("模拟飞书通知已记录", service.notify(principal.getId(), request, requestId));
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('feishu:view')")
    @Operation(summary = "List sanitized Feishu notification deliveries")
    public ApiResponse<PageResponse<NotificationDeliveryResponse>> deliveries(@RequestParam(defaultValue = "1") int page,
                                                                                @RequestParam(defaultValue = "20") int size,
                                                                                @RequestParam(required = false) String status,
                                                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.deliveries(principal.getId(), page, size, status));
    }

    @PostMapping("/notifications/{eventId}/retry")
    @PreAuthorize("hasAuthority('feishu:retry')")
    @Operation(summary = "Retry a failed controlled Feishu notification")
    public ApiResponse<NotificationDeliveryResponse> retry(@PathVariable String eventId,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("模拟飞书通知已重试", service.retry(principal.getId(), eventId, requestId));
    }
}
