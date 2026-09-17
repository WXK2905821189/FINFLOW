package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kingdee", name = "mock-mode", havingValue = "true", matchIfMissing = true)
public class MockKingdeeVoucherGateway implements KingdeeVoucherGateway {

    @Override
    public KingdeeVoucherResult push(StatementRecord statement) {
        return new KingdeeVoucherResult(
                "KD-MOCK-" + statement.getStatementNo(), "PUSHED", "Accepted by Kingdee mock gateway");
    }

    @Override
    public KingdeeVoucherResult pushGlVoucher(String payloadJson) {
        return new KingdeeVoucherResult(
                "GL-MOCK-" + Math.abs(payloadJson.hashCode()), "PUSHED",
                "Accepted by Kingdee mock gateway (GL_VOUCHER draft)");
    }

    @Override
    public KingdeeConnectionStatus ping() {
        return new KingdeeConnectionStatus(false, "MOCK",
                "当前为模拟网关（kingdee.mock-mode=true），推送只产生模拟凭证号，未连接真实金蝶");
    }
}
