package com.finance.system.feishu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.FeishuConnection;
import com.finance.system.domain.entity.FeishuDestination;
import com.finance.system.domain.entity.NotificationDelivery;
import com.finance.system.domain.entity.NotificationEvent;
import com.finance.system.domain.entity.NotificationOutbox;
import com.finance.system.domain.entity.NotificationPolicy;
import com.finance.system.domain.mapper.FeishuConnectionMapper;
import com.finance.system.domain.mapper.FeishuDestinationMapper;
import com.finance.system.domain.mapper.NotificationDeliveryMapper;
import com.finance.system.domain.mapper.NotificationEventMapper;
import com.finance.system.domain.mapper.NotificationOutboxMapper;
import com.finance.system.domain.mapper.NotificationPolicyMapper;
import com.finance.system.feishu.dto.FeishuConnectionRequest;
import com.finance.system.feishu.dto.FeishuDestinationRequest;
import com.finance.system.feishu.dto.FeishuOverviewResponse;
import com.finance.system.feishu.dto.NotificationDeliveryResponse;
import com.finance.system.feishu.dto.NotificationPolicyRequest;
import com.finance.system.feishu.dto.NotificationRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class FeishuService {
    private static final List<String> DESTINATION_TYPES = List.of("USER", "CHAT", "WEBHOOK_ALIAS");
    private final FeishuConnectionMapper connectionMapper;
    private final FeishuDestinationMapper destinationMapper;
    private final NotificationPolicyMapper policyMapper;
    private final NotificationEventMapper eventMapper;
    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper outboxMapper;
    private final CompanyScopeService companyScope;
    private final FeishuAdapter adapter;

    public FeishuService(FeishuConnectionMapper connectionMapper, FeishuDestinationMapper destinationMapper,
                         NotificationPolicyMapper policyMapper, NotificationEventMapper eventMapper,
                         NotificationDeliveryMapper deliveryMapper, NotificationOutboxMapper outboxMapper,
                         CompanyScopeService companyScope, FeishuAdapter adapter) {
        this.connectionMapper = connectionMapper; this.destinationMapper = destinationMapper; this.policyMapper = policyMapper;
        this.eventMapper = eventMapper; this.deliveryMapper = deliveryMapper; this.outboxMapper = outboxMapper;
        this.companyScope = companyScope; this.adapter = adapter;
    }

    public FeishuOverviewResponse overview(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<FeishuConnection> connections = connectionMapper.selectList(new LambdaQueryWrapper<FeishuConnection>()
                .eq(FeishuConnection::getCompanyId, companyId).orderByAsc(FeishuConnection::getId));
        List<FeishuDestination> destinations = destinationMapper.selectList(new LambdaQueryWrapper<FeishuDestination>()
                .eq(FeishuDestination::getCompanyId, companyId).orderByAsc(FeishuDestination::getId));
        List<NotificationPolicy> policies = policyMapper.selectList(new LambdaQueryWrapper<NotificationPolicy>()
                .eq(NotificationPolicy::getCompanyId, companyId).orderByAsc(NotificationPolicy::getEventType));
        return new FeishuOverviewResponse(!connections.isEmpty(), connections.isEmpty() ? "NOT_CONFIGURED" : "SIMULATED",
                "一期仅提供飞书模拟消息与运营验证，不连接真实飞书。",
                connections.stream().map(c -> new FeishuOverviewResponse.ConnectionItem(c.getId(), c.getConnectionCode(), c.getDisplayName(), c.getTenantAlias(), c.getMode(), c.getStatus())).toList(),
                destinations.stream().map(d -> new FeishuOverviewResponse.DestinationItem(d.getId(), d.getConnectionId(), d.getDestinationType(), d.getDestinationKey(), d.getDisplayName(), Boolean.TRUE.equals(d.getEnabled()))).toList(),
                policies.stream().map(p -> new FeishuOverviewResponse.PolicyItem(p.getId(), p.getEventType(), p.getDestinationId(), Boolean.TRUE.equals(p.getEnabled()), p.getTemplateVersion())).toList());
    }

    @Transactional
    public FeishuOverviewResponse.ConnectionItem createConnection(Long userId, FeishuConnectionRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        String code = "FEISHU-MOCK-" + System.nanoTime();
        FeishuConnection connection = new FeishuConnection(); connection.setCompanyId(companyId); connection.setConnectionCode(code);
        connection.setDisplayName(limit(request.displayName(), 128)); connection.setTenantAlias(limit(request.tenantAlias(), 128));
        connection.setMode("MOCK"); connection.setStatus("NOT_ENABLED"); connection.setCreatedAt(LocalDateTime.now()); connection.setUpdatedAt(LocalDateTime.now());
        connectionMapper.insert(connection);
        return new FeishuOverviewResponse.ConnectionItem(connection.getId(), code, connection.getDisplayName(), connection.getTenantAlias(), connection.getMode(), connection.getStatus());
    }

    @Transactional
    public FeishuOverviewResponse.DestinationItem createDestination(Long userId, FeishuDestinationRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        String type = upper(request.destinationType());
        if (!DESTINATION_TYPES.contains(type)) throw new BusinessException(400, "Unsupported Feishu destination type");
        FeishuConnection connection = connectionMapper.selectOne(new LambdaQueryWrapper<FeishuConnection>().eq(FeishuConnection::getId, request.connectionId()).eq(FeishuConnection::getCompanyId, companyId));
        if (connection == null) throw new BusinessException(404, "Feishu connection not found");
        FeishuDestination destination = new FeishuDestination(); destination.setCompanyId(companyId); destination.setConnectionId(connection.getId());
        destination.setDestinationType(type); destination.setDestinationKey(limit(request.destinationKey(), 128)); destination.setDisplayName(limit(request.displayName(), 128));
        destination.setEnabled(true); destination.setCreatedAt(LocalDateTime.now()); destination.setUpdatedAt(LocalDateTime.now());
        try { destinationMapper.insert(destination); } catch (DuplicateKeyException e) { throw new BusinessException(409, "Feishu destination already exists"); }
        return new FeishuOverviewResponse.DestinationItem(destination.getId(), connection.getId(), type, destination.getDestinationKey(), destination.getDisplayName(), true);
    }

    @Transactional
    public FeishuOverviewResponse.PolicyItem savePolicy(Long userId, NotificationPolicyRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        FeishuDestination destination = destination(companyId, request.destinationId());
        NotificationPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<NotificationPolicy>().eq(NotificationPolicy::getCompanyId, companyId).eq(NotificationPolicy::getEventType, upper(request.eventType())).eq(NotificationPolicy::getDestinationId, destination.getId()));
        if (policy == null) { policy = new NotificationPolicy(); policy.setCompanyId(companyId); policy.setEventType(upper(request.eventType())); policy.setDestinationId(destination.getId()); policy.setTemplateVersion("v1"); policy.setCreatedAt(LocalDateTime.now()); }
        policy.setEnabled(request.enabled()); policy.setUpdatedAt(LocalDateTime.now()); policyMapper.insertOrUpdate(policy);
        return new FeishuOverviewResponse.PolicyItem(policy.getId(), policy.getEventType(), policy.getDestinationId(), Boolean.TRUE.equals(policy.getEnabled()), policy.getTemplateVersion());
    }

    @Transactional
    public NotificationDeliveryResponse notify(Long userId, NotificationRequest request, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String eventId = request.eventId() == null || request.eventId().isBlank() ? java.util.UUID.randomUUID().toString() : limit(request.eventId().trim(), 96);
        NotificationEvent existing = eventMapper.selectOne(new LambdaQueryWrapper<NotificationEvent>().eq(NotificationEvent::getCompanyId, companyId).eq(NotificationEvent::getEventId, eventId));
        if (existing != null) return existingDelivery(companyId, existing);
        List<FeishuDestination> destinations = request.destinationId() == null
                ? configuredDestinations(companyId, request.eventType()) : List.of(destination(companyId, request.destinationId()));
        if (destinations.isEmpty()) throw new BusinessException(409, "No enabled Feishu destination is configured for this event");
        NotificationEvent event = new NotificationEvent(); event.setCompanyId(companyId); event.setEventId(eventId); event.setEventType(upper(request.eventType())); event.setReferenceNo(limit(request.referenceNo(), 96));
        event.setRequestId(safeRequestId(requestId)); event.setSeverity(upper(request.severity())); event.setSummary(safe(request.summary())); event.setCreatedAt(LocalDateTime.now());
        try { eventMapper.insert(event); } catch (DuplicateKeyException e) { return existingDelivery(companyId, eventMapper.selectOne(new LambdaQueryWrapper<NotificationEvent>().eq(NotificationEvent::getCompanyId, companyId).eq(NotificationEvent::getEventId, eventId))); }
        NotificationOutbox outbox = new NotificationOutbox(); outbox.setCompanyId(companyId); outbox.setEventId(event.getId()); outbox.setStatus("PROCESSING"); outbox.setAttempts(1); outbox.setAvailableAt(LocalDateTime.now()); outbox.setCreatedAt(LocalDateTime.now()); outboxMapper.insert(outbox);
        NotificationDelivery first = null;
        for (FeishuDestination destination : destinations) {
            NotificationDelivery delivery = new NotificationDelivery(); delivery.setCompanyId(companyId); delivery.setEventId(event.getId()); delivery.setDestinationId(destination.getId()); delivery.setAttemptCount(1); delivery.setStatus("SENDING"); delivery.setUpdatedAt(LocalDateTime.now()); deliveryMapper.insert(delivery);
            delivery.setProviderMessageId(adapter.send(event, destination)); delivery.setStatus("SENT"); delivery.setSentAt(LocalDateTime.now()); delivery.setUpdatedAt(LocalDateTime.now()); deliveryMapper.updateById(delivery); if (first == null) first = delivery;
        }
        outbox.setStatus("SENT"); outboxMapper.updateById(outbox);
        return response(event, first);
    }

    public PageResponse<NotificationDeliveryResponse> deliveries(Long userId, int page, int size, String status) {
        long companyId = companyScope.companyIdForUser(userId);
        Page<NotificationDelivery> result = deliveryMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), new LambdaQueryWrapper<NotificationDelivery>().eq(NotificationDelivery::getCompanyId, companyId).eq(status != null && !status.isBlank(), NotificationDelivery::getStatus, upper(status)).orderByDesc(NotificationDelivery::getUpdatedAt).orderByDesc(NotificationDelivery::getId));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords().stream().map(d -> responseForDelivery(companyId, d)).toList());
    }

    @Transactional
    public NotificationDeliveryResponse retry(Long userId, String eventId, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        NotificationEvent event = eventMapper.selectOne(new LambdaQueryWrapper<NotificationEvent>()
                .eq(NotificationEvent::getCompanyId, companyId).eq(NotificationEvent::getEventId, eventId));
        if (event == null) throw new BusinessException(404, "Notification event not found");
        NotificationDelivery delivery = deliveryMapper.selectOne(new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getCompanyId, companyId).eq(NotificationDelivery::getEventId, event.getId()).orderByAsc(NotificationDelivery::getId));
        if (delivery == null) throw new BusinessException(409, "Notification delivery is not available");
        if ("SENT".equalsIgnoreCase(delivery.getStatus())) return response(event, delivery);
        int attempts = delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount();
        delivery.setAttemptCount(attempts + 1); delivery.setStatus("SENDING"); delivery.setLastError(null); delivery.setUpdatedAt(LocalDateTime.now()); deliveryMapper.updateById(delivery);
        try {
            delivery.setProviderMessageId(adapter.send(event, destinationForRetry(companyId, delivery.getDestinationId())));
            delivery.setStatus("SENT"); delivery.setSentAt(LocalDateTime.now()); delivery.setUpdatedAt(LocalDateTime.now()); deliveryMapper.updateById(delivery);
            return response(event, delivery);
        } catch (RuntimeException failure) {
            delivery.setStatus("FAILED"); delivery.setLastError(safe(failure.getMessage())); delivery.setUpdatedAt(LocalDateTime.now()); deliveryMapper.updateById(delivery);
            throw new BusinessException(502, "模拟通知重试失败，请根据请求编号处理");
        }
    }

    private List<FeishuDestination> configuredDestinations(long companyId, String eventType) {
        List<Long> ids = policyMapper.selectList(new LambdaQueryWrapper<NotificationPolicy>().eq(NotificationPolicy::getCompanyId, companyId).eq(NotificationPolicy::getEventType, upper(eventType)).eq(NotificationPolicy::getEnabled, true)).stream().map(NotificationPolicy::getDestinationId).toList();
        if (ids.isEmpty()) return List.of();
        return destinationMapper.selectList(new LambdaQueryWrapper<FeishuDestination>().eq(FeishuDestination::getCompanyId, companyId).eq(FeishuDestination::getEnabled, true).in(FeishuDestination::getId, ids));
    }
    private FeishuDestination destination(long companyId, Long id) { FeishuDestination d = destinationMapper.selectOne(new LambdaQueryWrapper<FeishuDestination>().eq(FeishuDestination::getCompanyId, companyId).eq(FeishuDestination::getId, id).eq(FeishuDestination::getEnabled, true)); if (d == null) throw new BusinessException(404, "Feishu destination not found"); return d; }
    private FeishuDestination destinationForRetry(long companyId, Long id) { return destinationMapper.selectOne(new LambdaQueryWrapper<FeishuDestination>().eq(FeishuDestination::getCompanyId, companyId).eq(FeishuDestination::getId, id)); }
    private NotificationDeliveryResponse existingDelivery(long companyId, NotificationEvent event) { NotificationDelivery d = deliveryMapper.selectOne(new LambdaQueryWrapper<NotificationDelivery>().eq(NotificationDelivery::getCompanyId, companyId).eq(NotificationDelivery::getEventId, event.getId()).orderByAsc(NotificationDelivery::getId)); if (d == null) throw new BusinessException(409, "Notification event is already being processed"); return response(event, d); }
    private NotificationDeliveryResponse response(NotificationEvent event, NotificationDelivery d) { return new NotificationDeliveryResponse(event.getEventId(), event.getEventType(), event.getReferenceNo(), event.getSeverity(), d.getStatus(), d.getAttemptCount(), d.getProviderMessageId(), event.getRequestId(), event.getCreatedAt(), d.getSentAt(), d.getLastError()); }
    private NotificationDeliveryResponse responseForDelivery(long companyId, NotificationDelivery d) { NotificationEvent e = eventMapper.selectOne(new LambdaQueryWrapper<NotificationEvent>().eq(NotificationEvent::getCompanyId, companyId).eq(NotificationEvent::getId, d.getEventId())); return response(e, d); }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String limit(String value, int max) { if (value == null) return null; String v = value.trim(); return v.substring(0, Math.min(max, v.length())); }
    private String safe(String value) { String v = limit(value, 500); return v == null ? "" : v.replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]").replaceAll("(?<!\\d)\\d{8,}(?!\\d)", "****"); }
    private String safeRequestId(String value) { if (value == null || value.isBlank()) return java.util.UUID.randomUUID().toString(); if (!value.matches("[A-Za-z0-9._:-]{1,64}")) throw new BusinessException(400, "X-Request-Id format is invalid"); return value; }
}
