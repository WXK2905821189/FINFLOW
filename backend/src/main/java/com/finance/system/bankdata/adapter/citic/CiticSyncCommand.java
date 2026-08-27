package com.finance.system.bankdata.adapter.citic;

import java.time.LocalDateTime;

public record CiticSyncCommand(
        Long companyId,
        Long bankAccountId,
        String requestId,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        CiticCertificateReference certificateReference
) {
}
