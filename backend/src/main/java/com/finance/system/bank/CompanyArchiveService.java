package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.bank.dto.AiCompanyApplyRequest;
import com.finance.system.bank.dto.AiCompanyApplyResponse;
import com.finance.system.bank.dto.CompanyArchiveAccount;
import com.finance.system.bank.dto.CompanyArchiveCompany;
import com.finance.system.bank.dto.CompanyArchiveView;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final StatementRecordMapper statementRecordMapper;
    private final AccountDirectStatusService directStatusService;
    private final SysUserMapper sysUserMapper;
    private final KingdeeVoucherRuleMapper ruleMapper;
    private final SystemAuditService auditService;

    public CompanyArchiveService(CompanyMapper companyMapper, BankAccountMapper bankAccountMapper,
                                 BankDataBalanceMapper balanceMapper, BankDataStatementMapper statementMapper,
                                 StatementRecordMapper statementRecordMapper,
                                 AccountDirectStatusService directStatusService, SysUserMapper sysUserMapper,
                                 KingdeeVoucherRuleMapper ruleMapper, SystemAuditService auditService) {
        this.companyMapper = companyMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.balanceMapper = balanceMapper;
        this.statementMapper = statementMapper;
        this.statementRecordMapper = statementRecordMapper;
        this.directStatusService = directStatusService;
        this.sysUserMapper = sysUserMapper;
        this.ruleMapper = ruleMapper;
        this.auditService = auditService;
    }

    public CompanyArchiveView view() {
        List<Company> companies = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getStatus, "ACTIVE")
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

    @Transactional
    public void deleteCompany(Long operatorId, Long id) {
        Company company = companyMapper.selectById(id);
        if (company == null || !"ACTIVE".equals(company.getStatus())) {
            throw new BusinessException(404, "公司主体不存在或已停用");
        }
        List<String> references = new ArrayList<>();
        // 引用判据（W17 #1a）：账户「活跃」= 自身 status 非 INACTIVE（软删账户由 @TableLogic
        // 自动排除，不算占用）；用户只算 ACTIVE；银行流水/余额按 company_id 计数；凭证规则
        // scope 按公司编码精确分词匹配（LIKE 是超集，误伤同前缀编码，见 scopeReferencesCompany）。
        long activeAccounts = bankAccountMapper.selectCount(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, id)
                .ne(BankAccount::getStatus, "INACTIVE"));
        if (activeAccounts > 0) references.add("活跃银行账户 " + activeAccounts + " 个");
        long activeUsers = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getCompanyId, id).eq(SysUser::getStatus, "ACTIVE"));
        if (activeUsers > 0) references.add("在职用户 " + activeUsers + " 个");
        long statements = statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, id));
        if (statements > 0) references.add("未归档银行流水 " + statements + " 笔");
        long balances = balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, id));
        if (balances > 0) references.add("未归档银行余额 " + balances + " 条");
        long standardStatements = statementRecordMapper.selectCount(new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, id));
        if (standardStatements > 0) references.add("标准流水记录 " + standardStatements + " 笔");
        long scopedRuleHits = 0;
        for (KingdeeVoucherRule rule : ruleMapper.selectList(new LambdaQueryWrapper<KingdeeVoucherRule>()
                .eq(KingdeeVoucherRule::getEnabled, true))) {
            if (scopeReferencesCompany(rule.getScopeOrgs(), company.getCode())) {
                scopedRuleHits++;
            }
        }
        if (scopedRuleHits > 0) references.add("启用中的凭证规则（主体范围引用 " + company.getCode() + "）" + scopedRuleHits + " 条");
        if (!references.isEmpty()) {
            throw new BusinessException(409, "公司主体仍被以下资源引用，无法停用：" + String.join("；", references));
        }
        Company update = new Company();
        update.setId(id);
        update.setStatus("INACTIVE");
        int updated = companyMapper.updateById(update);
        if (updated != 1) {
            throw new BusinessException(409, "公司主体状态更新失败，请重试");
        }
        auditService.record(operatorId, "COMPANY_ARCHIVE_DELETE", "COMPANY", company.getCode(), null,
                "SUCCESS", "companyId=" + id + ", name=" + company.getName());
    }

    /**
     * 规则 scope_orgs 是 CSV（如 "ALL" 或 "300,410,710"）；"ALL" 表示全主体（含待删主体），
     * 其余按分词后精确比对——不用 LIKE，避免编码 300 误匹配 3001。
     */
    private boolean scopeReferencesCompany(String scopeOrgs, String companyCode) {
        if (scopeOrgs == null || scopeOrgs.isBlank()) {
            return false;
        }
        if ("ALL".equalsIgnoreCase(scopeOrgs.trim())) {
            return true;
        }
        for (String part : scopeOrgs.split(",")) {
            if (part.trim().equals(companyCode)) {
                return true;
            }
        }
        return false;
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
            // W17 #1a：INACTIVE 主体禁作归类目标（409），前端据此提示用户改选其他主体。
            throw new BusinessException(409, "目标公司主体已停用，不能归入");
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

    /**
     * 取消归属（2026-09-17 需求：归档板常驻「未归属」区，账户可拖回等待 AI 智能归类）。
     *
     * <p>只重置 {@code bank_account.company_id}（UpdateWrapper 显式置 NULL——updateById
     * 的 NOT_NULL 策略会跳过 null 字段，无法清空）；历史流水/余额的 company_id 为
     * NOT NULL 约束且代表已发生的事实，**保留原归属口径不变**——单公司用户对既有
     * 历史数据的可见性不受取消归属影响，仅账户归属与后续新同步数据的挂载点变化。
     * 与 {@link #assignAccount}（三表联动迁移）的差异化语义在此显式记录。</p>
     */
    @Transactional
    public CompanyArchiveAccount unassignAccount(Long accountId) {
        BankAccount account = bankAccountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException(404, "Bank account not found");
        }
        if (account.getCompanyId() != null) {
            bankAccountMapper.update(null, new LambdaUpdateWrapper<BankAccount>()
                    .eq(BankAccount::getId, accountId)
                    .set(BankAccount::getCompanyId, null));
        }
        return toAccount(bankAccountMapper.selectById(accountId), directStatusService.resolveOne(account));
    }

    /**
     * AI 归类建议批量应用（V32）：公司按名称解析——已有同名档案直接复用，否则新建
     * （复用 createCompany 的编码分配与校验），再走 {@link #assignAccount} 挂账户
     * （历史流水/余额的 company_id 一并迁移）。单行失败记 FAILED 不回滚整批，
     * 与 AI 制证批量语义一致。
     */
    @Transactional
    public AiCompanyApplyResponse applySuggestions(AiCompanyApplyRequest request) {
        List<AiCompanyApplyRequest.Item> items = request == null || request.items() == null
                ? List.of() : request.items();
        if (items.isEmpty()) {
            throw new BusinessException(400, "请选择要应用的归类建议");
        }
        List<AiCompanyApplyResponse.Row> rows = new ArrayList<>();
        long created = 0;
        long assigned = 0;
        for (AiCompanyApplyRequest.Item item : items) {
            if (item == null || item.accountId() == null
                    || item.companyName() == null || item.companyName().isBlank()) {
                rows.add(new AiCompanyApplyResponse.Row(null, null, null, "FAILED", "行数据不完整"));
                continue;
            }
            String companyName = item.companyName().trim();
            BankAccount account = bankAccountMapper.selectById(item.accountId());
            if (account == null) {
                rows.add(new AiCompanyApplyResponse.Row(item.accountId(), null, companyName,
                        "FAILED", "账户不存在或已从档案移除"));
                continue;
            }
            try {
                Company company = companyMapper.selectOne(new LambdaQueryWrapper<Company>()
                        .eq(Company::getName, companyName));
                boolean isNew = company == null;
                if (isNew) {
                    company = findCreated(companyName);
                }
                assignAccount(account.getId(), company.getId());
                created += isNew ? 1 : 0;
                assigned += 1;
                rows.add(new AiCompanyApplyResponse.Row(account.getId(), account.getAccountName(),
                        companyName, isNew ? "CREATED" : "ASSIGNED", null));
            } catch (BusinessException e) {
                rows.add(new AiCompanyApplyResponse.Row(account.getId(), account.getAccountName(),
                        companyName, "FAILED", e.getMessage()));
            }
        }
        return new AiCompanyApplyResponse(rows, created, assigned);
    }

    /** createCompany 的重名校验在批量语义下改为「复用已有」，绕行直接建档保持编码分配一致。 */
    private Company findCreated(String companyName) {
        String code = nextCompanyCode();
        Company company = new Company();
        company.setCode(code);
        company.setName(companyName);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
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
