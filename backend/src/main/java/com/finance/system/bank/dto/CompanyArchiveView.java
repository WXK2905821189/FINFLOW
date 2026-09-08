package com.finance.system.bank.dto;

import java.util.List;

/**
 * Single payload for the account-filing drawer: every company archive plus every
 * bank account across companies. One round trip keeps drag-and-drop snappy.
 */
public record CompanyArchiveView(
        List<CompanyArchiveCompany> companies,
        List<CompanyArchiveAccount> accounts
) {
}
