package com.finance.system.bank.dto;

public record BankTransferResponse(
        String bankCode,
        String bankReference,
        String status,
        String message
) {
}
