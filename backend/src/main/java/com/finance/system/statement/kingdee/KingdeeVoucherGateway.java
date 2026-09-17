package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;

public interface KingdeeVoucherGateway {

    KingdeeVoucherResult push(StatementRecord statement);

    /**
     * Saves a rule-engine journal voucher (GL_VOUCHER) as a DRAFT. The payload is built by
     * the rule engine (V34 WP-B) and must already be debit/credit balanced; the gateway
     * only performs the save call and response parsing. No auto submit/audit — vouchers
     * are always reviewed by finance on the Kingdee side (T8 decision).
     *
     * @param payloadJson GL_VOUCHER save payload (see KingdeeGlVoucherPayloadBuilder)
     */
    KingdeeVoucherResult pushGlVoucher(String payloadJson);

    /**
     * Read-only connectivity probe for the UI "connection test" button. Must never
     * create or modify data on the Kingdee side (query-only by contract).
     */
    KingdeeConnectionStatus ping();
}
