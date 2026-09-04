package com.finance.system.bankdata.aggregation;

import java.util.Locale;

/** FINFLOW status vocabulary shared by every bank adapter. */
public enum BankDataStatus {
    SUCCESS,
    FAILED,
    PENDING,
    TIMEOUT,
    UNKNOWN,
    EMPTY,
    DUPLICATE,
    PARTIAL;

    public static BankDataStatus fromVendor(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            // AAAAAAA = CITIC success, SUC0000 = CMB success (AAAAAAE = CITIC processing, EEEEEEE = CITIC failure).
            case "SUCCESS", "OK", "AAAAAAA", "SUC0000" -> SUCCESS;
            case "PENDING", "PROCESSING", "ACCEPTED", "AAAAAAE" -> PENDING;
            case "TIMEOUT", "TIMED_OUT", "TIME_OUT" -> TIMEOUT;
            case "FAILED", "FAILURE", "ERROR", "EEEEEEE" -> FAILED;
            case "DUPLICATE", "REPLAY" -> DUPLICATE;
            case "PARTIAL", "PARTIAL_SUCCESS" -> PARTIAL;
            case "EMPTY", "NO_DATA", "NO_DATA_FOUND" -> EMPTY;
            default -> UNKNOWN;
        };
    }

    public boolean allowsProjection() {
        return this == SUCCESS || this == PARTIAL;
    }
}
