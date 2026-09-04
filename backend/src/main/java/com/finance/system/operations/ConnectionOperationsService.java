package com.finance.system.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.ConnectionOperationLog;
import com.finance.system.domain.entity.ConnectionOperationTask;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.ConnectionOperationLogMapper;
import com.finance.system.domain.mapper.ConnectionOperationTaskMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import com.finance.system.operations.dto.ConnectionConfigurationResponse;
import com.finance.system.operations.dto.ConnectionOverviewResponse;
import com.finance.system.operations.dto.ConnectionSummaryResponse;
import com.finance.system.operations.dto.DataQueryCapabilityResponse;
import com.finance.system.operations.dto.OperationLogResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Connection/直联 status for the operations console.
 *
 * <p>Provider status is derived from adapter assembly (a REAL-mode adapter bean only exists when
 * its per-bank switch is on), NOT from a single CMB flag and NOT from a persisted status column:
 * a stored {@code connection_profile.status} can drift from reality, so it is only ever shown as
 * metadata. Per-account status is a separate concern handled by
 * {@code AccountDirectStatusService} — a bank can be connected while an individual account is
 * still unverified.
 */
@Service
public class ConnectionOperationsService {

    private static final List<String> DATA_RESOURCES = List.of(
            "balances", "statements");

    private final ConnectionProfileMapper profileMapper;
    private final ConnectionOperationTaskMapper taskMapper;
    private final ConnectionOperationLogMapper logMapper;
    private final CompanyScopeService companyScope;
    private final BankDataAdapterRegistry adapterRegistry;

    public ConnectionOperationsService(ConnectionProfileMapper profileMapper,
                                       ConnectionOperationTaskMapper taskMapper,
                                       ConnectionOperationLogMapper logMapper,
                                       CompanyScopeService companyScope,
                                       BankDataAdapterRegistry adapterRegistry) {
        this.profileMapper = profileMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.companyScope = companyScope;
        this.adapterRegistry = adapterRegistry;
    }

    public ConnectionConfigurationResponse configuration(Long userId, String section) {
        long companyId = companyScope.companyIdForUser(userId);
        validateSection(section);
        Set<String> realProviders = realProviders();
        List<ConnectionSummaryResponse> connections = profiles(companyId).stream()
                .map(profile -> toSummary(profile, realProviders)).toList();
        return new ConnectionConfigurationResponse(
                !realProviders.isEmpty(),
                realProviders.isEmpty() ? "NOT_CONFIGURED" : "REAL",
                realProviders.isEmpty()
                        ? "真实银行直联未连接：服务端未装配任何真实银行适配器（需为对应银行开启 real-enabled 配置）。"
                        : "已连接真实银行直联（" + String.join("、", realProviders) + "）；密钥仅存于服务端环境变量，不落库、不返回。",
                List.copyOf(realProviders),
                connections);
    }

    public ConnectionOverviewResponse overview(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<ConnectionProfile> profiles = profiles(companyId);
        Set<String> realProviders = realProviders();
        List<ConnectionSummaryResponse> connections = profiles.stream()
                .map(profile -> toSummary(profile, realProviders)).toList();
        boolean connected = !realProviders.isEmpty();
        String status = connected ? "REAL" : (profiles.isEmpty() ? "NOT_ENABLED" : "DISABLED");
        String message = connected
                ? "已连接真实银行直联（" + String.join("、", realProviders) + "）：余额/流水查询走真实银行接口；"
                        + "具体到每个账户是否已验证可查，请在银行账户页查看账户级直联状态。"
                : "真实银行直联未连接：服务端未装配任何真实银行适配器，查询页将明确标红提示。";
        return new ConnectionOverviewResponse(connected, status, message, connections);
    }

