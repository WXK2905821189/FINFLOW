package com.finance.system.bank;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.voucherrule.KingdeeOrgResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FINFLOW 银行账户 → 金蝶银行账号档案（CN_BANKACNT）映射服务（2026-09-21）。
 *
 * <p><b>为什么需要它</b>：总账凭证里挂「银行账号」必录核算维度的科目（账套维度类型
 * {@code ZDY0001}，维度类型=基础资料，实测 1002 银行存款挂该维度）必须带 CN_BANKACNT
 * 档案编码。一家公司有多个银行账户，全局一个默认账户只是联调期占位做法——映射粒度
 * 必须是「账户级」，且维度值应跟随**该笔流水所属的我方账户**。</p>
 *
 * <p><b>自动匹配规则</b>（只读预演与写回共用同一套判定）：</p>
 * <ol>
 *   <li>按账户 {@code account_number} 与档案 {@code FNumber} 规范化后精确匹配
 *   （实测账套 142 个档案中 102 个 FNumber 即账号本体）；</li>
 *   <li>命中多个（同一账号在多个组织各有一个档案）→ 用「公司名 → 金蝶组织」限定消歧；</li>
 *   <li>仍未消歧 → 标 {@code AMBIGUOUS} 并列出候选，由人工指定；</li>
 *   <li>零命中（虚拟账户：支付宝邮箱 / 薪福通 / 分贝通 / 携程商旅等）→ 标 {@code UNMATCHED}，
 *   并把**该公司所属组织下的全部档案**作为候选给出，人工挑一个即可。</li>
 * </ol>
 *
 * <p><b>写入口径</b>：只有「唯一命中」才自动写回；多义与零命中一律留给人工，
 * 不做模糊猜测（错映射会静默记错账户）。制证模式为 {@code MANUAL}（纯人工制证）的账户
 * 不进金蝶链路，标记为 {@code NOT_REQUIRED} 并跳过。</p>
 */
@Service
public class KingdeeAccountMappingService {

    private static final Logger log = LoggerFactory.getLogger(KingdeeAccountMappingService.class);

    /** 行状态字面量（前端据此渲染徽章与操作）。 */
    public static final String ST_MAPPED = "MAPPED";
    public static final String ST_AUTO_MATCHABLE = "AUTO_MATCHABLE";
    public static final String ST_AMBIGUOUS = "AMBIGUOUS";
    public static final String ST_UNMATCHED = "UNMATCHED";
    public static final String ST_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String ST_CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE";

    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final KingdeeVoucherGateway gateway;
    private final KingdeeOrgResolver orgResolver;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;

    public KingdeeAccountMappingService(BankAccountMapper bankAccountMapper,
                                        CompanyMapper companyMapper,
                                        KingdeeVoucherGateway gateway,
                                        KingdeeOrgResolver orgResolver,
                                        CompanyScopeService companyScope,
                                        RbacService rbacService) {
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.gateway = gateway;
        this.orgResolver = orgResolver;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
    }

    /**
     * 单个账户的映射状态行。
     *
     * @param status     MAPPED / AUTO_MATCHABLE / AMBIGUOUS / UNMATCHED / NOT_REQUIRED / CATALOG_UNAVAILABLE
     * @param candidates AMBIGUOUS（同账号跨组织重名）或 UNMATCHED（本公司组织下的全部档案）时的候选，
     *                   供人工挑；其余状态为空
     */
    public record MappingRow(Long accountId,
                             String bankCode,
                             String accountName,
                             String maskedAccountNumber,
                             Long companyId,
                             String companyName,
                             String accountingMode,
                             String kingdeeAccountNumber,
                             String status,
                             List<String> candidates) {
    }

    /**
     * 预演结果。
     *
     * @param gatewayMode      当前金蝶网关模式（MOCK / UNAVAILABLE / REAL）
     * @param catalogAvailable 是否成功拉到账套档案（false 时所有行不可判定）
     */
    public record MappingPreview(List<MappingRow> rows,
                                 String gatewayMode,
                                 boolean catalogAvailable,
                                 String note) {
    }

