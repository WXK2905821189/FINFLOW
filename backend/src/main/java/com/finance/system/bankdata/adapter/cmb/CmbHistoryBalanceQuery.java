package com.finance.system.bankdata.adapter.cmb;

import com.finance.system.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * NTQABINF 查询账户历史余额 request body model (vendor doc 7, 云直联文档中心
 * DCCT20201214145038074 节点 2026082616401783048 — docs/cmb-clouddc/markdown/7.
 * 查询账户历史余额NTQABINF.md).
 *
 * <p>Wire shape: {@code body.ntqabinfy} = single record ({@code accnbr} required,
 * {@code bbknbr}/{@code ccynbr} optional). Vendor constraints: 起止日期间隔 ≤31 天，
 * 且查询开始/结束日期必须早于当日。</p>
 *
 * @param accountNo account number string(35)
 * @param branchCode 分行号 string(2), A.1 招商分行 (optional on the wire)
 * @param startDate inclusive window start YYYYMMDD
 * @param endDate inclusive window end YYYYMMDD (gap to startDate &lt;= 31 days, both &lt; today)
 * @param currency 币种 string(2) (optional)
 */
public record CmbHistoryBalanceQuery(
        String accountNo,
        String branchCode,
        LocalDate startDate,
        LocalDate endDate,
        String currency
) {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public CmbHistoryBalanceQuery {
        if (accountNo == null || accountNo.isBlank()) {
            throw new BusinessException(400, "CMB NTQABINF requires an account number");
        }
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException(400, "CMB NTQABINF requires startDate <= endDate");
        }
        if (startDate.plusDays(31).isBefore(endDate)) {
            throw new BusinessException(400, "CMB NTQABINF window must not exceed 31 days");
        }
        LocalDate today = LocalDate.now();
        if (!endDate.isBefore(today)) {
            throw new BusinessException(400, "CMB NTQABINF requires dates earlier than today");
        }
        accountNo = accountNo.trim();
        branchCode = branchCode == null ? null : branchCode.trim();
        currency = currency == null ? null : currency.trim();
    }

    public String startDateText() {
        return startDate.format(YYYYMMDD);
    }

    public String endDateText() {
        return endDate.format(YYYYMMDD);
    }
}
