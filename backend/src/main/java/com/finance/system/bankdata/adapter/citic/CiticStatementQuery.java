package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * DLTRNALL request model. Vendor constraints: window gap at most 92 days,
 * trace depth 3 years, pageNumber (records per request) at most 20.
 *
 * @param accountNo   account number char(19)
 * @param startDate   inclusive window start YYYYMMDD
 * @param endDate     inclusive window end YYYYMMDD (gap to startDate &lt;= 92 days)
 * @param pageNumber  records requested this call, 1..20 (not the page index)
 * @param startRecord 0-based or 1-based start record; base is verified during joint testing
 * @param controlFlag 2 returns oriNum for idempotency
 */
public record CiticStatementQuery(
        String accountNo,
        LocalDate startDate,
        LocalDate endDate,
        int pageNumber,
        int startRecord,
        int controlFlag
) {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    public CiticStatementQuery {
        if (accountNo == null || accountNo.isBlank()) {
            throw new BusinessException(400, "CITIC DLTRNALL requires an account number");
        }
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException(400, "CITIC DLTRNALL requires startDate <= endDate");
        }
        if (startDate.plusDays(92).isBefore(endDate)) {
            throw new BusinessException(400, "CITIC DLTRNALL window must not exceed 92 days");
        }
        if (pageNumber < 1 || pageNumber > 20) {
            throw new BusinessException(400, "CITIC DLTRNALL pageNumber must be between 1 and 20");
        }
    }

    public String startDateText() {
        return startDate.format(YYYYMMDD);
    }

    public String endDateText() {
        return endDate.format(YYYYMMDD);
    }
}
