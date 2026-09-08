package com.finance.system.bank.dto;

/**
 * One company archive row in the drag-and-drop filing drawer.
 *
 * @param accountCount how many bank accounts currently point at this company;
 *                     the archive view is cross-company on purpose (bank:manage gate).
 */
public record CompanyArchiveCompany(
        Long id,
        String code,
        String name,
        String status,
        long accountCount
) {
}
