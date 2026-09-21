package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.BankAccountDimensionResolver;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 分录 → GL 草稿组装：三道闸（科目存在性 / 名称一致 / 银行类科目维度）+ 平衡与方向校验。
 * 这是「一键 AI 制证」切到总账落点后、报文生成前的最后一道业务闸门。
 *
 * <p>维度值自 2026-09-21 起由 {@link BankAccountDimensionResolver} 按**流水所属账户**解析
 * （账户级映射），组装器不再读全局默认账户配置——本测试用 mock 解析器覆盖该接缝。</p>
 */
class AiGlVoucherAssemblerTest {

    private static final String BANK_ACCOUNT = "11050160520009100036";

    private KingdeeProperties props;
    private BankAccountDimensionResolver bankDimensionResolver;
    private AiGlVoucherAssembler assembler;

    @BeforeEach
    void setUp() {
        KingdeeVoucherGateway gateway = mock(KingdeeVoucherGateway.class);
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeAccountRef("1001", "库存现金", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1002", "银行存款", "ZDY0001"),
                new KingdeeVoucherGateway.KingdeeAccountRef("1122.01", "外部往来", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("2232", "应付股利", null)));
        props = new KingdeeProperties();
        bankDimensionResolver = mock(BankAccountDimensionResolver.class);
        when(bankDimensionResolver.resolve(any())).thenReturn(BANK_ACCOUNT);
        assembler = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, props), bankDimensionResolver);
    }

    private static AiGlVoucherAssembler.EntryInput entry(String code, String name,
                                                         String direction, String amount) {
        return new AiGlVoucherAssembler.EntryInput("支付货款", code, name, direction,
                new BigDecimal(amount));
    }

    private static StatementRecord statement() {
        StatementRecord record = new StatementRecord();
        record.setId(1L);
        record.setStatementNo("SGD001");
        return record;
    }

    @Test
    void incomeVoucherGetsBankDimensionOnBankLineOnly() {
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "177.46"),
                entry("1122.01", "外部往来", "CREDIT", "177.46")), statement());

        assertEquals(1, assembled.debitLines().size());
        assertEquals(1, assembled.creditLines().size());
        KingdeeVoucherEntryDraft bankLine = assembled.debitLines().get(0);
        assertEquals("BANK_ACCOUNT", bankLine.dimension());
        assertEquals(BANK_ACCOUNT, bankLine.dimensionValue(),
                "维度值取该流水所属账户的金蝶档案编码（账户级映射）");
        assertNull(assembled.creditLines().get(0).dimensionValue(), "非银行科目不注入维度");
        assertEquals(new BigDecimal("177.46"), bankLine.amount());
        assertTrue(assembled.warnings().isEmpty());
    }

    @Test
    void missingSubjectCodeIsResolvedFromUniqueAccountName() {
        // AI 提示词允许「编码不确定就给空字符串」；GL 落点必须有编码 → 按名称在账套科目表反查
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entry(null, "银行存款", "DEBIT", "100.00"),
                entry(null, "外部往来", "CREDIT", "100.00")), statement());

        assertEquals("1002", assembled.debitLines().get(0).account());
        assertEquals("1122.01", assembled.creditLines().get(0).account());
        assertEquals(BANK_ACCOUNT, assembled.debitLines().get(0).dimensionValue(),
                "按名称解析出 1002 后，同样要注入银行账号维度");
    }

    @Test
    void ambiguousAccountNameIsRejectedWithCandidates() {
        KingdeeVoucherGateway gateway = mock(KingdeeVoucherGateway.class);
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeAccountRef("1001", "库存现金", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1122.01", "外部往来", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1123.01", "外部往来", null)));
        AiGlVoucherAssembler ambiguous = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, props), bankDimensionResolver);

        BusinessException ex = assertThrows(BusinessException.class, () -> ambiguous.assemble(List.of(
                entry("1001", "库存现金", "DEBIT", "100.00"),
                entry(null, "外部往来", "CREDIT", "100.00")), statement()));
        assertTrue(ex.getMessage().contains("对应多个科目"));
        assertTrue(ex.getMessage().contains("1122.01"));
        assertTrue(ex.getMessage().contains("1123.01"));
    }

    @Test
    void missingSubjectCodeIsRejectedBeforeBuilding() {
        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry(null, "账套里没有的名称", "DEBIT", "100.00"),
                entry("1002", "银行存款", "CREDIT", "100.00")), statement()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("第 1 行"));
        assertTrue(ex.getMessage().contains("没有精确匹配"));
    }

    @Test
    void nameMismatchWithAccountSetIsRejected() {
        // 账套 2232 = 应付股利；AI 建议写「应付账款」→ 拒绝，防静默记错账
        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("2232", "应付账款", "DEBIT", "100.00"),
                entry("1002", "银行存款", "CREDIT", "100.00")), statement()));
        assertTrue(ex.getMessage().contains("应付股利"));
    }

    @Test
    void missingBankAccountMappingIsRejectedWithGuidance() {
        // 账户级映射取不到值（流水无账户归属且无兜底配置）→ 拒绝并指明处置方向
        when(bankDimensionResolver.resolve(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "100.00"),
                entry("1122.01", "外部往来", "CREDIT", "100.00")), statement()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("银行账号"));
        assertTrue(ex.getMessage().contains("未关联我方银行账户"));
    }

    @Test
    void unbalancedEntriesAreRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "100.00"),
                entry("1122.01", "外部往来", "CREDIT", "99.00")), statement()));
        assertTrue(ex.getMessage().contains("借贷不平衡"));
    }

    @Test
    void singleSidedEntriesAreRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "100.00")), statement()));
        assertTrue(ex.getMessage().contains("必须同时包含借方与贷方"));
    }

    @Test
    void emptyEntriesAreRejectedWithDraftHint() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assembler.assemble(List.of(), statement()));
        assertTrue(ex.getMessage().contains("尚未生成凭证分录"));
    }

    @Test
    void invalidAmountOrDirectionIsRejected() {
        BusinessException zero = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "0.00"),
                entry("1122.01", "外部往来", "CREDIT", "0.00")), statement()));
        assertTrue(zero.getMessage().contains("金额无效"));

        BusinessException badDirection = assertThrows(BusinessException.class, () -> assembler.assemble(List.of(
                entry("1002", "银行存款", "BOTH", "1.00"),
                entry("1122.01", "外部往来", "CREDIT", "1.00")), statement()));
        assertTrue(badDirection.getMessage().contains("借贷方向无效"));
    }

    @Test
    void amountsAreScaledToTwoDecimals() {
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entry("1002", "银行存款", "DEBIT", "177.456"),
                entry("1122.01", "外部往来", "CREDIT", "177.456")), statement());
        assertEquals(new BigDecimal("177.46"), assembled.debitLines().get(0).amount());
    }
}
