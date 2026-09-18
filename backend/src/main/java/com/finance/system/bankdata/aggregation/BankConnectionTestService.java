package com.finance.system.bankdata.aggregation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bank.dto.BankConnectionTestResponse;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankExchangeEvidence;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.rbac.RbacService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read-only bank connectivity probe backing the UI "test connection" button.
 *
 * <p>Adapter routing matches the sync pipeline exactly: no explicit adapter code, the
 * account's own bank code decides ({@link BankDataAdapterRegistry#resolveCode}); the one
 * collect call goes through {@link BankAdapterCallExecutor} so rate limit, timeout and
 * retry semantics are identical to a real sync's first page.</p>
 *
 * <p>The probe persists nothing — no sync task, no projection row, no raw message, no
 * sync log (bank_data_sync_log.task_id is NOT NULL and a probe has no task). The bank
 * exchange still produces its wire evidence; only the sanitized summary fields are
 * returned to the caller.</p>
 *
 * <p>An account whose bank code has no registered adapter answers {@link #DISABLED} with
 * HTTP 200 instead of an error: "this bank is not wired in this deployment" is a result
 * the operator needs to read off the probe, not a malformed request.</p>
 */
@Service
public class BankConnectionTestService {

    /** Probe results the UI can render without knowing bank internals. */
    public static final String CONNECTED = "CONNECTED";
    public static final String FAILED = "FAILED";
    public static final String DISABLED = "DISABLED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String PENDING = "PENDING";

    private static final String TASK_NO = "TEST-CONN";

    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final BankDataAdapterRegistry registry;
    private final BankAdapterCallExecutor executor;

    public BankConnectionTestService(BankAccountMapper bankAccountMapper, CompanyMapper companyMapper,
                                     CompanyScopeService companyScope, RbacService rbacService,
                                     BankDataAdapterRegistry registry, BankAdapterCallExecutor executor) {
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.registry = registry;
        this.executor = executor;
    }

    public BankConnectionTestResponse test(Long userId, Long accountId) {
        BankAccount account = requireVisibleAccount(userId, accountId);
        LocalDateTime now = LocalDateTime.now();

        BankDataAdapter adapter;
        try {
            adapter = registry.require(registry.resolveCode(null, account.getBankCode()));
        } catch (BusinessException unavailable) {
            // The account's own bank code has no registered adapter. Unlike the sync trigger
            // (where adapterCode is client-supplied and 400 is right), nothing here comes from
            // the caller — this is a server-side deployment state (the bank is still in test /
            // not wired in production, or the real-adapter switch is off). The probe exists to
            // *report* that state, so answer 200 + DISABLED, which the UI already renders as a
            // warning ("真实适配器未启用"), instead of an error status the operator cannot act on.
            return new BankConnectionTestResponse(DISABLED,
                    "未启用 " + account.getBankCode() + " 直联适配器：本环境未注册该银行的适配器"
                            + "（该行可能仍在测试阶段，或真实适配器开关未开启）。本次未向银行发起任何请求。",
                    null, null, null, null, null, null, null, now);
        }

        String requestId = "test-conn-" + UUID.randomUUID();
        BankDataSyncContext context = new BankDataSyncContext(account.getCompanyId(), null, account.getId(),
                TASK_NO, requestId, now.toLocalDate().minusDays(1).atStartOfDay(), now,
                1, null, 100, "STATEMENT");

        BankAdapterCallOutcome outcome = executor.invoke(adapter, context);
        if (outcome.terminalStatus() != null) {
            return new BankConnectionTestResponse(terminalResult(outcome.terminalStatus()),
                    outcome.safeSummary(), null, null, null, null, null, null, null, now);
        }
        return describe(account, outcome.collection(), now);
    }

    private BankConnectionTestResponse describe(BankAccount account, BankDataCollection collection,
                                                LocalDateTime now) {
        if (collection == null) {
            return new BankConnectionTestResponse(FAILED, "Adapter returned no collection", null,
                    null, null, null, null, null, null, now);
        }
        BankDataStatus status = BankDataStatus.fromVendor(collection.status());
        BankExchangeEvidence evidence = collection.evidence();
        String operation = evidence == null ? null : evidence.funcode();
        Long durationMs = evidence == null ? null : evidence.durationMs();
        String endpoint = evidence == null ? null : evidence.endpoint();

        if (status != BankDataStatus.SUCCESS) {
            String message = "银行返回未成功状态：" + collection.bankStatusCode() + " / " + collection.status();
            return new BankConnectionTestResponse(FAILED, message, collection.bankRequestNo(),
                    operation, durationMs, endpoint, null, null,
                    collection.entries() == null ? 0 : collection.entries().size(), now);
        }

        BankDataBalanceEntry balance = collection.balances() == null || collection.balances().isEmpty()
                ? null : collection.balances().get(0);
        String message = "连接成功：银行已正常应答"
                + (balance == null ? "" : "，可用余额 " + balance.availableBalance()
                        + (balance.currency() == null ? "" : " " + balance.currency()));
        return new BankConnectionTestResponse(CONNECTED, message, collection.bankRequestNo(),
                operation, durationMs, endpoint,
                balance == null ? null : balance.availableBalance(),
                balance == null ? null : balance.currency(),
                collection.entries() == null ? 0 : collection.entries().size(), now);
    }

    private String terminalResult(BankDataStatus terminalStatus) {
        return switch (terminalStatus) {
            case TIMEOUT -> TIMEOUT;
            case PENDING -> PENDING;
            case FAILED -> FAILED;
            default -> DISABLED;
        };
    }

    /**
     * Same visibility rule as {@code BankAccountService.listResponses}: single-company users
     * are scoped to their own company; cross-company (V24) holders reach every ACTIVE company.
     */
    private BankAccount requireVisibleAccount(Long userId, Long accountId) {
        boolean crossCompany = rbacService.permissionCodesForUser(userId)
                .contains("bankdata:cross-company:view");
        LambdaQueryWrapper<BankAccount> query = new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, accountId);
        if (crossCompany) {
            query.in(BankAccount::getCompanyId, companyMapper.selectList(
                            new LambdaQueryWrapper<Company>().eq(Company::getStatus, "ACTIVE"))
                    .stream().map(Company::getId).toList());
        } else {
            query.eq(BankAccount::getCompanyId, companyScope.companyIdForUser(userId));
        }
        BankAccount account = bankAccountMapper.selectOne(query);
        if (account == null) {
            throw new BusinessException(404, "Bank account not found in your visible companies");
        }
        return account;
    }
}
