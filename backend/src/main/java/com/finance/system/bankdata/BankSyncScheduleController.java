package com.finance.system.bankdata;

import com.finance.system.bankdata.dto.BankSyncScheduleCreateRequest;
import com.finance.system.bankdata.dto.BankSyncScheduleResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.domain.entity.BankSyncSchedule;
import com.finance.system.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时同步计划管理（V25）：ADMIN 专用（bank:manage，与银行账户建档同权）。
 * 只读对所有人开放（同步任务页展示时刻与下次执行倒计时）。
 */
@RestController
@RequestMapping("/api")
public class BankSyncScheduleController {

    private final BankSyncScheduleService scheduleService;

    public BankSyncScheduleController(BankSyncScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/bank-sync-schedules")
    @PreAuthorize("hasAnyAuthority('bankdata:view', 'bankdata:balance:view', 'bankdata:statement:view')")
    @Operation(summary = "List configured sync schedule times")
    public ApiResponse<List<BankSyncScheduleResponse>> list() {
        return ApiResponse.success(scheduleService.list().stream()
                .map(BankSyncScheduleController::toResponse).toList());
    }

    @PostMapping("/bank-sync-schedules")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Add a sync schedule time (HH:mm, not on the hour/half hour)")
    public ApiResponse<BankSyncScheduleResponse> create(@Valid @RequestBody BankSyncScheduleCreateRequest request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        BankSyncSchedule created = scheduleService.create(request.executeHhmm(), principal.getId());
        return ApiResponse.success("同步计划已创建", toResponse(created));
    }

    @PutMapping("/bank-sync-schedules/{id}/enabled/{enabled}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Enable or disable a sync schedule time")
    public ApiResponse<Void> updateEnabled(@PathVariable Long id, @PathVariable boolean enabled,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        scheduleService.updateEnabled(id, enabled, principal.getId());
        return ApiResponse.success(enabled ? "同步计划已启用" : "同步计划已停用", null);
    }

    @DeleteMapping("/bank-sync-schedules/{id}")
    @PreAuthorize("hasAuthority('bank:manage')")
    @Operation(summary = "Delete a sync schedule time")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        scheduleService.delete(id, principal.getId());
        return ApiResponse.success("同步计划已删除", null);
    }

    private static BankSyncScheduleResponse toResponse(BankSyncSchedule schedule) {
        return new BankSyncScheduleResponse(schedule.getId(), schedule.getExecuteHhmm(),
                Boolean.TRUE.equals(schedule.getEnabled()));
    }
}
