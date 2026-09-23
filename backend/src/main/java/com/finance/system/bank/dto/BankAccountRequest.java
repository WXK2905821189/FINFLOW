package com.finance.system.bank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create/update payload for a bank account.
 *
 * <p>V34 ⑧（WP-E）新增表单必填降为 2 项（户名 + 账号）：银行由前端按账号位数特征识别
 * （识别失败强制手选，因此提交时 bankCode 恒有值，服务端保持必填校验）；币种/初始余额/
 * 状态降为可选，空值由 {@code BankAccountService.create} 兜底（CNY / 0.00 / ACTIVE）；
 * {@code companyId} 可选指定归属公司主体（缺省=操作人本公司，跨公司归属需要
 * {@code bankdata:cross-company:view} 权限；更新路径忽略该字段——归属变更统一走档案页）。</p>
 */
public record BankAccountRequest(
        @NotBlank(message = "Bank code is required") @Size(max = 32) String bankCode,
        @NotBlank(message = "Account name is required") @Size(max = 128) String accountName,
        @NotBlank(message = "Account number is required") @Pattern(regexp = "^[0-9A-Za-z]{8,64}$", message = "Account number format is invalid") String accountNumber,
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter code") String currency,
        @DecimalMin(value = "0.00", message = "Available balance must not be negative") BigDecimal availableBalance,
        @Size(max = 32) String status,
        /** 制证模式（V31，可选）：KINGDEE_AUTO=AI 制证推送金蝶；MANUAL=纯人工制证。null 时保持默认 KINGDEE_AUTO。 */
        @Pattern(regexp = "^(KINGDEE_AUTO|MANUAL)$", message = "Accounting mode must be KINGDEE_AUTO or MANUAL") String accountingMode,
        /** 可选归属公司主体（V34 ⑧）：null=操作人本公司；跨公司需 cross-company 权限。 */
        Long companyId,
        /** 可选金蝶 CN_BANKACNT 档案编码，填入后可直接参与制证。 */
        @Size(max = 128) String kingdeeAccountNumber
) {
}
