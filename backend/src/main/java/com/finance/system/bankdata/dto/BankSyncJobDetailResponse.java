package com.finance.system.bankdata.dto;

import java.util.List;

public record BankSyncJobDetailResponse(
        BankSyncJobResponse job,
        List<BankSyncJobEventResponse> timeline
) {
}
