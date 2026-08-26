package com.finance.system.bank;

import com.finance.system.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class BankServiceFactory {

    private final Map<String, BankService> services;

    public BankServiceFactory(Collection<BankService> serviceImplementations) {
        Map<String, BankService> candidates = new LinkedHashMap<>();
        for (BankService service : serviceImplementations) {
            String code = service.bankCode().toUpperCase(Locale.ROOT);
            if (candidates.putIfAbsent(code, service) != null) {
                throw new IllegalStateException("Duplicate bank service: " + code);
            }
        }
        this.services = Map.copyOf(candidates);
    }

    public BankService get(String bankCode) {
        if (bankCode == null || bankCode.isBlank()) {
            throw new BusinessException(400, "Bank code is required");
        }
        BankService service = services.get(bankCode.toUpperCase(Locale.ROOT));
        if (service == null) {
            throw new BusinessException(404, "Unsupported bank: " + bankCode);
        }
        return service;
    }

    public Collection<String> supportedBankCodes() {
        return services.keySet().stream().sorted().toList();
    }
}
