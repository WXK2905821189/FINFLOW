package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Fail-closed placeholder: active when the mock is disabled but the real SDK gateway is not
 * enabled ({@code kingdee.mock-mode=false} + {@code kingdee.real-enabled!=true}). Mutually
 * exclusive with {@code RealKingdeeVoucherGateway} (kingdee-sdk Maven profile) via
 * {@link KingdeeUnavailableCondition}.
 */
@Component
@Conditional(KingdeeUnavailableCondition.class)
public class UnavailableKingdeeVoucherGateway implements KingdeeVoucherGateway {

    @Override
    public KingdeeVoucherResult push(StatementRecord statement) {
        return new KingdeeVoucherResult(null, "UNAVAILABLE",
                "Kingdee real gateway is not enabled; set kingdee.real-enabled=true with the kingdee-sdk Maven profile");
    }

    @Override
    public KingdeeVoucherResult pushGlVoucher(String payloadJson) {
        return new KingdeeVoucherResult(null, "UNAVAILABLE",
                "Kingdee real gateway is not enabled; set kingdee.real-enabled=true with the kingdee-sdk Maven profile");
    }

    @Override
    public KingdeeConnectionStatus ping() {
        return new KingdeeConnectionStatus(false, "UNAVAILABLE",
                "Real 网关未激活：需同时满足 kingdee-sdk Maven profile 编译（本地 ~/.m2 有 SDK jar）"
                        + "与 KINGDEE_REAL_ENABLED=true 及凭据环境变量");
    }

    /** 网关未激活 → 科目目录不可用（空表）；调用方据此跳过后端校验并在结果里标注。 */
    @Override
    public java.util.List<KingdeeAccountRef> queryAccountCatalog() {
        return java.util.List.of();
    }

    /** 网关未激活 → 银行账号档案不可用（空表）；账户映射页据此提示「需先激活 Real 网关」。 */
    @Override
    public java.util.List<KingdeeBankAccountRef> queryBankAccountCatalog() {
        return java.util.List.of();
    }
}