    /** 一键匹配的写回统计。 */
    public record MatchResult(int scanned, int matched, int ambiguous, int unmatched, int skipped) {
    }

    /** 只读预演：不写任何数据，供映射页展示与人工决策。 */
    public MappingPreview preview(Long userId) {
        List<BankAccount> accounts = visibleAccounts(userId);
        String mode = gatewayMode();
        List<KingdeeVoucherGateway.KingdeeBankAccountRef> catalog;
        boolean catalogAvailable;
        try {
            catalog = gateway.queryBankAccountCatalog();
            catalogAvailable = catalog != null && !catalog.isEmpty();
        } catch (Exception e) {
            log.warn("金蝶银行账号档案拉取失败：{}", e.getMessage());
            catalog = List.of();
            catalogAvailable = false;
        }
        if (!catalogAvailable) {
            catalog = List.of();
        }
        Map<Long, Company> companies = companiesOf(accounts);
        List<MappingRow> rows = new ArrayList<>(accounts.size());
        for (BankAccount account : accounts) {
            rows.add(toRow(account, companies.get(account.getCompanyId()), catalog, catalogAvailable));
        }
        String note = catalogAvailable ? null
                : ("MOCK".equals(mode)
                    ? "当前为模拟网关，档案为内置样例；请在环境变量注入真实凭据并开启 Real 网关后再做正式匹配"
                    : "账套银行账号档案不可用，请先确认金蝶连接（可用「连接测试」按钮自检）");
        return new MappingPreview(rows, mode, catalogAvailable, note);
    }

    /**
     * 一键自动匹配：只把「唯一命中」写回账户档案；多义/零命中留给人工。
     */
    public MatchResult autoMatch(Long userId) {
        List<BankAccount> accounts = visibleAccounts(userId);
        List<KingdeeVoucherGateway.KingdeeBankAccountRef> catalog = gateway.queryBankAccountCatalog();
        if (catalog == null || catalog.isEmpty()) {
            throw new BusinessException(400, "金蝶银行账号档案不可用（当前网关为 " + gatewayMode()
                    + "），无法自动匹配；请先确认金蝶连接后再试");
        }
        Map<Long, Company> companies = companiesOf(accounts);
        int matched = 0;
        int ambiguous = 0;
        int unmatched = 0;
        int skipped = 0;
        for (BankAccount account : accounts) {
            if (!requiresMapping(account) || hasText(account.getKingdeeAccountNumber())) {
                skipped++;
                continue;
            }
            MatchOutcome outcome = match(account, companies.get(account.getCompanyId()), catalog);
            if (outcome.isUnique()) {
                bankAccountMapper.update(null, new LambdaUpdateWrapper<BankAccount>()
                        .eq(BankAccount::getId, account.getId())
                        .set(BankAccount::getKingdeeAccountNumber, outcome.unique().number()));
                matched++;
            } else if (outcome.isAmbiguous()) {
                ambiguous++;
            } else {
                unmatched++;
            }
        }
        log.info("金蝶账户自动匹配：扫描 {} / 自动写入 {} / 多义 {} / 未命中 {} / 跳过 {}",
                accounts.size(), matched, ambiguous, unmatched, skipped);
        return new MatchResult(accounts.size(), matched, ambiguous, unmatched, skipped);
    }

    /**
     * 人工指定（或清除）某账户的金蝶档案编码。
     *
     * @param kingdeeNumber 金蝶 CN_BANKACNT 编码；空白表示清除映射
     */
    public MappingRow setMapping(Long userId, Long accountId, String kingdeeNumber) {
        BankAccount account = accountById(userId, accountId);
        String value = hasText(kingdeeNumber) ? kingdeeNumber.trim() : null;
        bankAccountMapper.update(null, new LambdaUpdateWrapper<BankAccount>()
                .eq(BankAccount::getId, account.getId())
                .set(BankAccount::getKingdeeAccountNumber, value));
        account.setKingdeeAccountNumber(value);
        List<KingdeeVoucherGateway.KingdeeBankAccountRef> catalog = gateway.queryBankAccountCatalog();
        return toRow(account, companyMapper.selectById(account.getCompanyId()),
                catalog == null ? List.of() : catalog, catalog != null && !catalog.isEmpty());
    }

