package com.finance.system.bankdata.dto;

/**
 * 公司主体下拉选项（/bank-data/company-options）。
 * 跨公司权限用户返回全部 ACTIVE 公司；无权限者仅返回本公司（行为不变）。
 */
public record CompanyOptionResponse(
        Long id,
        String name
) {
}
