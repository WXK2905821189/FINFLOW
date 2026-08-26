package com.finance.system.statement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StatementImportRequest(
        @Size(max = 128, message = "Source name must be at most 128 characters") String sourceName,
        @Valid List<StatementRecordInput> records
) {
}
