package com.finance.system.bank.dto;

import jakarta.validation.constraints.Size;

/**
 * 人工指定某银行账户对应的金蝶银行账号档案编码（V41）。
 *
 * <p>值取自金蝶 CN_BANKACNT 的 FNumber（可在「核算维度 › 银行账号」下拉中看到）。
 * 空白表示清除映射——清除后该账户若为 KINGDEE_AUTO，制证会被阻断并提示补映射。</p>
 */
public record KingdeeMappingRequest(
        @Size(max = 128, message = "Kingdee account number is too long") String kingdeeAccountNumber) {
}
