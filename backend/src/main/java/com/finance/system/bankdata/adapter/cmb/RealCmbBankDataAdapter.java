package com.finance.system.bankdata.adapter.cmb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.BankDataSyncContext;
import com.finance.system.bankdata.adapter.BankExchangeEvidence;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.BalanceRow;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.Envelope;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementPage;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
            BalanceCall balance = queryBalance(accountNo, CmbCryptoHelper.newReqId());
            if (!balance.envelope().succeeded()) {
                return failed(requestNo, balance.envelope().resultcode(), balance.auxiliary());
            }
            String rowError = targetBalanceError(balance.envelope(), accountNo);
            if (rowError != null) {
                return failed(requestNo, rowError, balance.auxiliary());
            }
            List<BankDataBalanceEntry> balances =
                    CmbRowMapper.toBalances(balance.envelope(), context.bankAccountId(), accountNo, requestNo);
            return statementPage(context, accountNo, from, to, balances, requestNo, balance.auxiliary());
        }
        return statementPage(context, accountNo, from, to, List.of(), requestNo, null);
    }

    private BankDataCollection statementPage(BankDataSyncContext context, String accountNo,
                                             LocalDate from, LocalDate to,
                                             List<BankDataBalanceEntry> balances, String requestNo,
                                             BankExchangeEvidence.AuxiliaryCall auxiliary) {
        CmbStatementQuery query = buildStatementQuery(context, accountNo, from, to);
        CmbHttpGateway.CmbExchange exchange = gateway.exchangeDetailed(CmbRequestBuilder.FUNCODE_STATEMENT,
                CmbRequestBuilder.statementDocument(requireUid(), requestNo, query));
        BankExchangeEvidence evidence = new BankExchangeEvidence(properties.getUrl(),
                CmbRequestBuilder.FUNCODE_STATEMENT, exchange.plainRequest(), exchange.responseText(),
                exchange.durationMs(), exchange.httpStatus(), auxiliary);
        Envelope envelope = CmbResponseParser.parseEnvelope(exchange.responseText());
        if (!envelope.succeeded()) {
            return failed(requestNo, envelope.resultcode(), evidence);
        }
        StatementPage page = CmbResponseParser.parseStatementPage(envelope);
        List<BankDataEntry> entries = CmbRowMapper.toEntries(page, context.bankAccountId(), requestNo);
        boolean hasMore = "Y".equalsIgnoreCase(trim(page.ctnFlag()));
        String nextCursor = hasMore
                ? StatementCursor.encode(page.queryAcctNbr(), page.breakPoints()) : null;
        return new BankDataCollection(requestNo, entries, balances, hasMore, nextCursor,
                SUCCESS, SUCCESS, page.pageTotals(), evidence);
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

    /** One balance exchange plus its wire evidence (the page-1 NTQADINF snapshot). */
    private record BalanceCall(Envelope envelope, BankExchangeEvidence.AuxiliaryCall auxiliary) {
    }

    private BalanceCall queryBalance(String accountNo, String requestId) {
        CmbBalanceQuery query = new CmbBalanceQuery(List.of(
                new CmbBalanceQuery.CmbBalanceAccount(accountNo, properties.getBranchCode(), null)));
        CmbHttpGateway.CmbExchange exchange = gateway.exchangeDetailed(CmbRequestBuilder.FUNCODE_BALANCE,
                CmbRequestBuilder.balanceDocument(requireUid(), requestId, query));
        BankExchangeEvidence.AuxiliaryCall auxiliary = new BankExchangeEvidence.AuxiliaryCall(
                CmbRequestBuilder.FUNCODE_BALANCE, exchange.plainRequest(), exchange.responseText(),
                exchange.durationMs(), exchange.httpStatus());
        return new BalanceCall(CmbResponseParser.parseEnvelope(exchange.responseText()), auxiliary);
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

    private BankDataCollection failed(String bankRequestNo, String statusCode,
                                      BankExchangeEvidence.AuxiliaryCall auxiliary) {
        BankExchangeEvidence evidence = auxiliary == null ? null
                : new BankExchangeEvidence(null, null, null, null, null, null, auxiliary);
        return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                statusCode, statusCode, null, evidence);
    }

    private BankDataCollection failed(String bankRequestNo, String statusCode,
                                      BankExchangeEvidence evidence) {
        return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                statusCode, statusCode, null, evidence);
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
