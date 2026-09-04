package com.finance.system.bankdata.adapter.cmb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.VendorStatementFields;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.BalanceRow;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.Envelope;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementPage;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementRow;
import com.finance.system.bankdata.adapter.cmb.CmbStatementQuery.CmbStatementBreakPoint;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Authorized CMB CloudDC (免前置) bank data adapter: NTQADINF balance snapshot +
 * trsQryByBreakPoint statement query with 断点续传.
 *
 * <p>One {@link #collect(BankDataSyncContext)} maps to one trsQryByBreakPoint page; the first
 * page of a window also takes an NTQADINF snapshot (≤30 accounts — here a single account scope).
 * The statement cursor is a JSON of {@code queryAcctNbr} + the echoed Y1 break-point array,
 * so continuation pages resume exactly where the bank left off.</p>
 *
 * <p>Activated only when {@code bankdata.adapter.cmb.real-enabled=true} (registry key CMB vs
 * the CMB_MOCK simulated adapter) and guarded by the aggregation call executor.</p>
 */
@Component
@ConditionalOnProperty(prefix = "bankdata.adapter.cmb", name = "real-enabled", havingValue = "true")
public class RealCmbBankDataAdapter implements BankDataAdapter {

    static final String ADAPTER_CODE = "CMB";
    private static final String SUCCESS = CmbResponseParser.SUCCESS_CODE;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final CmbAdapterProperties properties;
    private final CmbHttpGateway gateway;
    private final BankAccountMapper bankAccountMapper;

    public RealCmbBankDataAdapter(CmbAdapterProperties properties, BankAccountMapper bankAccountMapper) {
        this.properties = properties;
        this.gateway = new CmbHttpGateway(properties);
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
            throw new BusinessException(400, "CMB collect requires a bank account scope");
        }
        if (context.windowStart() == null || context.windowEnd() == null) {
            throw new BusinessException(400, "CMB collect requires a sync window");
        }
        requireConfigured();
        int page = context.pageNumber() == null || context.pageNumber() < 1 ? 1 : context.pageNumber();
        String accountNo = resolveAccountNumber(context);
        LocalDate from = context.windowStart().toLocalDate();
        LocalDate to = context.windowEnd().toLocalDate();
        String requestNo = CmbCryptoHelper.newReqId();

        if (page == 1) {
            Envelope balance = queryBalance(accountNo, CmbCryptoHelper.newReqId());
            if (!balance.succeeded()) {
                return failed(requestNo, balance.resultcode());
            }
            String rowError = targetBalanceError(balance, accountNo);
            if (rowError != null) {
                return failed(requestNo, rowError);
            }
            List<BankDataBalanceEntry> balances =
                    toBalances(balance, context.bankAccountId(), accountNo, requestNo);
            return statementPage(context, accountNo, from, to, balances, requestNo);
        }
        return statementPage(context, accountNo, from, to, List.of(), requestNo);
    }

    private BankDataCollection statementPage(BankDataSyncContext context, String accountNo,
                                             LocalDate from, LocalDate to,
                                             List<BankDataBalanceEntry> balances, String requestNo) {
        CmbStatementQuery query = buildStatementQuery(context, accountNo, from, to);
        Envelope envelope = CmbResponseParser.parseEnvelope(
                gateway.exchange(CmbRequestBuilder.FUNCODE_STATEMENT,
                        CmbRequestBuilder.statementDocument(requireUid(), requestNo, query)));
        if (!envelope.succeeded()) {
            return failed(requestNo, envelope.resultcode());
        }
        StatementPage page = CmbResponseParser.parseStatementPage(envelope);
        List<BankDataEntry> entries = toEntries(page, context.bankAccountId(), requestNo);
        boolean hasMore = "Y".equalsIgnoreCase(trim(page.ctnFlag()));
        String nextCursor = hasMore
                ? StatementCursor.encode(page.queryAcctNbr(), page.breakPoints()) : null;
        return new BankDataCollection(requestNo, entries, balances, hasMore, nextCursor,
                SUCCESS, SUCCESS, page.pageTotals());
    }

    private CmbStatementQuery buildStatementQuery(BankDataSyncContext context, String accountNo,
                                                  LocalDate from, LocalDate to) {
        String cursor = context.cursor();
        if (cursor != null && !cursor.isBlank()) {
            CmbStatementCursorValue decoded = StatementCursor.decode(cursor);
            return new CmbStatementQuery(accountNo, from.format(DAY), to.format(DAY), "1", null,
                    decoded.queryAcctNbr(), null, null, decoded.breakPoints());
        }
        return CmbStatementQuery.firstPage(accountNo, from.format(DAY), to.format(DAY));
    }

    private Envelope queryBalance(String accountNo, String requestId) {
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount(accountNo, properties.getBranchCode(), null)));
        String response = gateway.exchange(CmbRequestBuilder.FUNCODE_BALANCE,
                CmbRequestBuilder.balanceDocument(requireUid(), requestId, query));
        return CmbResponseParser.parseEnvelope(response);
    }

    private String targetBalanceError(Envelope envelope, String accountNo) {
        for (BalanceRow row : CmbResponseParser.parseBalanceRows(envelope)) {
            if (!accountNo.equals(trim(row.accnbr()))) {
                continue;
            }
            String error = trim(row.errcod());
            return error == null || SUCCESS.equals(error) ? null : error;
        }
        return null;
    }

    private List<BankDataBalanceEntry> toBalances(Envelope envelope, Long bankAccountId,
                                                  String accountNo, String bankRequestNo) {
        List<BalanceRow> rows = CmbResponseParser.parseBalanceRows(envelope);
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDateTime asOf = LocalDateTime.now();
        List<BankDataBalanceEntry> balances = new ArrayList<>(1);
        for (BalanceRow row : rows) {
            if (!accountNo.equals(trim(row.accnbr()))) {
                continue;
            }
            String error = trim(row.errcod());
            if (error != null && !SUCCESS.equals(error)) {
                continue;
            }
            balances.add(new BankDataBalanceEntry(bankRequestNo, bankAccountId,
                    decimal(trim(row.avlblv())), null, asOf,
                    decimal(trim(row.onlblv())), decimal(trim(row.hldblv())),
                    decimal(trim(row.accblv())), trim(row.ccynbr()), trim(row.bbknbr()),
                    trim(row.accnbr()), trim(row.accnam()), trim(row.accitm()),
                    trim(row.relnbr()), trim(row.stscod()), trim(row.opndat()),
                    trim(row.inttyp()), trim(row.dpstxt())));
        }
        return List.copyOf(balances);
    }

    /**
     * Z2 rows → FINFLOW entries. The bank's own fields are attached verbatim through
     * {@link VendorStatementFields}; nothing is renamed, rounded or re-signed here, and the
     * signed {@code transAmount} is preserved so a reviewer can reconcile against the bank's
     * own statement export (which shows 借方/贷方 as two unsigned columns).
     */
    private List<BankDataEntry> toEntries(StatementPage page, Long bankAccountId, String bankRequestNo) {
        List<StatementRow> rows = page.rows();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        String pageAccountNo = trim(page.queryAcctNbr());
        List<BankDataEntry> entries = new ArrayList<>(rows.size());
        for (StatementRow row : rows) {
            LocalDateTime transactionTime = transactionTime(trim(row.transDate()), trim(row.transTime()));
            String loanCode = trim(row.loanCode());
            String direction = direction(loanCode);
            BigDecimal amount = magnitude(trim(row.transAmount()));
            String statementNo = trim(row.transSequenceIdn());
            String summary = firstNonBlank(trim(row.businessText()), trim(row.remarkTextClt()));
            entries.add(new BankDataEntry(bankRequestNo, statementNo, bankAccountId, transactionTime,
                    direction, amount, null, trim(row.ctpAcctName()), trim(row.ctpAcctNbr()), summary,
                    new VendorStatementFields(
                            pageAccountNo, valueDate(trim(row.valueDate())), loanCode,
                            trim(row.textCode()), trim(row.billNumber()), trim(row.remarkTextClt()),
                            trim(row.reversalFlag()), decimal(trim(row.acctOnlineBal())),
                            decimal(trim(row.transAmount())), trim(row.extendedRemark()),
                            trim(row.ctpAcctNbr()), trim(row.ctpBankName()), trim(row.ctpBankAddress()),
                            trim(row.fatOrSonAccount()), trim(row.fatOrSonCompanyName()),
                            trim(row.fatOrSonBankName()), trim(row.fatOrSonBankAddress()),
                            trim(row.infoFlag()), trim(row.businessName()), trim(row.businessText()),
                            trim(row.requestNbr()), trim(row.yurRef()), trim(row.virtualNbr()),
                            trim(row.mchOrderNbr()), trim(row.transCardNbr()), trim(row.reserve()),
                            trim(row.currencyNbr()))));
        }
        return List.copyOf(entries);
    }

    private BankDataCollection failed(String bankRequestNo, String statusCode) {
        return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                statusCode, statusCode);
    }

    private String resolveAccountNumber(BankDataSyncContext context) {
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, context.bankAccountId())
                .eq(BankAccount::getCompanyId, context.companyId()));
        if (account == null || account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
            throw new BusinessException(400, "CMB bank account number is not resolvable for account "
                    + context.bankAccountId());
        }
        return account.getAccountNumber().trim();
    }

    private String requireUid() {
        String uid = properties.getUid();
        if (uid == null || uid.isBlank()) {
            throw new BusinessException(400, "CMB uid is required when the real adapter is enabled");
        }
        return uid.trim();
    }

    private void requireConfigured() {
        try {
            gateway.requireConfigured();
        } catch (CmbCallException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private LocalDateTime transactionTime(String date, String time) {
        if (date == null) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(date, DAY);
            if (time == null) {
                return parsed.atStartOfDay();
            }
            return parsed.atTime(LocalTime.parse(time, TIME));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalDate valueDate(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date, DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String direction(String loanCode) {
        if (loanCode == null) {
            return null;
        }
        return switch (loanCode.toUpperCase(Locale.ROOT)) {
            case "C" -> "INCOME";
            case "D" -> "EXPENSE";
            default -> null;
        };
    }

    /** Bank signs debit amounts negative; FINFLOW entries carry direction separately → magnitude. */
    private BigDecimal magnitude(String amount) {
        BigDecimal value = decimal(amount);
        return value == null ? null : value.abs();
    }

    private BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Next-page cursor for FINFLOW sync paging: JSON of the bank's continuation account
     * ({@code queryAcctNbr}) plus the echoed Y1 break-point array.
     */
    static final class StatementCursor {

        private StatementCursor() {
        }

        static String encode(String queryAcctNbr, List<CmbStatementBreakPoint> breakPoints) {
            JsonObject cursor = new JsonObject();
            if (queryAcctNbr != null && !queryAcctNbr.isBlank()) {
                cursor.addProperty("queryAcctNbr", queryAcctNbr);
            }
            if (breakPoints != null && !breakPoints.isEmpty()) {
                JsonArray array = new JsonArray();
                for (CmbStatementBreakPoint point : breakPoints) {
                    JsonObject item = new JsonObject();
                    if (point.acctNbr() != null) {
                        item.addProperty("acctNbr", point.acctNbr());
                    }
                    if (point.transDate() != null) {
                        item.addProperty("transDate", point.transDate());
                    }
                    if (point.expectNextSequence() != null) {
                        item.addProperty("expectNextSequence", point.expectNextSequence());
                    }
                    array.add(item);
                }
                cursor.add("breakPoints", array);
            }
            return cursor.toString();
        }

        static CmbStatementCursorValue decode(String cursor) {
            JsonObject object;
            try {
                object = JsonParser.parseString(cursor).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                        "CMB statement cursor is not valid JSON", e);
            }
            String queryAcctNbr = null;
            JsonElement account = object.get("queryAcctNbr");
            if (account != null && account.isJsonPrimitive()) {
                queryAcctNbr = account.getAsString();
            }
            List<CmbStatementBreakPoint> breakPoints = new ArrayList<>();
            JsonElement points = object.get("breakPoints");
            if (points != null && points.isJsonArray()) {
                for (JsonElement element : points.getAsJsonArray()) {
                    JsonObject row = element.getAsJsonObject();
                    breakPoints.add(new CmbStatementBreakPoint(
                            text(row.get("acctNbr")), text(row.get("transDate")),
                            text(row.get("expectNextSequence"))));
                }
            }
            return new CmbStatementCursorValue(queryAcctNbr, List.copyOf(breakPoints));
        }

        private static String text(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            String value = element.getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** Decoded cursor value passed to the statement query builder. */
    record CmbStatementCursorValue(String queryAcctNbr, List<CmbStatementBreakPoint> breakPoints) {
    }
}