    /**
     * 制证时取该账户的核算维度值（即金蝶档案编码）。
     *
     * <p>阻断口径（2026-09-21 决策）：KINGDEE_AUTO 账户未映射时抛 400 并指明去哪配——
     * 凭证错账代价大，宁可先补映射。MANUAL 账户本就不进金蝶链路，返回 null 由调用方跳过。</p>
     */
    public String dimensionValueFor(BankAccount account) {
        if (account == null || !requiresMapping(account)) {
            return null;
        }
        if (hasText(account.getKingdeeAccountNumber())) {
            return account.getKingdeeAccountNumber().trim();
        }
        throw new BusinessException(400, "银行账户「" + account.getAccountName() + "（尾号 "
                + last4(account.getAccountNumber()) + "）」尚未映射金蝶银行账号档案编码，无法制证；"
                + "请在「银行账户」页点击「匹配金蝶账户」由系统自动匹配，或在行内手动指定金蝶账户后重试");
    }

    /** KINGDEE_AUTO 才需要映射；null 按默认 KINGDEE_AUTO 处理（与 V31 列默认值一致）。 */
    public static boolean requiresMapping(BankAccount account) {
        String mode = account.getAccountingMode();
        return mode == null || !"MANUAL".equalsIgnoreCase(mode.trim());
    }

    private MappingRow toRow(BankAccount account,
                             Company company,
                             List<KingdeeVoucherGateway.KingdeeBankAccountRef> catalog,
                             boolean catalogAvailable) {
        String companyName = company == null ? null : company.getName();
        String mapping = account.getKingdeeAccountNumber();
        String status;
        List<String> candidates = List.of();
        if (!requiresMapping(account)) {
            status = ST_NOT_REQUIRED;
        } else if (hasText(mapping)) {
            status = ST_MAPPED;
        } else if (!catalogAvailable) {
            status = ST_CATALOG_UNAVAILABLE;
        } else {
            MatchOutcome outcome = match(account, company, catalog);
            if (outcome.isUnique()) {
                status = ST_AUTO_MATCHABLE;
                candidates = List.of(describe(outcome.unique()));
            } else if (outcome.isAmbiguous()) {
                status = ST_AMBIGUOUS;
                candidates = outcome.candidates().stream().map(this::describe).toList();
            } else {
                status = ST_UNMATCHED;
                candidates = outcome.candidates().stream().map(this::describe).toList();
            }
        }
        return new MappingRow(account.getId(), account.getBankCode(), account.getAccountName(),
                "****" + last4(account.getAccountNumber()), account.getCompanyId(), companyName,
                account.getAccountingMode() == null ? "KINGDEE_AUTO" : account.getAccountingMode(),
                mapping, status, candidates);
    }

    /**
     * 匹配判定结果。
     *
     * <p>{@code ambiguousHits} 与 {@code candidates} 必须分开：前者的语义是「同账号在多组织命中，
     * 无法确定是哪一个」，后者是「零命中时给人工的参考候选池」。两者混用会把「未命中」误报成
     * 「多义」（真实缺陷，由 KingdeeAccountMappingServiceTest 抓出）。</p>
     *
     * @param unique         唯一命中（可自动写回），否则 null
     * @param ambiguousHits  同账号在多组织命中且组织消歧失败
     * @param candidates     给人工的候选清单（多义时为命中集，零命中时为本组织档案池）
     */
    private record MatchOutcome(KingdeeVoucherGateway.KingdeeBankAccountRef unique,
                                List<KingdeeVoucherGateway.KingdeeBankAccountRef> ambiguousHits,
                                List<KingdeeVoucherGateway.KingdeeBankAccountRef> candidates) {
        static MatchOutcome unique(KingdeeVoucherGateway.KingdeeBankAccountRef ref) {
            return new MatchOutcome(ref, List.of(), List.of());
        }

        static MatchOutcome ambiguous(List<KingdeeVoucherGateway.KingdeeBankAccountRef> hits) {
            return new MatchOutcome(null, List.copyOf(hits), List.copyOf(hits));
        }

        static MatchOutcome unmatched(List<KingdeeVoucherGateway.KingdeeBankAccountRef> candidates) {
            return new MatchOutcome(null, List.of(), List.copyOf(candidates));
        }

        boolean isUnique() {
            return unique != null;
        }

        boolean isAmbiguous() {
            return unique == null && !ambiguousHits.isEmpty();
        }
    }

