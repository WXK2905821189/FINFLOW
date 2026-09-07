package com.finance.system.bankdata.adapter.citic;

import com.finance.system.bankdata.adapter.BankDataBalanceEntry;
import com.finance.system.bankdata.adapter.BankDataCollection;
import com.finance.system.bankdata.adapter.BankDataEntry;
import com.finance.system.bankdata.adapter.VendorStatementFields;

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
 * The one and only CITIC wire-row → FINFLOW-entry mapping, shared by the live adapter
 * and the raw-message replay path so a mapping change is always reflected by a replay.
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
public final class CiticRowMapper {

    public static final String SUCCESS = "AAAAAAA";
    public static final String NO_TRANSACTION = "EEEEEEE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private CiticRowMapper() {
    }

    /**
     * Re-parses a stored (decoded) DLTRNALL business XML response with the current
     * mapping for the replay endpoint. Balance snapshots live in a separate exchange
     * and are replayed through {@link #replayBalance}.
     */
    public static BankDataCollection replayStatementPage(String responseXml, Long bankAccountId,
                                                         String bankRequestNo) {
        CiticStatementPage page = CiticResponseXml.parseStatementPage(responseXml);
        if (page.status() == null) {
            return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                    "UNKNOWN", "UNKNOWN");
        }
        if (!SUCCESS.equals(page.status()) && !NO_TRANSACTION.equals(page.status())) {
            return new BankDataCollection(bankRequestNo, List.of(), List.of(), false, null,
                    page.status(), page.status());
        }
        List<BankDataEntry> entries = toEntries(page, bankAccountId, bankRequestNo);
        boolean noTransaction = NO_TRANSACTION.equals(page.status());
        boolean hasMore = !noTransaction && fullPage(page);
        String nextCursor = hasMore ? "REPLAY" : null;
        return new BankDataCollection(bankRequestNo, entries, List.of(), hasMore, nextCursor,
                SUCCESS, SUCCESS);
    }

    /** Re-parses a stored DLBALQRY response for the replay endpoint. */
    public static List<BankDataBalanceEntry> replayBalance(String responseXml, Long bankAccountId,
                                                           String bankRequestNo) {
        CiticBalanceResult result = CiticResponseXml.parseBalanceQuery(responseXml);
        return acceptedBalanceRows(result.rows(), bankAccountId, bankRequestNo);
    }

    public static List<BankDataEntry> toEntries(CiticStatementPage page, Long bankAccountId, String bankRequestNo) {
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

    public static List<BankDataBalanceEntry> acceptedBalanceRows(List<CiticBalanceRow> rows, Long bankAccountId,
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
            // forzenAmt=冻结. CITIC reports no 上日余额 and no 科目/客户关系号/账户状态/
            // 开户日/利率类型/存期/透支额度/利息码/年利率/到期日 (NTQADINF-specific fields) - left null.
            balances.add(new BankDataBalanceEntry(bankRequestNo, bankAccountId, row.usableBalance(), null, asOf,
                    row.balance(), row.forzenAmt(), null, trim(row.currencyId()), null,
                    trim(row.accountNo()), trim(row.accountName()), null, null,
                    null, null, null, null,
                    null, null, null, null));
        }
        return List.copyOf(balances);
    }

    /**
     * CITIC sends an unsigned amount plus a C/D flag; the shared column carries the signed
     * figure, so the sign is re-applied here (D 借方 negative, C 贷方 positive).
     */
    private static BigDecimal signedAmount(BigDecimal amount, String creditDebitFlag) {
        if (amount == null) {
            return null;
        }
        String flag = creditDebitFlag == null ? null : creditDebitFlag.trim().toUpperCase(Locale.ROOT);
        return "D".equals(flag) ? amount.negate() : amount;
    }

    private static boolean fullPage(CiticStatementPage statements) {
        // The vendor's page size is not re-readable from the parsed page alone; the live
        // adapter compares against its configured page size. Replay uses the row count with
        // the vendor's documented 20-row page limit, which is the same signal.
        return statements.rows() != null && statements.rows().size() >= 20;
    }

    private static LocalDateTime transactionTime(String tranDate, String tranTime) {
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

    private static String direction(String creditDebitFlag) {
        if (creditDebitFlag == null) {
            return null;
        }
        return switch (creditDebitFlag.trim().toUpperCase(Locale.ROOT)) {
            case "C" -> "INCOME";
            case "D" -> "EXPENSE";
            default -> null;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
