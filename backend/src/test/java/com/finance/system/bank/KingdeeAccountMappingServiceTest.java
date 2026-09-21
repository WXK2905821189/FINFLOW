package com.finance.system.bank;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.voucherrule.KingdeeOrgResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 银行账户 → 金蝶银行账号档案映射（2026-09-21）。
 *
 * <p>覆盖自动匹配的四种判定（唯一命中 / 组织消歧 / 多义 / 零命中）、已映射与 MANUAL 账户的
 * 短路，以及制证取值时的 fail-closed 阻断口径。</p>
 */
class KingdeeAccountMappingServiceTest {

    private static final String SNOW_ACCOUNT = "898902383810809";
    private static final String SNOW_ORG = "400";
    private static final String TUCHONG_ORG = "410";

    private BankAccountMapper bankAccountMapper;
    private CompanyMapper companyMapper;
    private KingdeeVoucherGateway gateway;
    private KingdeeOrgResolver orgResolver;
    private KingdeeAccountMappingService service;

    @BeforeEach
    void setUp() {
        bankAccountMapper = mock(BankAccountMapper.class);
        companyMapper = mock(CompanyMapper.class);
        gateway = mock(KingdeeVoucherGateway.class);
        orgResolver = mock(KingdeeOrgResolver.class);
        CompanyScopeService companyScope = mock(CompanyScopeService.class);
        RbacService rbacService = mock(RbacService.class);
        when(companyScope.companyIdForUser(1L)).thenReturn(7L);
        when(rbacService.permissionCodesForUser(1L)).thenReturn(List.<String>of());
        service = new KingdeeAccountMappingService(bankAccountMapper, companyMapper, gateway,
                orgResolver, companyScope, rbacService);
    }

    private void stubAccounts(BankAccount... accounts) {
        when(bankAccountMapper.selectList(any())).thenReturn(List.of(accounts));
    }

    private void stubCompany(Long id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        when(companyMapper.selectById(id)).thenReturn(company);
        when(companyMapper.selectList(any())).thenReturn(List.of(company));
    }

