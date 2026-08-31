package com.finance.system.validation.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record AccountingMappingRequest(@NotBlank @Size(max=64) String mappingCode, @NotBlank @Size(max=128) String name,
                                        @NotBlank String direction, @Size(max=128) String counterpartyKeyword,
                                        @NotBlank @Size(max=64) String debitSubject, @NotBlank @Size(max=64) String creditSubject,
                                        @NotBlank @Size(max=128) String voucherTemplate) {}
