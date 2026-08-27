package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.bank.dto.BankTransferRequest;
import com.finance.system.bank.dto.BankTransferResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.bankdata.scope.CompanyScopeService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class BankAccountService extends ServiceImpl<BankAccountMapper, BankAccount> {

    private final BankServiceFactory bankServiceFactory;
    private final CompanyScopeService companyScope;

    public BankAccountService(BankServiceFactory bankServiceFactory, CompanyScopeService companyScope) {
        this.bankServiceFactory = bankServiceFactory;
        this.companyScope = companyScope;
    }

    public List<BankAccountResponse> listResponses(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        return list(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, companyId)
                .orderByAsc(BankAccount::getId)).stream()
                .map(this::toResponse).toList();
    }

    public BankAccountResponse create(Long userId, BankAccountRequest request) {
        bankServiceFactory.get(request.bankCode());
        BankAccount account = new BankAccount();
        account.setCompanyId(companyScope.companyIdForUser(userId));
        apply(request, account);
        save(account);
        return toResponse(account);
    }

    public BankAccountResponse updateAccount(Long userId, Long id, BankAccountRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount account = getOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, id)
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) {
            throw new BusinessException(404, "Bank account not found");
        }
        bankServiceFactory.get(request.bankCode());
        apply(request, account);
        updateById(account);
        return toResponse(account);
    }

    public BankTransferResponse submitTransfer(Long userId, BankTransferRequest request) {
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount payer = getOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, request.payerAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        if (payer == null) {
            throw new BusinessException(404, "Payer account not found");
        }
        if (!"ACTIVE".equalsIgnoreCase(payer.getStatus())) {
            throw new BusinessException(409, "Payer account is not active");
        }
        if (!payer.getBankCode().equalsIgnoreCase(request.bankCode())) {
            throw new BusinessException(400, "Payer account does not belong to the selected bank");
        }
        BigDecimal availableBalance = bankServiceFactory.get(request.bankCode()).queryAvailableBalance(payer);
        if (availableBalance.compareTo(request.amount()) < 0) {
            throw new BusinessException(409, "Available balance is insufficient");
        }
        return bankServiceFactory.get(request.bankCode()).submitTransfer(payer, new BankTransferCommand(
                request.payeeName(), request.payeeAccount(), request.payeeBank(), request.amount(), request.remark()));
    }

    private void apply(BankAccountRequest request, BankAccount account) {
        account.setBankCode(request.bankCode().trim().toUpperCase());
        account.setAccountName(request.accountName().trim());
        account.setAccountNumber(request.accountNumber().trim());
        account.setCurrency(request.currency().trim().toUpperCase());
        account.setAvailableBalance(request.availableBalance());
        account.setStatus(request.status().trim().toUpperCase());
    }

    private BankAccountResponse toResponse(BankAccount account) {
        return new BankAccountResponse(account.getId(), account.getBankCode(), account.getAccountName(),
                maskAccountNumber(account.getAccountNumber()), account.getCurrency(), account.getAvailableBalance(), account.getStatus());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "**** **** " + accountNumber.substring(accountNumber.length() - 4);
    }
}