    public PageResponse<OperationLogResponse> logs(Long userId, int page, int size, String connectionCode,
                                                    String status, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        Long connectionId = findConnectionId(companyId, connectionCode);
        if (connectionCode != null && !connectionCode.isBlank() && connectionId == null) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        LambdaQueryWrapper<ConnectionOperationLog> query = new LambdaQueryWrapper<ConnectionOperationLog>()
                .eq(ConnectionOperationLog::getCompanyId, companyId)
                .eq(status != null && !status.isBlank(), ConnectionOperationLog::getResult, normalize(status))
                .eq(requestId != null && !requestId.isBlank(), ConnectionOperationLog::getRequestId, requestId.trim())
                .orderByDesc(ConnectionOperationLog::getOccurredAt)
                .orderByDesc(ConnectionOperationLog::getId);
        if (connectionId != null) {
            List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<ConnectionOperationTask>()
                            .eq(ConnectionOperationTask::getCompanyId, companyId)
                            .eq(ConnectionOperationTask::getConnectionId, connectionId))
                    .stream().map(ConnectionOperationTask::getId).toList();
            if (taskIds.isEmpty()) {
                return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
            }
            query.in(ConnectionOperationLog::getTaskId, taskIds);
        }
        Page<ConnectionOperationLog> result = logMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toLogResponse).toList());
    }

    public DataQueryCapabilityResponse dataCapability(String resource) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!DATA_RESOURCES.contains(normalized)) {
            throw new BusinessException(404,
                    "银行侧未开通该功能；当前仅支持 balances(余额查询) / statements(流水查询)");
        }
        Set<String> realProviders = realProviders();
        boolean connected = !realProviders.isEmpty();
        return new DataQueryCapabilityResponse(
                normalized,
                connected,
                connected ? "REAL" : "NOT_CONFIGURED",
                connected
                        ? "已连接真实银行直联（" + String.join("、", realProviders) + "），余额/流水为真实银行数据。"
                        : "真实银行直联未连接：服务端未装配任何真实银行适配器。"
        );
    }

    /** Banks with a REAL-mode adapter bean, i.e. actually wired for real traffic. */
    private Set<String> realProviders() {
        return new TreeSet<>(adapterRegistry.realAdapterCodes());
    }

    private List<ConnectionProfile> profiles(long companyId) {
        return profileMapper.selectList(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getCompanyId, companyId)
                .orderByAsc(ConnectionProfile::getConnectionCode)
                .orderByAsc(ConnectionProfile::getId));
    }

    private Long findConnectionId(long companyId, String connectionCode) {
        if (connectionCode == null || connectionCode.isBlank()) return null;
        ConnectionProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getCompanyId, companyId)
                .eq(ConnectionProfile::getConnectionCode, connectionCode.trim()));
        return profile == null ? null : profile.getId();
    }

    private Map<Long, ConnectionProfile> profileMap(long companyId, List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return profileMapper.selectList(new LambdaQueryWrapper<ConnectionProfile>()
                        .eq(ConnectionProfile::getCompanyId, companyId)
                        .in(ConnectionProfile::getId, distinctIds)).stream()
                .collect(Collectors.toMap(ConnectionProfile::getId, Function.identity()));
    }

    /**
     * Row status is derived from adapter assembly for this profile's provider instead of the
     * persisted status column: a bank that is not wired must read NOT_CONNECTED regardless of
     * what the profile row says.
     */
    private ConnectionSummaryResponse toSummary(ConnectionProfile profile, Set<String> realProviders) {
        String provider = normalizeOrNull(profile.getProviderType());
        boolean real = provider != null && realProviders.contains(provider);
        return new ConnectionSummaryResponse(profile.getConnectionCode(), profile.getDisplayName(),
                profile.getProviderType(), real, real ? "REAL" : "NOT_CONNECTED",
                profile.getLastCheckedAt());
    }

    private OperationLogResponse toLogResponse(ConnectionOperationLog log) {
        return new OperationLogResponse(log.getTaskId(), log.getLevel(), log.getEventType(), log.getResult(),
                log.getRequestId(), sanitizeLogMessage(log.getMessage()), log.getOccurredAt());
    }

    private void validateSection(String section) {
        if (section == null || section.isBlank()) return;
        if (!List.of("applications", "contracts", "preferences").contains(section.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(400, "Unsupported connection configuration section");
        }
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private long boundedSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    private String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) return message;
        String sanitized = message
                .replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                .replaceAll("(?<!\\d)\\d{8,}(?!\\d)", "****");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}
