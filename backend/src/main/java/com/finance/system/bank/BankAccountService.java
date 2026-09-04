package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.common.tenant.CompanyScopeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class BankAccountService extends ServiceImpl<BankAccountMapper, BankAccount> {

    private final BankServiceFactory bankServiceFactory;
    private final CompanyScopeService companyScope;
    private final AccountDirectStatusService directStatusService;

    public BankAccountService(BankServiceFactory bankServiceFactory, CompanyScopeService companyScope,
                              AccountDirectStatusService directStatusService) {
        this.bankServiceFactory = bankServiceFactory;
        this.companyScope = companyScope;
        this.directStatusService = directStatusService;
    }

    public List<BankAccountResponse> listResponses(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<BankAccount> accounts = list(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, companyId)
                .orderByAsc(BankAccount::getId));
        Map<Long, AccountDirectStatusService.DirectStatusView> statuses = directStatusService.resolve(accounts);
        return accounts.stream()
                .map(account -> toResponse(account, statuses.get(account.getId())))
                .toList();
    }

    public BankAccountResponse create(Long userId, BankAccountRequest request) {
        bankServiceFactory.get(request.bankCode());
        BankAccount account = new BankAccount();
        account.setCompanyId(companyScope.companyIdForUser(userId));
        apply(request, account);
        save(account);
        return toResponse(account, directStatusService.resolveOne(account));
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
        return toResponse(account, directStatusService.resolveOne(account));
    }

    private void apply(BankAccountRequest request, BankAccount account) {
        account.setBankCode(request.bankCode().trim().toUpperCase());
        account.setAccountName(request.accountName().trim());
        account.setAccountNumber(request.accountNumber().trim());
        account.setCurrency(request.currency().trim().toUpperCase());
        account.setAvailableBalance(request.availableBalance());
        account.setStatus(request.status().trim().toUpperCase());
    }

    private BankAccountResponse toResponse(BankAccount account, AccountDirectStatusService.DirectStatusView direct) {
        AccountDirectStatusService.DirectStatusView view = direct == null
                ? new AccountDirectStatusService.DirectStatusView(AccountDirectStatusService.NOT_CONNECTED, null)
                : direct;
        return new BankAccountResponse(account.getId(), account.getBankCode(), account.getAccountName(),
                maskAccountNumber(account.getAccountNumber()), account.getCurrency(), account.getAvailableBalance(),
                account.getStatus(), view.status(), view.lastRealSyncAt());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "**** **** " + accountNumber.substring(accountNumber.length() - 4);
    }
}
