package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;

public interface KingdeeVoucherGateway {

    KingdeeVoucherResult push(StatementRecord statement);
}
