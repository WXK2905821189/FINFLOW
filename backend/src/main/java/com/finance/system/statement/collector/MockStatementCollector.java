package com.finance.system.statement.collector;

import com.finance.system.statement.dto.StatementImportRequest;
import com.finance.system.statement.dto.StatementRecordInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "statement.collection", name = "mode", havingValue = "mock")
public class MockStatementCollector implements StatementCollector {

    @Override
    public StatementCollection collect(StatementImportRequest request) {
        if (request.records() != null && !request.records().isEmpty()) {
            return new StatementCollection("MOCK", request.sourceName(), request.records());
        }
        StatementRecordInput sample = new StatementRecordInput(
                "MOCK-" + System.currentTimeMillis(), 1L, LocalDateTime.now().minusMinutes(5),
                "INCOME", new BigDecimal("100.00"), "CNY", "模拟客户", "MOCK-ACCOUNT", "模拟银行流水");
        return new StatementCollection("MOCK", request.sourceName(), List.of(sample));
    }
}
