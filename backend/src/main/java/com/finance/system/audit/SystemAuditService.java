package com.finance.system.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.audit.dto.SystemAuditEventResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.SystemAuditEvent;
import com.finance.system.domain.mapper.SystemAuditEventMapper;
import org.springframework.stereotype.Service;

@Service
public class SystemAuditService {
    private final SystemAuditEventMapper mapper; private final CompanyScopeService scope;
    public SystemAuditService(SystemAuditEventMapper mapper, CompanyScopeService scope) { this.mapper = mapper; this.scope = scope; }
    public void record(Long userId, String action, String type, String objectId, String requestId, String result, String detail) {
        SystemAuditEvent event = new SystemAuditEvent(); event.setCompanyId(scope.companyIdForUser(userId)); event.setActorId(userId);
        event.setAction(limit(action, 64)); event.setObjectType(limit(type, 64)); event.setObjectId(limit(objectId, 128));
        event.setRequestId(requestId == null || requestId.isBlank() ? java.util.UUID.randomUUID().toString() : limit(requestId, 64));
        event.setResult(limit(result, 16)); event.setDetail(sanitize(detail)); event.setCreatedAt(java.time.LocalDateTime.now()); mapper.insert(event);
    }
    public PageResponse<SystemAuditEventResponse> page(Long userId, int page, int size, String action, String objectType, String requestId) {
        long companyId = scope.companyIdForUser(userId);
        Page<SystemAuditEvent> result = mapper.selectPage(new Page<>(Math.max(1,page), Math.min(100,Math.max(1,size))),
                new LambdaQueryWrapper<SystemAuditEvent>().eq(SystemAuditEvent::getCompanyId, companyId)
                        .eq(action != null && !action.isBlank(), SystemAuditEvent::getAction, action)
                        .eq(objectType != null && !objectType.isBlank(), SystemAuditEvent::getObjectType, objectType)
                        .eq(requestId != null && !requestId.isBlank(), SystemAuditEvent::getRequestId, requestId)
                        .orderByDesc(SystemAuditEvent::getCreatedAt).orderByDesc(SystemAuditEvent::getId));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords().stream()
                .map(e -> new SystemAuditEventResponse(e.getId(), e.getActorId(), e.getAction(), e.getObjectType(), e.getObjectId(), e.getRequestId(), e.getResult(), e.getDetail(), e.getCreatedAt())).toList());
    }
    private String limit(String value, int max) { if (value == null) return null; return value.substring(0, Math.min(max, value.length())); }
    private String sanitize(String value) { if (value == null) return null; return limit(value.replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]"), 500); }
}
