package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps the internal boundary available until a separately approved adapter is supplied. */
@Component
@ConditionalOnProperty(prefix = "kingdee", name = "mock-mode", havingValue = "false")
public class UnavailableKingdeeVoucherGateway implements KingdeeVoucherGateway {

    @Override
    public KingdeeVoucherResult push(StatementRecord statement) {
        return new KingdeeVoucherResult(null, "UNAVAILABLE", "Kingdee production adapter is not configured");
    }
}
