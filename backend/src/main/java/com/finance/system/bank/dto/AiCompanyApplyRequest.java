package com.finance.system.bank.dto;

import java.util.List;

/** AI 归类建议批量应用请求：仅携带用户在预览中勾选的行，公司不存在则建档。 */
public record AiCompanyApplyRequest(List<Item> items) {

    public record Item(Long accountId, String companyName) {
    }
}
