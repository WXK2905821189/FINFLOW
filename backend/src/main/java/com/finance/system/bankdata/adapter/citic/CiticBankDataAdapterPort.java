package com.finance.system.bankdata.adapter.citic;

/**
 * Boundary for a future authorized CITIC SDK adapter. Implementations must be supplied
 * separately and must not persist certificate contents or perform calls from this module.
 */
public interface CiticBankDataAdapterPort {

    CiticPreparedRequest prepare(CiticSyncCommand command);

    CiticParsedResponse parse(CiticTransportResponse response);
}
