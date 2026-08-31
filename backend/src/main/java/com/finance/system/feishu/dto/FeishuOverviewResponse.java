package com.finance.system.feishu.dto;
import java.util.List;
public record FeishuOverviewResponse(boolean enabled, String status, String message, List<ConnectionItem> connections, List<DestinationItem> destinations, List<PolicyItem> policies) {
    public record ConnectionItem(Long id, String connectionCode, String displayName, String tenantAlias, String mode, String status) {}
    public record DestinationItem(Long id, Long connectionId, String destinationType, String destinationKey, String displayName, boolean enabled) {}
    public record PolicyItem(Long id, String eventType, Long destinationId, boolean enabled, String templateVersion) {}
}
