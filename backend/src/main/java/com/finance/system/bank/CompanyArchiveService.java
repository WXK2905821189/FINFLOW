package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.bank.dto.CompanyArchiveAccount;
import com.finance.system.bank.dto.CompanyArchiveCompany;
import com.finance.system.bank.dto.CompanyArchiveView;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Company-archive filing for bank accounts (the drag-and-drop drawer).
 *
 * <p>Re-filing an account does more than flip {@code bank_account.company_id}:
 * balance and statement rows denormalize {@code company_id} at insert time, so the
 * same transaction rewrites the historical rows too - otherwise the projection
 * filters would keep showing the account under its old company indefinitely.
 */
@Service
public class CompanyArchiveService {

    private final CompanyMapper companyMapper;
    private final BankAccountMapper bankAccountMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataStatementMapper statementMapper;
    private final AccountDirectStatusService directStatusService;

    public CompanyArchiveService(CompanyMapper companyMapper, BankAccountMapper bankAccountMapper,
                                 BankDataBalanceMapper balanceMapper, BankDataStatementMapper statementMapper,
                                 AccountDirectStatusService directStatusService) {
        this.companyMapper = companyMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.balanceMapper = balanceMapper;
        this.statementMapper = statementMapper;
        this.directStatusService = directStatusService;
    }

    public CompanyArchiveView view() {
        List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .orderByAsc(Company::getId));
        List<BankAccount> accounts = bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                .orderByAsc(BankAccount::getId));
        Map<Long, AccountDirectStatusService.DirectStatusView> statuses = directStatusService.resolve(accounts);
        Map<Long, Long> counts = accounts.stream()
                .filter(account -> account.getCompanyId() != null)
                .collect(Collectors.groupingBy(BankAccount::getCompanyId, Collectors.counting()));
        List<CompanyArchiveCompany> companyRows = companies.stream()
                .map(company -> new CompanyArchiveCompany(company.getId(), company.getCode(), company.getName(),
                        company.getStatus(), counts.getOrDefault(company.getId(), 0L)))
                .toList();
        List<CompanyArchiveAccount> accountRows = accounts.stream()
                .map(account -> toAccount(account, statuses.get(account.getId())))
                .toList();
        return new CompanyArchiveView(companyRows, accountRows);
    }

    public CompanyArchiveCompany createCompany(String name) {
        String trimmed = name.trim();
        assertNameAvailable(trimmed, null);
        Company company = new Company();
        company.setCode(nextCompanyCode());
        company.setName(trimmed);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return new CompanyArchiveCompany(company.getId(), company.getCode(), company.getName(),
                company.getStatus(), 0L);
    }

    public CompanyArchiveCompany renameCompany(Long id, String name) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new BusinessException(404, "Company archive not found");
        }
        String trimmed = name.trim();
        assertNameAvailable(trimmed, id);
        company.setName(trimmed);
        companyMapper.updateById(company);
        long count = bankAccountMapper.selectCount(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, id));
        return new CompanyArchiveCompany(company.getId(), company.getCode(), company.getName(),
                company.getStatus(), count);
    }

    @Transactional
    public CompanyArchiveAccount assignAccount(Long accountId, Long companyId) {
        BankAccount account = bankAccountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException(404, "Bank account not found");
        }
        Company company = companyMapper.selectById(companyId);
        if (company == null) {
            throw new BusinessException(404, "Company archive not found");
        }
        if (!"ACTIVE".equals(company.getStatus())) {
            throw new BusinessException(400, "目标公司档案未启用，不能归入");
        }
        if (!companyId.equals(account.getCompanyId())) {
            account.setCompanyId(companyId);
            bankAccountMapper.updateById(account);
            balanceMapper.update(null, new LambdaUpdateWrapper<BankDataBalance>()
                    .eq(BankDataBalance::getBankAccountId, accountId)
                    .set(BankDataBalance::getCompanyId, companyId));
            statementMapper.update(null, new LambdaUpdateWrapper<BankDataStatement>()
                    .eq(BankDataStatement::getBankAccountId, accountId)
                    .set(BankDataStatement::getCompanyId, companyId));
        }
        return toAccount(account, directStatusService.resolveOne(account));
    }

    private void assertNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<Company> wrapper = new LambdaQueryWrapper<Company>().eq(Company::getName, name);
        if (excludeId != null) {
            wrapper.ne(Company::getId, excludeId);
        }
        Long count = companyMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(400, "公司名称已存在");
        }
    }

    private String nextCompanyCode() {
        long existing = companyMapper.selectCount(null);
        for (int i = 0; i < 8; i++) {
            String candidate = String.format("CMP-%06d", existing + 1 + i);
            Long clash = companyMapper.selectCount(new LambdaQueryWrapper<Company>().eq(Company::getCode, candidate));
            if (clash == null || clash == 0) {
                return candidate;
            }
        }
        throw new BusinessException(500, "Unable to allocate a company code");
    }

    private CompanyArchiveAccount toAccount(BankAccount account, AccountDirectStatusService.DirectStatusView direct) {
        AccountDirectStatusService.DirectStatusView view = direct == null
                ? new AccountDirectStatusService.DirectStatusView(AccountDirectStatusService.NOT_CONNECTED, null)
                : direct;
        return new CompanyArchiveAccount(account.getId(), account.getAccountName(),
                maskAccountNumber(account.getAccountNumber()), account.getBankCode(), account.getCurrency(),
                account.getStatus(), account.getCompanyId(), view.status());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "**** **** " + accountNumber.substring(accountNumber.length() - 4);
    }
}
