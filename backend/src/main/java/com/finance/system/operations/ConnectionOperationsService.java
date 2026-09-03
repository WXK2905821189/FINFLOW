package com.finance.system.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.adapter.cmb.CmbAdapterProperties;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConnectionOperationsService {

    private static final List<String> DATA_RESOURCES = List.of(
            "balances", "statements");

    private final ConnectionProfileMapper profileMapper;
    private final ConnectionOperationTaskMapper taskMapper;
    private final ConnectionOperationLogMapper logMapper;
    private final CompanyScopeService companyScope;
    private final CmbAdapterProperties cmbProperties;

    public ConnectionOperationsService(ConnectionProfileMapper profileMapper,
                                       ConnectionOperationTaskMapper taskMapper,
                                       ConnectionOperationLogMapper logMapper,
                                       CompanyScopeService companyScope,
                                       CmbAdapterProperties cmbProperties) {
        this.profileMapper = profileMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.companyScope = companyScope;
        this.cmbProperties = cmbProperties;
    }

    public ConnectionConfigurationResponse configuration(Long userId, String section) {
        long companyId = companyScope.companyIdForUser(userId);
        validateSection(section);
        boolean cmbReal = cmbProperties.isRealEnabled();
        List<ConnectionSummaryResponse> connections = profiles(companyId).stream().map(this::toSummary).toList();
        return new ConnectionConfigurationResponse(
                cmbReal,
                cmbReal ? "REAL" : "NOT_CONFIGURED",
                cmbReal
                        ? "已连接真实银行直联（招行 CMB）；密钥仅存于服务端环境变量，不落库、不返回。"
                        : "真实银行直联未连接：服务端未启用真实银行适配器（需配置 CMB 并开启 BANKDATA_CMB_REAL_ENABLED）。",
                cmbReal ? List.of("CMB") : List.of(),
                connections);
    }

    public ConnectionOverviewResponse overview(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<ConnectionProfile> profiles = profiles(companyId);
        List<ConnectionSummaryResponse> connections = profiles.stream().map(this::toSummary).toList();
        boolean cmbReal = cmbProperties.isRealEnabled();
        String status = cmbReal ? "REAL" : (profiles.isEmpty() ? "NOT_ENABLED" : "DISABLED");
        String message = cmbReal
                ? "已连接真实银行直联（招行 CMB）：余额/流水查询走真实银行接口。"
                : "真实银行直联未连接：未启用真实银行适配器，查询页将明确标红提示。";
        return new ConnectionOverviewResponse(cmbReal, status, message, connections);
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
        boolean cmbReal = cmbProperties.isRealEnabled();
        return new DataQueryCapabilityResponse(
                normalized,
                cmbReal,
                cmbReal ? "REAL" : "NOT_CONFIGURED",
                cmbReal
                        ? "已连接真实银行直联（招行 CMB），余额/流水为真实银行数据。"
                        : "真实银行直联未连接：服务端未启用真实银行适配器。"
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
