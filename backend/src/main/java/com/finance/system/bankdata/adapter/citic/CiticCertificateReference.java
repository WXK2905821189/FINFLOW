package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;

/** Logical certificate alias only; raw certificates and private keys are never accepted here. */
public record CiticCertificateReference(String alias) {

    public CiticCertificateReference {
        if (alias == null || !alias.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException(400, "Certificate reference must be a safe logical alias");
        }
    }
}
