package com.finance.system.bank.dto;

/**
 * One bank account row in the filing drawer, across every company archive.
 * The account number is masked - the drawer never needs the raw number.
 *
 * @param companyId the archive the account currently belongs to; null only for
 *                  legacy rows predating the company backfill (V4).
 */
public record CompanyArchiveAccount(
        Long id,
        String accountName,
        String maskedAccountNumber,
        String bankCode,
        String currency,
        String status,
        Long companyId,
        String directStatus
) {
}
