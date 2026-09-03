package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;

import java.util.List;

/**
 * DLBALQRY request model. Vendor limit: up to ten accounts per request.
 *
 * @param accountNos account numbers (char(19)); 1..10 entries
 */
public record CiticBalanceQuery(List<String> accountNos) {

    public CiticBalanceQuery {
        if (accountNos == null || accountNos.isEmpty()) {
            throw new BusinessException(400, "CITIC DLBALQRY requires at least one account");
        }
        if (accountNos.size() > 10) {
            throw new BusinessException(400, "CITIC DLBALQRY supports at most 10 accounts per request");
        }
        accountNos = List.copyOf(accountNos);
    }
}
