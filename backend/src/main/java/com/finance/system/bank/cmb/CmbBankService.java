package com.finance.system.bank.cmb;

import com.finance.system.bank.BankService;
import com.finance.system.bank.BankTransferCommand;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * CMB (招商银行 CloudDC) registration for {@code bank_account} archival.
 *
 * <p>CMB connectivity in FINFLOW goes through the bank data pipeline
 * ({@code RealCmbBankDataAdapter}, adapterCode CMB), not through the legacy
 * payment channel. This service exists so that {@code POST /api/bank-accounts}
 * accepts {@code bankCode=CMB} and data sync can resolve the account number;
 * balance queries and transfers are deliberately unsupported here (they have no
 * active product surface, matching the v0.4 scope) and fail with an explicit
 * message instead of silently returning stale data.</p>
 */
@Service
public class CmbBankService implements BankService {

    @Override
    public String bankCode() {
        return "CMB";
    }

    @Override
    public BigDecimal queryAvailableBalance(BankAccount account) {
        throw new BusinessException(400,
                "CMB balance queries are not supported through the payment channel; use the bank data pipeline");
    }

    @Override
    public BankTransferResponse submitTransfer(BankAccount payerAccount, BankTransferCommand command) {
        throw new BusinessException(400,
                "CMB transfers are outside the current product scope; use the bank data pipeline for statements");
    }
}