    private static BankAccount account(Long id, Long companyId, String number, String kingdeeNumber) {
        BankAccount account = new BankAccount();
        account.setId(id);
        account.setCompanyId(companyId);
        account.setBankCode("CMB");
        account.setAccountName("北京雪云锐创科技有限公司");
        account.setAccountNumber(number);
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("0.00"));
        account.setStatus("ACTIVE");
        account.setAccountingMode("KINGDEE_AUTO");
        account.setKingdeeAccountNumber(kingdeeNumber);
        return account;
    }

    @Test
    void uniqueAccountNumberHitIsAutoMatchable() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, null));
        stubCompany(7L, "北京雪云锐创科技有限公司");
        when(orgResolver.resolveOrgCode("北京雪云锐创科技有限公司")).thenReturn(SNOW_ORG);
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "北京雪云锐创科技有限公司", SNOW_ORG)));

        KingdeeAccountMappingService.MappingRow row = service.preview(1L).rows().get(0);

        assertEquals(KingdeeAccountMappingService.ST_AUTO_MATCHABLE, row.status());
        assertTrue(row.candidates().get(0).contains(SNOW_ACCOUNT));
        assertNull(row.kingdeeAccountNumber(), "预演只读，不写回");
    }

    @Test
    void duplicateNumberAcrossOrgsIsDisambiguatedByCompanyOrg() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, null));
        stubCompany(7L, "北京雪云锐创科技有限公司");
        when(orgResolver.resolveOrgCode("北京雪云锐创科技有限公司")).thenReturn(SNOW_ORG);
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "首体科技金融支行", TUCHONG_ORG),
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "北京雪云锐创科技有限公司", SNOW_ORG)));

        KingdeeAccountMappingService.MappingRow row = service.preview(1L).rows().get(0);

        assertEquals(KingdeeAccountMappingService.ST_AUTO_MATCHABLE, row.status());
        assertTrue(row.candidates().get(0).contains("组织 " + SNOW_ORG), "应限定到本公司组织");
    }

    @Test
    void duplicateNumberWithoutOrgResolutionStaysAmbiguous() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, null));
        stubCompany(7L, "未识别主体");
        when(orgResolver.resolveOrgCode("未识别主体")).thenReturn(null);
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "A", SNOW_ORG),
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "B", TUCHONG_ORG)));

        KingdeeAccountMappingService.MappingRow row = service.preview(1L).rows().get(0);

        assertEquals(KingdeeAccountMappingService.ST_AMBIGUOUS, row.status());
        assertEquals(2, row.candidates().size());
    }

    @Test
    void virtualAccountWithoutNumberHitFallsBackToOrgCandidates() {
        // 虚拟账户（支付宝/薪福通等）账号匹配不上档案编码 → 列本公司组织下全部档案供人工挑
        stubAccounts(account(11L, 7L, "admin@xiaopiu.x", null));
        stubCompany(7L, "北京雪云锐创科技有限公司");
        when(orgResolver.resolveOrgCode("北京雪云锐创科技有限公司")).thenReturn(SNOW_ORG);
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeBankAccountRef("admin@xiaopiu.com", "支付宝", SNOW_ORG),
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "雪云招行", SNOW_ORG),
                new KingdeeVoucherGateway.KingdeeBankAccountRef("110922659010201", "图虫招行", TUCHONG_ORG)));

        KingdeeAccountMappingService.MappingRow row = service.preview(1L).rows().get(0);

        assertEquals(KingdeeAccountMappingService.ST_UNMATCHED, row.status());
        assertEquals(2, row.candidates().size(), "只给本公司组织的档案，不跨组织");
        assertTrue(row.candidates().stream().noneMatch(c -> c.contains(TUCHONG_ORG)));
    }

    @Test
    void alreadyMappedAndManualAccountsAreShortCircuited() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, "11050160520009100036"),
                manualAccount(12L, 7L, SNOW_ACCOUNT));
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of());

        KingdeeAccountMappingService.MappingPreview preview = service.preview(1L);

        assertEquals(KingdeeAccountMappingService.ST_MAPPED, preview.rows().get(0).status());
        assertEquals(KingdeeAccountMappingService.ST_NOT_REQUIRED, preview.rows().get(1).status(),
                "MANUAL（纯人工制证）账户不需要金蝶映射");
        assertEquals("11050160520009100036", preview.rows().get(0).kingdeeAccountNumber());
    }

    @Test
    void catalogUnavailableIsReportedPerRowWithNote() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, null));
        stubCompany(7L, "北京雪云锐创科技有限公司");
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of());

        KingdeeAccountMappingService.MappingPreview preview = service.preview(1L);

        assertEquals(KingdeeAccountMappingService.ST_CATALOG_UNAVAILABLE, preview.rows().get(0).status());
        assertTrue(preview.note() != null && !preview.note().isBlank(), "档案不可用必须给出可执行提示");
    }

    @Test
    void autoMatchWritesOnlyUniqueHits() {
        BankAccount unique = account(11L, 7L, SNOW_ACCOUNT, null);
        BankAccount ambiguous = account(12L, 7L, "6222000000000", null);
        BankAccount mapped = account(13L, 7L, "6222999999999", "11050160520009100036");
        stubAccounts(unique, ambiguous, mapped);
        stubCompany(7L, "北京雪云锐创科技有限公司");
        when(orgResolver.resolveOrgCode("北京雪云锐创科技有限公司")).thenReturn(SNOW_ORG);
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeBankAccountRef(SNOW_ACCOUNT, "雪云招行", SNOW_ORG)));

        KingdeeAccountMappingService.MatchResult result = service.autoMatch(1L);

        assertEquals(3, result.scanned());
        assertEquals(1, result.matched());
        assertEquals(1, result.unmatched(), "6222… 零命中 → 留给人工");
        assertEquals(1, result.skipped(), "已映射账户跳过，不覆盖人工选择");
        verify(bankAccountMapper, times(1)).update(isNull(), any());
    }

    @Test
    void autoMatchRejectsWhenCatalogUnavailable() {
        stubAccounts(account(11L, 7L, SNOW_ACCOUNT, null));
        when(gateway.queryBankAccountCatalog()).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.autoMatch(1L));
        assertTrue(ex.getMessage().contains("档案不可用"));
        verify(bankAccountMapper, never()).update(isNull(), any());
    }

    @Test
    void dimensionValueRequiresMappingForAutoAccounts() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.dimensionValueFor(account(11L, 7L, SNOW_ACCOUNT, null)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("匹配金蝶账户"), "提示要指明处置入口");

        assertEquals("11050160520009100036",
                service.dimensionValueFor(account(11L, 7L, SNOW_ACCOUNT, "11050160520009100036")));
        assertNull(service.dimensionValueFor(manualAccount(12L, 7L, SNOW_ACCOUNT)),
                "MANUAL 账户不进金蝶链路，无需维度值");
    }

    @Test
    void accountNumberNormalizationIgnoresSeparatorsAndCase() {
        assertEquals("898902383810809", KingdeeAccountMappingService.normalize(" 8989 0238-3810 809 "));
        assertEquals("ADMIN@XIAOPIU.COM", KingdeeAccountMappingService.normalize("admin@xiaopiu.com"));
        assertEquals("", KingdeeAccountMappingService.normalize(null));
    }

    private static BankAccount manualAccount(Long id, Long companyId, String number) {
        BankAccount account = account(id, companyId, number, null);
        account.setAccountingMode("MANUAL");
        return account;
    }
}
