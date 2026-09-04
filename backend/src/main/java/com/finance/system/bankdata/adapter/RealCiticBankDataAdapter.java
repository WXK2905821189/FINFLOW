package com.finance.system.bankdata.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.CiticBalanceQuery;
import com.finance.system.bankdata.adapter.citic.CiticBalanceResult;
import com.finance.system.bankdata.adapter.citic.CiticBalanceRow;
import com.finance.system.bankdata.adapter.citic.CiticEnvelopeCodec;
import com.finance.system.bankdata.adapter.citic.CiticRequestXml;
import com.finance.system.bankdata.adapter.citic.CiticResponseXml;
import com.finance.system.bankdata.adapter.citic.CiticStatementPage;
import com.finance.system.bankdata.adapter.citic.CiticStatementQuery;
import com.finance.system.bankdata.adapter.citic.CiticStatementRow;
import com.finance.system.bankdata.adapter.citic.dlink.CiticDlinkSdk;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private static final String SUCCESS = "AAAAAAA";
    private static final String NO_TRANSACTION = "EEEEEEE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

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
            CiticBalanceResult balance = queryBalance(accountNo, userName, requestId, page);
            String balanceStatus = balance.status();
            if (balanceStatus != null && !balanceStatus.isBlank() && !SUCCESS.equals(balanceStatus)) {
                return failed(bankRequestNo, balanceStatus);
            }
            List<BankDataBalanceEntry> balances = acceptedBalanceRows(balance.rows(), context.bankAccountId(),
                    bankRequestNo);
            CiticStatementPage statements = queryStatement(accountNo, userName, requestId, page,
                    context.windowStart().toLocalDate(), context.windowEnd().toLocalDate(),
                    startRecord, pageSize);
            if (statements.status() == null) {
                return failed(bankRequestNo, "UNKNOWN");
            }
            if (!SUCCESS.equals(statements.status()) && !NO_TRANSACTION.equals(statements.status())) {
                return failed(bankRequestNo, statements.status());
            }
            List<BankDataEntry> entries = statementEntries(statements, context.bankAccountId(), bankRequestNo);
            boolean noTransaction = NO_TRANSACTION.equals(statements.status());
            boolean hasMore = !noTransaction && fullPage(statements, pageSize);
            return page(bankRequestNo, entries, balances, hasMore, startRecord, pageSize);
        }

        CiticStatementPage statements = queryStatement(accountNo, userName, requestId, page,
                context.windowStart().toLocalDate(), context.windowEnd().toLocalDate(),
                startRecord, pageSize);
        if (statements.status() == null) {
            return failed(bankRequestNo, "UNKNOWN");
        }
        if (!SUCCESS.equals(statements.status()) && !NO_TRANSACTION.equals(statements.status())) {
            return failed(bankRequestNo, statements.status());
        }
        List<BankDataEntry> entries = statementEntries(statements, context.bankAccountId(), bankRequestNo);
        boolean noTransaction = NO_TRANSACTION.equals(statements.status());
        boolean hasMore = !noTransaction && fullPage(statements, pageSize);
        return page(bankRequestNo, entries, List.of(), hasMore, startRecord, pageSize);
    }

    private CiticBalanceResult queryBalance(String accountNo, String userName, String requestId, int page) {
        String businessXml = CiticRequestXml.buildBalanceQuery(userName,
                new CiticBalanceQuery(List.of(accountNo)));
        String responseXml = sdk.exchange("DLBALQRY", businessXml, CiticEnvelopeCodec.clientId(requestId, page));
        return CiticResponseXml.parseBalanceQuery(responseXml);
    }

    private CiticStatementPage queryStatement(String accountNo, String userName, String requestId, int page,
                                              LocalDate windowStart, LocalDate windowEnd, int startRecord,
                                              int pageSize) {
        CiticStatementQuery query = new CiticStatementQuery(accountNo, windowStart, windowEnd, pageSize,
                startRecord, properties.getControlFlag());
        String businessXml = CiticRequestXml.buildStatementQuery(userName, query);
        String responseXml = sdk.exchange("DLTRNALL", businessXml, CiticEnvelopeCodec.clientId(requestId, page));
        return CiticResponseXml.parseStatementPage(responseXml);
    }

    /**
     * DLTRNALL rows → FINFLOW entries, with the bank's own fields attached.
     *
     * <p>The vendor columns are shared with CMB, so each CITIC field is mapped onto the
     * semantically equivalent one and the mapping is spelled out here rather than left
     * implied. Two honest caveats:</p>
     * <ul>
     *   <li>CITIC reports {@code tranAmount} <strong>unsigned</strong> (88.00 with
     *       creditDebitFlag=C, 12.00 with D) where CMB reports it signed. {@code signedAmount}
     *       is therefore reconstructed from {@code creditDebitFlag} for CITIC, and is verbatim
     *       for CMB — the column means "the bank's signed figure", not "the wire value".</li>
     *   <li>Fields CITIC simply does not report (起息日, 票据号, 冲账标志, 信息标志, 母子公司…)
     *       stay null. Nothing is invented to fill a column.</li>
     * </ul>
     */
    private List<BankDataEntry> statementEntries(CiticStatementPage page, Long bankAccountId, String bankRequestNo) {
        if (page.rows() == null || page.rows().isEmpty()) {
            return List.of();
        }
        String containerAccount = trim(page.accountNo());
        List<BankDataEntry> entries = new ArrayList<>(page.rows().size());
        for (CiticStatementRow row : page.rows()) {
            LocalDateTime transactionTime = transactionTime(row.tranDate(), row.tranTime());
            String direction = direction(row.creditDebitFlag());
            String statementNo = firstNonBlank(row.tranNo(), row.sumTranNo(), row.oriNum());
            entries.add(new BankDataEntry(bankRequestNo, statementNo, bankAccountId, transactionTime, direction,
                    row.tranAmount(), null, trim(row.oppAccountName()), trim(row.oppAccountNo()),
                    trim(row.summary()),
                    new VendorStatementFields(containerAccount, null, trim(row.creditDebitFlag()),
                            null, null, null, null, row.balance(),
                            signedAmount(row.tranAmount(), row.creditDebitFlag()), null,
                            trim(row.oppAccountNo()), trim(row.oppOpenBankName()), null,
                            null, null, null, null, null, null, null,
                            trim(row.sumTranNo()), trim(row.oriNum()), null, null, null, null,
                            null)));
        }
        return List.copyOf(entries);
    }

    /**
     * CITIC sends an unsigned amount plus a C/D flag; the shared column carries the signed
     * figure, so the sign is re-applied here (D 借方 negative, C 贷方 positive).
     */
    private BigDecimal signedAmount(BigDecimal amount, String creditDebitFlag) {
        if (amount == null) {
            return null;
        }
        String flag = creditDebitFlag == null ? null : creditDebitFlag.trim().toUpperCase(Locale.ROOT);
        return "D".equals(flag) ? amount.negate() : amount;
    }

    private List<BankDataBalanceEntry> acceptedBalanceRows(List<CiticBalanceRow> rows, Long bankAccountId,
                                                           String bankRequestNo) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        LocalDateTime asOf = LocalDateTime.now();
        List<BankDataBalanceEntry> balances = new ArrayList<>(rows.size());
        for (CiticBalanceRow row : rows) {
            // Account-level status: only accept explicitly healthy or absent status rows.
            String rowStatus = trim(row.status());
            if (rowStatus != null && !SUCCESS.equals(rowStatus)) {
                continue;
            }
            // DLBALQRY and NTQADINF report the same three figures under different names, so
            // they land in the same columns: usableBalance=可用, balance=账面(联机),
            // forzenAmt=冻结. CITIC reports no 上日余额 and no 科目/客户关系号 - left null.
            balances.add(new BankDataBalanceEntry(bankRequestNo, bankAccountId, row.usableBalance(), null, asOf,
                    row.balance(), row.forzenAmt(), null, trim(row.currencyId()), null,
                    trim(row.accountNo()), trim(row.accountName()), null, null));
        }
        return List.copyOf(balances);
    }

    private BankDataCollection page(String bankRequestNo, List<BankDataEntry> entries,
                                    List<BankDataBalanceEntry> balances, boolean hasMore,
                                    int startRecord, int pageSize) {
        String nextCursor = hasMore ? String.valueOf(startRecord + pageSize) : null;
        return new BankDataCollection(bankRequestNo, entries, balances, hasMore, nextCursor,
                SUCCESS, SUCCESS);
    }

    private BankDataCollection failed(String bankRequestNo, String statusCode) {
        return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                statusCode, statusCode);
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

    private LocalDateTime transactionTime(String tranDate, String tranTime) {
        if (tranDate == null || tranDate.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(tranDate.trim(), DAY);
            if (tranTime == null || tranTime.isBlank()) {
                return date.atStartOfDay();
            }
            return date.atTime(LocalTime.parse(tranTime.trim(), TIME));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String direction(String creditDebitFlag) {
        if (creditDebitFlag == null) {
            return null;
        }
        return switch (creditDebitFlag.trim().toUpperCase(Locale.ROOT)) {
            case "C" -> "INCOME";
            case "D" -> "EXPENSE";
            default -> null;
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
