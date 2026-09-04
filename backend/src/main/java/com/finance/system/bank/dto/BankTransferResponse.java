package com.finance.system.bank.dto;

public record BankTransferResponse(
        Long paymentId,
        String paymentNo,
        String bankCode,
        String bankReference,
        String status,
        String message
) {
}
