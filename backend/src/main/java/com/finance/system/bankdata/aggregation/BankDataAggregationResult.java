package com.finance.system.bankdata.aggregation;

import com.finance.system.bankdata.adapter.BankDataCollection;

/** Result crossing the unified aggregation boundary; vendor details do not cross it. */
public record BankDataAggregationResult(
        String adapterCode,
        String mappingVersion,
        BankDataStatus status,
        BankDataCollection collection,
        String safeErrorSummary
) {
}
