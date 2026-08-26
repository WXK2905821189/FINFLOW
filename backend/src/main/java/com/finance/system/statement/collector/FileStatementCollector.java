package com.finance.system.statement.collector;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.dto.StatementImportRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "statement.collection", name = "mode", havingValue = "file", matchIfMissing = true)
public class FileStatementCollector implements StatementCollector {

    @Override
    public StatementCollection collect(StatementImportRequest request) {
        if (request.records() == null || request.records().isEmpty()) {
            throw new BusinessException(400, "At least one statement record is required");
        }
        return new StatementCollection("FILE", request.sourceName(), request.records());
    }
}