    private MatchOutcome match(BankAccount account, Company company,
                               List<KingdeeVoucherGateway.KingdeeBankAccountRef> catalog) {
        String orgCode = orgResolver.resolveOrgCode(company == null ? null : company.getName());
        String target = normalize(account.getAccountNumber());
        List<KingdeeVoucherGateway.KingdeeBankAccountRef> hits = target.isEmpty() ? List.of()
                : catalog.stream().filter(r -> normalize(r.number()).equals(target)).toList();
        if (hits.size() == 1) {
            return MatchOutcome.unique(hits.get(0));
        }
        if (hits.size() > 1) {
            // 同账号跨组织重名 → 用「公司 → 金蝶组织」限定
            if (orgCode != null) {
                List<KingdeeVoucherGateway.KingdeeBankAccountRef> scoped = hits.stream()
                        .filter(r -> orgCode.equals(r.orgNumber())).toList();
                if (scoped.size() == 1) {
                    return MatchOutcome.unique(scoped.get(0));
                }
            }
            return MatchOutcome.ambiguous(hits);
        }
        // 零命中：虚拟账户（支付宝/薪福通等）无账号可匹配，给出该公司组织下的全部档案供人工挑
        List<KingdeeVoucherGateway.KingdeeBankAccountRef> orgPool = orgCode == null ? List.of()
                : catalog.stream().filter(r -> orgCode.equals(r.orgNumber())).toList();
        return MatchOutcome.unmatched(orgPool);
    }

    private List<BankAccount> visibleAccounts(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        boolean crossCompany = rbacService.permissionCodesForUser(userId)
                .contains("bankdata:cross-company:view");
        if (crossCompany) {
            List<Long> companyIds = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                            .eq(Company::getStatus, "ACTIVE"))
                    .stream().map(Company::getId).toList();
            if (companyIds.isEmpty()) {
                return List.of();
            }
            return bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                    .in(BankAccount::getCompanyId, companyIds)
                    .orderByAsc(BankAccount::getCompanyId).orderByAsc(BankAccount::getId));
        }
        return bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getCompanyId, companyId)
                .orderByAsc(BankAccount::getId));
    }

    private BankAccount accountById(Long userId, Long accountId) {
        long companyId = companyScope.companyIdForUser(userId);
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, accountId)
                .eq(BankAccount::getCompanyId, companyId));
        if (account == null) {
            throw new BusinessException(404, "银行账户不存在");
        }
        return account;
    }

    private Map<Long, Company> companiesOf(List<BankAccount> accounts) {
        if (accounts.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = accounts.stream().map(BankAccount::getCompanyId).distinct().toList();
        return companyMapper.selectList(new LambdaQueryWrapper<Company>().in(Company::getId, ids))
                .stream().collect(Collectors.toMap(Company::getId, Function.identity(), (a, b) -> a));
    }

    private String gatewayMode() {
        try {
            return gateway.ping().mode();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String describe(KingdeeVoucherGateway.KingdeeBankAccountRef ref) {
        return ref.number() + " " + (ref.name() == null ? "" : ref.name())
                + (ref.orgNumber() == null ? "" : "（组织 " + ref.orgNumber() + "）");
    }

    /** 账号规范化：去空白与连字符（银行流水/档案两侧格式常带分隔符）。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-]", "").toUpperCase();
    }

    private static String last4(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "----";
        }
        String trimmed = accountNumber.trim();
        return trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
