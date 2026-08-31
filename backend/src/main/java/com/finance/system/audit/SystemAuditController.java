package com.finance.system.audit;
import com.finance.system.audit.dto.SystemAuditEventResponse;
import com.finance.system.common.api.ApiResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/audit") public class SystemAuditController {
    private final SystemAuditService service; public SystemAuditController(SystemAuditService service){this.service=service;}
    @GetMapping("/events") @PreAuthorize("hasAuthority('audit:view')")
    public ApiResponse<PageResponse<SystemAuditEventResponse>> page(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String action, @RequestParam(required=false) String objectType, @RequestParam(required=false) String requestId,
            @AuthenticationPrincipal UserPrincipal principal){ return ApiResponse.success(service.page(principal.getId(),page,size,action,objectType,requestId)); }
}
