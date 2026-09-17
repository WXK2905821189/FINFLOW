package com.finance.system.bank.dto;

import java.util.List;

/** AI 归类批量应用结果：逐行状态（CREATED/ASSIGNED/FAILED），单行失败不回滚整批。 */
public record AiCompanyApplyResponse(List<Row> rows, long createdCompanies, long assignedAccounts) {

    public record Row(Long accountId, String accountName, String companyName,
                      String outcome, String message) {
    }
}
