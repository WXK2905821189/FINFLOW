package com.finance.system.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.audit.dto.SystemAuditEventResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.SystemAuditEvent;
import com.finance.system.domain.mapper.SystemAuditEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemAuditService {
    private static final Logger log = LoggerFactory.getLogger(SystemAuditService.class);
    private final SystemAuditEventMapper mapper; private final CompanyScopeService scope;
    public SystemAuditService(SystemAuditEventMapper mapper, CompanyScopeService scope) { this.mapper = mapper; this.scope = scope; }
    /**
     * Audit recording must never break the business flow it observes: login failures carry
     * no actor (GAP-3, module doc 2026-09-07) and an audit outage must not take login down
     * with it. Failures degrade to a warning log; resolution failures fall back to the
     * default company scope so single-tenant deployments still see the event.
     */
    public void record(Long userId, String action, String type, String objectId, String requestId, String result, String detail) {
        try {
            SystemAuditEvent event = new SystemAuditEvent(); event.setCompanyId(resolveCompanyId(userId)); event.setActorId(userId);
            event.setAction(limit(action, 64)); event.setObjectType(limit(type, 64)); event.setObjectId(limit(objectId, 128));
            event.setRequestId(requestId == null || requestId.isBlank() ? java.util.UUID.randomUUID().toString() : limit(requestId, 64));
            event.setResult(limit(result, 16)); event.setDetail(sanitize(detail)); event.setCreatedAt(java.time.LocalDateTime.now()); mapper.insert(event);
        } catch (RuntimeException exception) {
            log.warn("system audit record failed for action {}: {}", action, exception.getMessage());
        }
    }
    private long resolveCompanyId(Long userId) {
        if (userId == null) {
            return CompanyScopeService.DEFAULT_DEVELOPMENT_COMPANY_ID;
        }
        try {
            return scope.companyIdForUser(userId);
        } catch (RuntimeException exception) {
            return CompanyScopeService.DEFAULT_DEVELOPMENT_COMPANY_ID;
        }
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
