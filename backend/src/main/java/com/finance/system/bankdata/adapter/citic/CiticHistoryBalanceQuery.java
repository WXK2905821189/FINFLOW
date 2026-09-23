package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * DLHBLQRY request model (vendor dev-guide §5.4): signed-account historical balance,
 * start-to-end gap at most 30 days. Response rows are one {@code date}(YYYYMMDD) +
 * {@code balance} pair per day.
 *
 * @param accountNo account number char(19)
 * @param startDate inclusive window start YYYYMMDD
 * @param endDate   inclusive window end YYYYMMDD (gap to startDate &lt;= 30 days)
 */
public record CiticHistoryBalanceQuery(
        String accountNo,
        LocalDate startDate,
        LocalDate endDate
) {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public CiticHistoryBalanceQuery {
        if (accountNo == null || accountNo.isBlank()) {
            throw new BusinessException(400, "CITIC DLHBLQRY requires an account number");
        }
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException(400, "CITIC DLHBLQRY requires startDate <= endDate");
        }
        if (startDate.plusDays(30).isBefore(endDate)) {
            throw new BusinessException(400, "CITIC DLHBLQRY window must not exceed 30 days");
        }
    }

    public String startDateText() {
        return startDate.format(YYYYMMDD);
    }

    public String endDateText() {
        return endDate.format(YYYYMMDD);
    }
}
