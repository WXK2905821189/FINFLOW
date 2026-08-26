package com.finance.system.statement.collector;

import com.finance.system.statement.dto.StatementRecordInput;

import java.util.List;

public record StatementCollection(String sourceType, String sourceName, List<StatementRecordInput> records) {
}
