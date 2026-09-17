package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;

public interface KingdeeVoucherGateway {

    KingdeeVoucherResult push(StatementRecord statement);

    /**
     * Read-only connectivity probe for the UI "connection test" button. Must never
     * create or modify data on the Kingdee side (query-only by contract).
     */
    KingdeeConnectionStatus ping();
}
