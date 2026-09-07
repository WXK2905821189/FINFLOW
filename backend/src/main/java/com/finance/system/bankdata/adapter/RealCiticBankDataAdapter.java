package com.finance.system.bankdata.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.CiticBalanceQuery;
import com.finance.system.bankdata.adapter.citic.CiticBalanceResult;
import com.finance.system.bankdata.adapter.citic.CiticEnvelopeCodec;
import com.finance.system.bankdata.adapter.citic.CiticRequestXml;
import com.finance.system.bankdata.adapter.citic.CiticResponseXml;
import com.finance.system.bankdata.adapter.citic.CiticRowMapper;
import com.finance.system.bankdata.adapter.citic.CiticStatementPage;
import com.finance.system.bankdata.adapter.citic.CiticStatementQuery;
import com.finance.system.bankdata.adapter.citic.dlink.CiticDlinkSdk;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Authorized CITIC bank data adapter (DLBALQRY balance + DLTRNALL statement query).
 *
 * <p>One {@link #collect(BankDataSyncContext)} call maps to a single DLTRNALL page and,
 * on the first page of a window, also one DLBALQRY snapshot. The vendor's 20-row page
 * limit wins over the caller's page size. Balance is queried once per window because it
 * is a real-time snapshot, while statements page until the vendor returns a short page.</p>
 *
 * <p>Activated only when {@code bankdata.adapter.citic.real-enabled=true} and guarded by
 * the aggregation call executor (rate limit / timeout / retry).</p>
 */
@Component
@ConditionalOnProperty(prefix = "bankdata.adapter.citic", name = "real-enabled", havingValue = "true")
public class RealCiticBankDataAdapter implements BankDataAdapter {

    static final String ADAPTER_CODE = "CITIC";

    private final CiticAdapterProperties properties;
    private final CiticDlinkSdk sdk;
    private final BankAccountMapper bankAccountMapper;

    public RealCiticBankDataAdapter(CiticAdapterProperties properties, CiticDlinkSdk sdk,
                                    BankAccountMapper bankAccountMapper) {
        this.properties = properties;
        this.sdk = sdk;
        this.bankAccountMapper = bankAccountMapper;
    }

    @Override
    public String adapterCode() {
        return ADAPTER_CODE;
    }

    @Override
    public BankAdapterExecutionMode executionMode() {
        return BankAdapterExecutionMode.REAL;
    }

    @Override
    public BankDataCollection collect(BankDataSyncContext context) {
        if (context == null || context.bankAccountId() == null) {
            throw new BusinessException(400, "CITIC collect requires a bank account scope");
        }
        if (context.windowStart() == null || context.windowEnd() == null) {
            throw new BusinessException(400, "CITIC collect requires a sync window");
        }
        int page = context.pageNumber() == null || context.pageNumber() < 1 ? 1 : context.pageNumber();
        String accountNo = resolveAccountNumber(context);
        String userName = requireUserName();
        String requestId = context.requestId() == null || context.requestId().isBlank()
                ? "finflow" : context.requestId();
        String bankRequestNo = CiticEnvelopeCodec.clientId(requestId, page);
        int pageSize = properties.getPageSize();

        // Balance is a real-time snapshot: query it only on the first page of the window.
        int startRecord = startRecord(context, page);
        if (page == 1) {
            BalanceCall balance = queryBalance(accountNo, userName, requestId, page);
            String balanceStatus = balance.page().status();
            if (balanceStatus != null && !balanceStatus.isBlank() && !CiticRowMapper.SUCCESS.equals(balanceStatus)) {
                return failed(bankRequestNo, balanceStatus, null, balance.auxiliary());
            }
            List<BankDataBalanceEntry> balances = CiticRowMapper.acceptedBalanceRows(balance.page().rows(),
                    context.bankAccountId(), bankRequestNo);
            StatementCall statements = queryStatement(accountNo, userName, requestId, page,
                    context.windowStart().toLocalDate(), context.windowEnd().toLocalDate(),
                    startRecord, pageSize);
            BankExchangeEvidence evidence = evidence(statements, balance.auxiliary());
            if (statements.page().status() == null) {
                return failed(bankRequestNo, "UNKNOWN", evidence, null);
            }
            if (!CiticRowMapper.SUCCESS.equals(statements.page().status())
                    && !CiticRowMapper.NO_TRANSACTION.equals(statements.page().status())) {
                return failed(bankRequestNo, statements.page().status(), evidence, null);
            }
            List<BankDataEntry> entries = CiticRowMapper.toEntries(statements.page(), context.bankAccountId(),
                    bankRequestNo);
            boolean noTransaction = CiticRowMapper.NO_TRANSACTION.equals(statements.page().status());
            boolean hasMore = !noTransaction && fullPage(statements.page(), pageSize);
            return page(bankRequestNo, entries, balances, hasMore, startRecord, pageSize, evidence);
        }

        StatementCall statements = queryStatement(accountNo, userName, requestId, page,
                context.windowStart().toLocalDate(), context.windowEnd().toLocalDate(),
                startRecord, pageSize);
        BankExchangeEvidence evidence = evidence(statements, null);
        if (statements.page().status() == null) {
            return failed(bankRequestNo, "UNKNOWN", evidence, null);
        }
        if (!CiticRowMapper.SUCCESS.equals(statements.page().status())
                && !CiticRowMapper.NO_TRANSACTION.equals(statements.page().status())) {
            return failed(bankRequestNo, statements.page().status(), evidence, null);
        }
        List<BankDataEntry> entries = CiticRowMapper.toEntries(statements.page(), context.bankAccountId(),
                bankRequestNo);
        boolean noTransaction = CiticRowMapper.NO_TRANSACTION.equals(statements.page().status());
        boolean hasMore = !noTransaction && fullPage(statements.page(), pageSize);
        return page(bankRequestNo, entries, List.of(), hasMore, startRecord, pageSize, evidence);
    }

    /** One balance exchange plus its wire evidence (page-1 DLBALQRY snapshot). */
    private record BalanceCall(CiticBalanceResult page, BankExchangeEvidence.AuxiliaryCall auxiliary) {
    }

    /** One statement exchange plus its wire facts. */
    private record StatementCall(CiticStatementPage page, String responseXml, String businessXml, long durationMs) {
    }

    private BalanceCall queryBalance(String accountNo, String userName, String requestId, int page) {
        String businessXml = CiticRequestXml.buildBalanceQuery(userName,
                new CiticBalanceQuery(List.of(accountNo)));
        long start = System.nanoTime();
        String responseXml = sdk.exchange("DLBALQRY", businessXml, CiticEnvelopeCodec.clientId(requestId, page));
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        BankExchangeEvidence.AuxiliaryCall auxiliary = new BankExchangeEvidence.AuxiliaryCall("DLBALQRY",
                businessXml, responseXml, durationMs, null);
        return new BalanceCall(CiticResponseXml.parseBalanceQuery(responseXml), auxiliary);
    }

    private StatementCall queryStatement(String accountNo, String userName, String requestId, int page,
                                         LocalDate windowStart, LocalDate windowEnd, int startRecord,
                                         int pageSize) {
        CiticStatementQuery query = new CiticStatementQuery(accountNo, windowStart, windowEnd, pageSize,
                startRecord, properties.getControlFlag());
        String businessXml = CiticRequestXml.buildStatementQuery(userName, query);
        long start = System.nanoTime();
        String responseXml = sdk.exchange("DLTRNALL", businessXml, CiticEnvelopeCodec.clientId(requestId, page));
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        return new StatementCall(CiticResponseXml.parseStatementPage(responseXml), responseXml, businessXml,
                durationMs);
    }

    /** Assembles the ODS evidence for one collect call (primary statement exchange + optional balance). */
    private BankExchangeEvidence evidence(StatementCall statements, BankExchangeEvidence.AuxiliaryCall auxiliary) {
        String endpoint;
        try {
            endpoint = properties.getSdk().getUrl();
        } catch (RuntimeException e) {
            endpoint = null;
        }
        return new BankExchangeEvidence(endpoint, "DLTRNALL", statements.businessXml(),
                statements.responseXml(), statements.durationMs(), null, auxiliary);
    }

    private BankDataCollection page(String bankRequestNo, List<BankDataEntry> entries,
                                    List<BankDataBalanceEntry> balances, boolean hasMore,
                                    int startRecord, int pageSize, BankExchangeEvidence evidence) {
        String nextCursor = hasMore ? String.valueOf(startRecord + pageSize) : null;
        return new BankDataCollection(bankRequestNo, entries, balances, hasMore, nextCursor,
                CiticRowMapper.SUCCESS, CiticRowMapper.SUCCESS, null, evidence);
    }

    private BankDataCollection failed(String bankRequestNo, String statusCode,
                                      BankExchangeEvidence evidence,
                                      BankExchangeEvidence.AuxiliaryCall auxiliary) {
        BankExchangeEvidence effective = evidence;
        if (effective == null && auxiliary != null) {
            effective = new BankExchangeEvidence(null, null, null, null, null, null, auxiliary);
        }
        return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                statusCode, statusCode, null, effective);
    }

    private int startRecord(BankDataSyncContext context, int page) {
        String cursor = context.cursor();
        if (cursor != null && !cursor.isBlank()) {
            try {
                return Integer.parseInt(cursor.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the computed record
            }
        }
        return properties.getStartRecordBase() + (page - 1) * properties.getPageSize();
    }

    private boolean fullPage(CiticStatementPage statements, int pageSize) {
        Integer returned = statements.returnRecords();
        if (returned != null) {
            return returned >= pageSize;
        }
        return statements.rows() != null && statements.rows().size() >= pageSize;
    }

    private String resolveAccountNumber(BankDataSyncContext context) {
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, context.bankAccountId())
                .eq(BankAccount::getCompanyId, context.companyId()));
        if (account == null || account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
            throw new BusinessException(400, "CITIC bank account number is not resolvable for account "
                    + context.bankAccountId());
        }
        return account.getAccountNumber().trim();
    }

    private String requireUserName() {
        String userName = properties.getSdk().getUserName();
        if (userName == null || userName.isBlank()) {
            throw new BusinessException(400, "CITIC sdk user-name is required when the real adapter is enabled");
        }
        return userName.trim();
    }
}
