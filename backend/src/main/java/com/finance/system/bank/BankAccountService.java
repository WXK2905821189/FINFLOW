package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.system.bank.dto.BankAccountRequest;
import com.finance.system.bank.dto.BankAccountResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.rbac.RbacService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BankAccountService extends ServiceImpl<BankAccountMapper, BankAccount> {

    private final BankServiceFactory bankServiceFactory;
    private final CompanyScopeService companyScope;
    private final AccountDirectStatusService directStatusService;
    private final CompanyMapper companyMapper;
    private final RbacService rbacService;

    public BankAccountService(BankServiceFactory bankServiceFactory, CompanyScopeService companyScope,
                              AccountDirectStatusService directStatusService, CompanyMapper companyMapper,
                              RbacService rbacService) {
        this.bankServiceFactory = bankServiceFactory;
        this.companyScope = companyScope;
        this.directStatusService = directStatusService;
        this.companyMapper = companyMapper;
        this.rbacService = rbacService;
    }

    public List<BankAccountResponse> listResponses(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        // 跨公司权限（V24）用户返回全部 ACTIVE 公司账户：数据查询页的「公司主体 → 账户」
        // 两级分组需要他公司账户作为选项；单公司用户维持本公司隔离不变。
        boolean crossCompany = rbacService.permissionCodesForUser(userId)
                .contains("bankdata:cross-company:view");
        List<BankAccount> accounts = crossCompany
                ? list(new LambdaQueryWrapper<BankAccount>()
                        .in(BankAccount::getCompanyId, companyMapper.selectList(
                                        new LambdaQueryWrapper<Company>().eq(Company::getStatus, "ACTIVE"))
                                .stream().map(Company::getId).toList())
                        .orderByAsc(BankAccount::getCompanyId).orderByAsc(BankAccount::getId))
                : list(new LambdaQueryWrapper<BankAccount>()
                        .eq(BankAccount::getCompanyId, companyId)
                        .orderByAsc(BankAccount::getId));
        Map<Long, Company> companiesById = accounts.isEmpty() ? Map.of()
                : companyMapper.selectList(new LambdaQueryWrapper<Company>()
                        .in(Company::getId, accounts.stream().map(BankAccount::getCompanyId).distinct().toList()))
                .stream().collect(Collectors.toMap(Company::getId, Function.identity(), (a, b) -> a));
        Map<Long, AccountDirectStatusService.DirectStatusView> statuses = directStatusService.resolve(accounts);
        return accounts.stream()
                .map(account -> toResponse(account, statuses.get(account.getId()),
                        companiesById.get(account.getCompanyId())))
                .toList();
    }

    public BankAccountResponse create(Long userId, BankAccountRequest request) {
        bankServiceFactory.get(request.bankCode());
        long ownCompanyId = companyScope.companyIdForUser(userId);
        // V34 ⑧：可选归属公司主体——缺省=操作人本公司；跨公司归属需要 cross-company 权限。
        long companyId = resolveCompanyId(userId, ownCompanyId, request.companyId());
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        apply(request, account);
        save(account);
        return toResponse(account, directStatusService.resolveOne(account), companyMapper.selectById(companyId));
    }

    /**
     * V34 ⑧ 归属解析：{@code companyId} 缺省/越权语义见 {@link BankAccountRequest}。
     * 跨公司校验与 {@code BankDataAccountingService} 制证跨公司行同款（权限码
     * {@code bankdata:cross-company:view}）。
     */
    private long resolveCompanyId(Long userId, long ownCompanyId, Long requestedCompanyId) {
        if (requestedCompanyId == null || requestedCompanyId <= 0 || requestedCompanyId == ownCompanyId) {
            return ownCompanyId;
        }
        if (!rbacService.permissionCodesForUser(userId).contains("bankdata:cross-company:view")) {
            throw new BusinessException(403, "跨公司建立银行账户需要跨公司数据权限");
        }
        if (companyMapper.selectById(requestedCompanyId) == null) {
            throw new BusinessException(404, "目标公司主体不存在");
        }
        return requestedCompanyId;
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
        return toResponse(account, directStatusService.resolveOne(account), null);
    }

    /**
     * 档案移除（V32 软删除）：{@link BankAccount} 带 @TableLogic，removeById 落库为
     * UPDATE deleted=1——历史流水/余额/报文全保留，档案板/下拉/查询/调度自动过滤。
     * 可见性规则与 updateAccount 一致（本公司账户；404 不区分不存在与已删除）。
     */
    public void deleteAccount(Long userId, Long id) {
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount account = getOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, id)
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) {
            throw new BusinessException(404, "Bank account not found");
        }
        removeById(id);
    }

    private void apply(BankAccountRequest request, BankAccount account) {
        account.setBankCode(request.bankCode().trim().toUpperCase());
        account.setAccountName(request.accountName().trim());
        account.setAccountNumber(request.accountNumber().trim());
        // V34 ⑧：币种/余额/状态降为可选，空值兜底（CNY / 0.00 / ACTIVE）——新增表单必填只有户名+账号。
        account.setCurrency(request.currency() == null || request.currency().isBlank()
                ? "CNY" : request.currency().trim().toUpperCase());
        account.setAvailableBalance(request.availableBalance() == null
                ? new java.math.BigDecimal("0.00") : request.availableBalance());
        account.setStatus(request.status() == null || request.status().isBlank()
                ? "ACTIVE" : request.status().trim().toUpperCase());
        // V31 制证模式：null 保持默认（KINGDEE_AUTO），显式 MANUAL 才落纯人工制证。
        account.setAccountingMode(request.accountingMode() == null ? "KINGDEE_AUTO"
                : request.accountingMode().trim().toUpperCase());
    }

    private BankAccountResponse toResponse(BankAccount account, AccountDirectStatusService.DirectStatusView direct,
                                           Company company) {
        AccountDirectStatusService.DirectStatusView view = direct == null
                ? new AccountDirectStatusService.DirectStatusView(AccountDirectStatusService.NOT_CONNECTED, null)
                : direct;
        return new BankAccountResponse(account.getId(), account.getBankCode(), account.getAccountName(),
                maskAccountNumber(account.getAccountNumber()), account.getCurrency(), account.getAvailableBalance(),
                account.getStatus(), view.status(), view.lastRealSyncAt(),
                account.getCompanyId(), company == null ? null : company.getName(),
                account.getAccountingMode() == null ? "KINGDEE_AUTO" : account.getAccountingMode());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "**** **** " + accountNumber.substring(accountNumber.length() - 4);
    }
}
