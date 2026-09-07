package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.VendorStatementFields;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.BalanceRow;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.Envelope;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementPage;
import com.finance.system.bankdata.adapter.cmb.CmbResponseParser.StatementRow;
import com.finance.system.bankdata.adapter.cmb.CmbStatementQuery.CmbStatementBreakPoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * The one and only CMB wire-row → FINFLOW-entry mapping. Both the live adapter and the
 * raw-message replay path call these static methods, so a mapping change is always
 * reflected by a replay — a duplicated copy here would silently defeat the replay's
 * purpose of exposing field drift.
 *
 * <p>Bank fields are attached verbatim through {@link VendorStatementFields}; nothing is
 * renamed, rounded or re-signed. The signed {@code transAmount} is preserved so a reviewer
 * can reconcile against the bank's own statement export (which shows 借方/贷方 as two
 * unsigned columns).</p>
 */
public final class CmbRowMapper {

    static final String SUCCESS = CmbResponseParser.SUCCESS_CODE;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private CmbRowMapper() {
    }

    /**
     * Re-parses a stored (decrypted) statement-page response with the current mapping.
     * Used by the replay endpoint: the result can be diffed against the view captured
     * when the page was first collected, which exposes any mapping change since then.
     */
    public static BankDataCollection replayStatementPage(String responseText, Long bankAccountId,
                                                         String bankRequestNo) {
        Envelope envelope = CmbResponseParser.parseEnvelope(responseText);
        if (!envelope.succeeded()) {
            return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                    envelope.resultcode(), envelope.resultcode());
        }
        StatementPage page = CmbResponseParser.parseStatementPage(envelope);
        List<BankDataEntry> entries = toEntries(page, bankAccountId, bankRequestNo);
        boolean hasMore = "Y".equalsIgnoreCase(trim(page.ctnFlag()));
        String nextCursor = hasMore
                ? RealCmbBankDataAdapter.StatementCursor.encode(page.queryAcctNbr(), page.breakPoints())
                : null;
        return new BankDataCollection(bankRequestNo, entries, List.of(), hasMore, nextCursor,
                SUCCESS, SUCCESS, page.pageTotals());
    }

    public static List<BankDataEntry> toEntries(StatementPage page, Long bankAccountId, String bankRequestNo) {
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

    public static List<BankDataBalanceEntry> toBalances(Envelope envelope, Long bankAccountId,
                                                        String accountNo, String bankRequestNo) {
        return toBalanceRows(CmbResponseParser.parseBalanceRows(envelope), accountNo, bankAccountId, bankRequestNo);
    }

    /**
     * Re-parses a stored NTQADINF balance snapshot response for the replay endpoint. The
     * original collect filtered rows to the synced account; the snapshot itself is scoped
     * to a single account, so accepting every healthy row reproduces the same result.
     */
    public static List<BankDataBalanceEntry> replayBalance(String responseText, Long bankAccountId,
                                                           String bankRequestNo) {
        Envelope envelope = CmbResponseParser.parseEnvelope(responseText);
        if (!envelope.succeeded()) {
            return List.of();
        }
        return toBalanceRows(CmbResponseParser.parseBalanceRows(envelope), null, bankAccountId, bankRequestNo);
    }

    private static List<BankDataBalanceEntry> toBalanceRows(List<BalanceRow> rows, String accountNo,
                                                            Long bankAccountId, String bankRequestNo) {
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDateTime asOf = LocalDateTime.now();
        List<BankDataBalanceEntry> balances = new ArrayList<>(1);
        for (BalanceRow row : rows) {
            if (accountNo != null && !accountNo.equals(trim(row.accnbr()))) {
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
                    trim(row.inttyp()), trim(row.dpstxt()),
                    decimal(trim(row.lmtovr())), trim(row.intcod()),
                    decimal(trim(row.intrat())), trim(row.mutdat())));
        }
        return List.copyOf(balances);
    }

    private static LocalDateTime transactionTime(String date, String time) {
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

    private static LocalDate valueDate(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date, DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String direction(String loanCode) {
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
    private static BigDecimal magnitude(String amount) {
        BigDecimal value = decimal(amount);
        return value == null ? null : value.abs();
    }

    private static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
