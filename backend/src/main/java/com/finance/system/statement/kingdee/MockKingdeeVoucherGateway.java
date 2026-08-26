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
}
