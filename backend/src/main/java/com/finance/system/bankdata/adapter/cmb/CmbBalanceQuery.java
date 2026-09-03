package com.finance.system.bankdata.adapter.cmb;

import java.util.List;

/**
 * NTQADINF 批量查询余额 request body model.
 *
 * <p>Wire shape: {@code body.ntqadinfx} = JSON array of per-account records
 * ({@code accnbr}/{@code bbknbr}/{@code ccynbr}). The bank accepts up to 30 accounts per
 * request; a failing account is reported per-record via {@code errcod} without failing the
 * other accounts.</p>
 */
public record CmbBalanceQuery(List<CmbBalanceAccount> accounts) {

    public CmbBalanceQuery {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    /** Per-account query record; branchCode/currency are optional on the wire. */
    public record CmbBalanceAccount(String accountNo, String branchCode, String currency) {

        public CmbBalanceAccount {
            accountNo = accountNo == null ? null : accountNo.trim();
            branchCode = branchCode == null ? null : branchCode.trim();
            currency = currency == null ? null : currency.trim();
        }
    }
}
