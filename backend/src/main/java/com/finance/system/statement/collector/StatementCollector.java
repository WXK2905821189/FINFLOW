package com.finance.system.statement.collector;

import com.finance.system.statement.dto.StatementImportRequest;

public interface StatementCollector {

    StatementCollection collect(StatementImportRequest request);
}
