package com.finance.system.validation.dto;
import java.time.LocalDateTime;
public record AccountingMappingResponse(Long id, String mappingCode, String name, String direction,
                                        String counterpartyKeyword, String debitSubject, String creditSubject,
                                        String voucherTemplate, Integer versionNo, String status, LocalDateTime updatedAt) {}
