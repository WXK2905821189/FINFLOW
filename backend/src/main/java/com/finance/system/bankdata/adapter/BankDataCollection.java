package com.finance.system.bankdata.adapter;

import java.util.List;

public record BankDataCollection(String bankRequestNo, List<BankDataEntry> entries) {
}
