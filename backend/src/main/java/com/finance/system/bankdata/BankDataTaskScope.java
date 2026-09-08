package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.common.tenant.CompanyScopeService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared task-scope / lineage / account-label helpers for the bank-data read side.
 *
 * <p>Both {@link BankDataQueryService} (on-screen projection) and
 * {@link BankDataExportService} (CSV export) resolve "which sync tasks may this user see"
 * and decorate rows with task lineage the same way, so the logic lives here once.
 * Also owns the REAL-adapter computation: an adapter counts as a real direct link only
 * when its execution mode is {@code REAL} (mock/simulated adapters never project).</p>
 */
@Component
public class BankDataTaskScope {

    /** 跨公司主体查询权限（V24）：持有者可在余额/流水投影中查看全部 ACTIVE 公司的数据。 */
    public static final String CROSS_COMPANY_PERMISSION = "bankdata:cross-company:view";

    private final CompanyScopeService companyScope;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankAccountMapper bankAccountMapper;
    private final RbacService rbacService;
    private final BankDataSyncResponseAssembler responseAssembler;
    /** True when at least one REAL (non-simulated) bank adapter bean is active in this deployment. */
    private final boolean realDirectConnected;
    /** Adapter codes of the active REAL adapters (e.g. CMB); empty when直联未连接. */
    private final List<String> realAdapterCodes;

    public BankDataTaskScope(CompanyScopeService companyScope,
                             BankDataSyncTaskMapper taskMapper,
                             BankAccountMapper bankAccountMapper,
                             RbacService rbacService,
                             BankDataSyncResponseAssembler responseAssembler,
                             List<BankDataAdapter> bankDataAdapters) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.rbacService = rbacService;
        this.responseAssembler = responseAssembler;
        List<BankDataAdapter> realAdapters = bankDataAdapters == null ? List.of() : bankDataAdapters.stream()
                .filter(adapter -> adapter.executionMode() == BankAdapterExecutionMode.REAL)
                .toList();
        this.realAdapterCodes = realAdapters.stream().map(BankDataAdapter::adapterCode).toList();
        this.realDirectConnected = !realAdapterCodes.isEmpty();
    }

    public boolean realDirectConnected() {
        return realDirectConnected;
    }

    public List<String> realAdapterCodes() {
        return realAdapterCodes;
    }

    /** Task ids produced by the active REAL adapters only (mock/simulated tasks are never projected). */
    public List<Long> realTaskIds(Collection<Long> companyIds) {
        if (realAdapterCodes.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .in(BankDataSyncTask::getAdapterCode, realAdapterCodes)
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    public List<Long> scopedTaskIds(Collection<Long> companyIds, String syncJobNo, String requestId) {
        if ((syncJobNo == null || syncJobNo.isBlank()) && (requestId == null || requestId.isBlank())) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .eq(syncJobNo != null && !syncJobNo.isBlank(), BankDataSyncTask::getTaskNo, syncJobNo == null ? null : syncJobNo.trim())
                        .eq(requestId != null && !requestId.isBlank(), BankDataSyncTask::getRequestId, requestId == null ? null : requestId.trim())
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    public Map<Long, BankDataSyncTask> tasksById(Collection<Long> companyIds, List<Long> taskIds) {
        List<Long> ids = taskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .in(BankDataSyncTask::getId, ids)
                        .select(BankDataSyncTask::getId, BankDataSyncTask::getTaskNo,
                                BankDataSyncTask::getRequestId, BankDataSyncTask::getStatus,
                                BankDataSyncTask::getCompanyId))
                .stream().collect(Collectors.toMap(BankDataSyncTask::getId, java.util.function.Function.identity()));
    }

    public String taskNo(BankDataSyncTask task) {
        return task == null ? null : task.getTaskNo();
    }

    public String requestId(BankDataSyncTask task) {
        return task == null ? null : task.getRequestId();
    }

    public String taskStatus(BankDataSyncTask task) {
        return task == null ? null : task.getStatus();
    }

    /**
     * Our side of a row: the masked account number plus the account name. The bank's statement
     * rows never carry our account name, so it is joined from {@code bank_account} — needed by
     * the projection page and by the export, which mirrors the bank's own export layout
     * including its 账号名称 column.
     */
    public Map<Long, AccountLabel> accountLabels(Collection<Long> companyIds, List<Long> accountIds) {
        List<Long> ids = accountIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                        .in(BankAccount::getCompanyId, companyIds)
                        .in(BankAccount::getId, ids)
                        .select(BankAccount::getId, BankAccount::getAccountNumber, BankAccount::getAccountName))
                .stream()
                .collect(Collectors.toMap(BankAccount::getId, account -> new AccountLabel(
                        responseAssembler.maskAccount(account.getAccountNumber()), account.getAccountName())));
    }

    public record AccountLabel(String maskedNumber, String name) {
    }

    public boolean hasCrossCompanyPermission(Long userId) {
        return rbacService.permissionCodesForUser(userId).contains(CROSS_COMPANY_PERMISSION);
    }

    /** Kept for callers that still resolve the user's own company through the scope service. */
    public long companyIdForUser(Long userId) {
        return companyScope.companyIdForUser(userId);
    }
}
