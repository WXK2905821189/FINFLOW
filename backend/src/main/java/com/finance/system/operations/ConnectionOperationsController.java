package com.finance.system.operations;

import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.operations.dto.ConnectionConfigurationResponse;
import com.finance.system.operations.dto.ConnectionOverviewResponse;
import com.finance.system.operations.dto.DataQueryCapabilityResponse;
import com.finance.system.operations.dto.OperationLogResponse;
import com.finance.system.operations.dto.OperationTaskResponse;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class ConnectionOperationsController {

    private final ConnectionOperationsService service;

    public ConnectionOperationsController(ConnectionOperationsService service) {
        this.service = service;
    }

    @GetMapping("/connections/configuration")
    @PreAuthorize("hasAnyAuthority('connection:view', 'connection:manage')")
    @Operation(summary = "Read safe connection configuration metadata")
    public ApiResponse<ConnectionConfigurationResponse> configuration(
            @RequestParam(required = false) String section,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.configuration(principal.getId(), section));
    }

    @GetMapping("/operations/connections")
    @PreAuthorize("hasAuthority('operation:monitor')")
    @Operation(summary = "Read connection status overview")
    public ApiResponse<ConnectionOverviewResponse> connections(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.overview(principal.getId()));
    }

    @GetMapping("/operations/tasks")
    @PreAuthorize("hasAuthority('operation:monitor')")
    @Operation(summary = "List connection operation tasks")
    public ApiResponse<PageResponse<OperationTaskResponse>> tasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String connectionCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.tasks(principal.getId(), page, size, connectionCode, status, requestId));
    }

    @GetMapping("/operations/logs")
    @PreAuthorize("hasAuthority('operation:log:view')")
    @Operation(summary = "List sanitized connection operation logs")
    public ApiResponse<PageResponse<OperationLogResponse>> logs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String connectionCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(service.logs(principal.getId(), page, size, connectionCode, status, requestId));
    }

    @GetMapping("/data/{resource}")
    @PreAuthorize("hasAuthority('data:query')")
    @Operation(summary = "Read server-side data query capability status")
    public ApiResponse<DataQueryCapabilityResponse> dataCapability(@PathVariable String resource) {
        return ApiResponse.success(service.dataCapability(resource));
    }
}
