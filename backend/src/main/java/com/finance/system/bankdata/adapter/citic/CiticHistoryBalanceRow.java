package com.finance.system.bankdata.adapter.citic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * DLHBLQRY response row: one historical day-balance pair ({@code date} YYYYMMDD +
 * {@code balance}), verbatim per vendor dev-guide §5.4.
 */
public record CiticHistoryBalanceRow(LocalDate date, BigDecimal balance) {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static CiticHistoryBalanceRow of(String dateText, String balanceText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateText.trim(), YYYYMMDD);
        } catch (DateTimeParseException exception) {
            return null;
        }
        if (balanceText == null || balanceText.isBlank()) {
            return null;
        }
        try {
            return new CiticHistoryBalanceRow(date, new BigDecimal(balanceText.trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
