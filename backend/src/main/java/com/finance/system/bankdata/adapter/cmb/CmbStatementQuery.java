package com.finance.system.bankdata.adapter.cmb;

import java.util.List;

/**
 * trsQryByBreakPoint 账户交易信息查询 request body model (断点续传).
 *
 * <p>Wire shape (all values strings, per the 报文规范):</p>
 * <pre>
 * body.TRANSQUERYBYBREAKPOINT_X1 = [ X1 fields... ]   // query condition, single record
 * body.TRANSQUERYBYBREAKPOINT_Y1 = [ break points... ] // continuation cursor, echo of last response Y1
 * </pre>
 * A fresh query omits breakPoints and leaves queryAcctNbr empty; the continuation query fills
 * queryAcctNbr from the previous response Z1.queryAcctNbr and echoes the previous Y1 array.
 */
public record CmbStatementQuery(
        String cardNbr,
        String beginDate,
        String endDate,
        String transactionSequence,
        String currencyCode,
        String queryAcctNbr,
        String reserve,
        String loanCode,
        List<CmbStatementBreakPoint> breakPoints) {

    public CmbStatementQuery {
        breakPoints = breakPoints == null ? List.of() : List.copyOf(breakPoints);
    }

    /** First page of a new window (no cursor). */
    public static CmbStatementQuery firstPage(String cardNbr, String beginDate, String endDate) {
        return new CmbStatementQuery(cardNbr, beginDate, endDate, "1", null, null, null, null, List.of());
    }

    public boolean isContinuation() {
        return !breakPoints.isEmpty() || (queryAcctNbr != null && !queryAcctNbr.isBlank());
    }

    /** One Y1 break-point record echoed between requests. */
    public record CmbStatementBreakPoint(String acctNbr, String transDate, String expectNextSequence) {
    }
}
