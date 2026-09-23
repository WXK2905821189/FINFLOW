package com.finance.system.bankdata.adapter;

import java.util.List;

public interface BankDataAdapter {

    String adapterCode();

    BankDataCollection collect(BankDataSyncContext context);

    /** Existing and test adapters stay simulated unless a future adapter explicitly opts in. */
    default BankAdapterExecutionMode executionMode() {
        return BankAdapterExecutionMode.SIMULATED;
    }

    /**
     * W17 包 F：历史（按日）余额查询，供 BankDataBackfillService 回补银行接入日以前的
     * 余额快照。窗口取 context.windowStart()/windowEnd()（含两端日期）。不支持历史余额的
     * adapter（Mock/测试替身）返回空成功集合，使回补余额片以 0 行正常完成。
     */
    default BankDataCollection collectHistoryBalance(BankDataSyncContext context) {
        return new BankDataCollection(null, List.of(), List.of(), false, null, "SUCCESS", "SUCCESS");
    }
}
