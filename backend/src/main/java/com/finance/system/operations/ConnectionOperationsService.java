package com.finance.system.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.bankdata.scope.CompanyScopeService;
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
import com.finance.system.operations.dto.OperationTaskResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConnectionOperationsService {

    private static final List<String> DATA_RESOURCES = List.of(
            "balances", "statements", "receipts", "reconciliation-statements", "payments", "payroll");
    private static final List<String> SUPPORTED_PROVIDER_TYPES = List.of("SIMULATED");

    private final ConnectionProfileMapper profileMapper;
    private final ConnectionOperationTaskMapper taskMapper;
    private final ConnectionOperationLogMapper logMapper;
    private final CompanyScopeService companyScope;

    public ConnectionOperationsService(ConnectionProfileMapper profileMapper,
                                       ConnectionOperationTaskMapper taskMapper,
                                       ConnectionOperationLogMapper logMapper,
                                       CompanyScopeService companyScope) {
        this.profileMapper = profileMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.companyScope = companyScope;
    }

    public ConnectionConfigurationResponse configuration(Long userId, String section) {
        long companyId = companyScope.companyIdForUser(userId);
        validateSection(section);
        List<ConnectionSummaryResponse> connections = profiles(companyId).stream().map(this::toSummary).toList();
        return new ConnectionConfigurationResponse(
                false,
                "NOT_ENABLED",
                "连接配置仅保留安全元数据；本期未启用外部连接，不读取或保存密钥。",
                SUPPORTED_PROVIDER_TYPES,
                connections);
    }

    public ConnectionOverviewResponse overview(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<ConnectionProfile> profiles = profiles(companyId);
        List<ConnectionSummaryResponse> connections = profiles.stream().map(this::toSummary).toList();
        boolean enabled = profiles.stream().anyMatch(profile -> Boolean.TRUE.equals(profile.getEnabled()));
        String status = profiles.isEmpty() ? "NOT_ENABLED" : (enabled ? "SIMULATED" : "DISABLED");
        String message = profiles.isEmpty()
                ? "当前没有已配置的连接，未执行任何外部调用。"
                : "仅展示服务端连接元数据，未执行真实银行或金蝶调用。";
        return new ConnectionOverviewResponse(enabled, status, message, connections);
    }

    public PageResponse<OperationTaskResponse> tasks(Long userId, int page, int size, String connectionCode,
                                                       String status, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        Long connectionId = findConnectionId(companyId, connectionCode);
        LambdaQueryWrapper<ConnectionOperationTask> query = new LambdaQueryWrapper<ConnectionOperationTask>()
                .eq(ConnectionOperationTask::getCompanyId, companyId)
                .eq(connectionId != null, ConnectionOperationTask::getConnectionId, connectionId)
                .eq(status != null && !status.isBlank(), ConnectionOperationTask::getStatus, normalize(status))
                .eq(requestId != null && !requestId.isBlank(), ConnectionOperationTask::getRequestId, requestId.trim())
                .orderByDesc(ConnectionOperationTask::getCreatedAt)
                .orderByDesc(ConnectionOperationTask::getId);
        if (connectionCode != null && !connectionCode.isBlank() && connectionId == null) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        Page<ConnectionOperationTask> result = taskMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        Map<Long, ConnectionProfile> profiles = profileMap(companyId, result.getRecords().stream()
                .map(ConnectionOperationTask::getConnectionId).toList());
        List<OperationTaskResponse> records = result.getRecords().stream()
                .map(task -> toTaskResponse(task, profiles.get(task.getConnectionId())))
                .toList();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
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
            throw new BusinessException(404, "Data query capability not found");
        }
        return new DataQueryCapabilityResponse(
                normalized,
                false,
                "NOT_ENABLED",
                "本期仅提供服务端能力状态；未连接外部数据源，不返回虚构的余额、流水或业务记录。"
        );
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

    private ConnectionSummaryResponse toSummary(ConnectionProfile profile) {
        return new ConnectionSummaryResponse(profile.getConnectionCode(), profile.getDisplayName(),
                profile.getProviderType(), Boolean.TRUE.equals(profile.getEnabled()), profile.getStatus(),
                profile.getLastCheckedAt());
    }

    private OperationTaskResponse toTaskResponse(ConnectionOperationTask task, ConnectionProfile profile) {
        return new OperationTaskResponse(task.getTaskNo(), task.getTaskType(),
                profile == null ? null : profile.getConnectionCode(), task.getStatus(), task.getRequestId(),
                task.getSummary(), task.getStartedAt(), task.getCompletedAt(), task.getCreatedAt());
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
