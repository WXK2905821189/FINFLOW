package com.finance.system.bankdata;

import java.math.BigDecimal;
import java.util.List;

/**
 * Renders bank data as CSV in the layout the bank itself uses for its own statement export,
 * so an exported file can be laid next to the bank's file and compared column by column.
 *
 * <p>Three deliberate details:</p>
 * <ul>
 *   <li><strong>UTF-8 BOM.</strong> Excel detects UTF-8 by the BOM; without it, Chinese
 *       headers and memo text open as mojibake. Every other CSV reader tolerates it.</li>
 *   <li><strong>CRLF + RFC 4180 quoting.</strong> Excel is the consumer that actually matters
 *       here, and it expects CRLF.</li>
 *   <li><strong>Formula-injection guard on text cells.</strong> A counterparty can choose the
 *       memo on a transfer, so 摘要 / 收付方名称 is attacker-influenced text. Excel evaluates
 *       a leading {@code = + - @} as a formula, which turns an exported file into an execution
 *       vector. Text cells starting with those characters get a leading apostrophe, which
 *       Excel treats as "display as text" and does not show. Numeric cells bypass the guard
 *       so negative amounts stay clean numbers.</li>
 * </ul>
 */
public final class BankDataCsvWriter {

    private static final String BOM = "\uFEFF";
    private static final String CRLF = "\r\n";
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private BankDataCsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        appendRow(out, headers);
        for (List<String> row : rows) {
            appendRow(out, row);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(cells.get(i)));
        }
        out.append(CRLF);
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        String safe = guard(value);
        boolean needsQuotes = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    /** Neutralises spreadsheet formula injection: a leading apostrophe makes Excel show text. */
    static String guard(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    /** Numeric cells skip the guard so signed amounts survive as numbers. */
    public static String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
