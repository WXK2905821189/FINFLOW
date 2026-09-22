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
    private KingdeeVoucherGateway gateway;
    private BankAccountDimensionResolver bankDimensionResolver;
    private AiGlVoucherAssembler assembler;

    @BeforeEach
    void setUp() {
        gateway = mock(KingdeeVoucherGateway.class);
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeAccountRef("1001", "库存现金", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1002", "银行存款", "ZDY0001"),
                new KingdeeVoucherGateway.KingdeeAccountRef("1122.01", "外部往来", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("2241.99", "其他应付款-其他", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("2232", "应付股利", null)));
        props = new KingdeeProperties();
        bankDimensionResolver = mock(BankAccountDimensionResolver.class);
        when(bankDimensionResolver.resolve(any())).thenReturn(BANK_ACCOUNT);
        assembler = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, props), bankDimensionResolver, props);
    }

    /** 关掉兜底科目的组装器（props.fallbackAccount = 空 ⇒ 维持原有拦截语义）。 */
    private AiGlVoucherAssembler assemblerWithoutFallback() {
        KingdeeProperties noFallback = new KingdeeProperties();
        noFallback.setFallbackAccount("");
        return new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, noFallback), bankDimensionResolver, noFallback);
    }

    private static AiGlVoucherAssembler.EntryInput entry(String code, String name,
                                                         String direction, String amount) {
        return new AiGlVoucherAssembler.EntryInput("支付货款", code, name, direction,
                new BigDecimal(amount), null);
    }

    private static AiGlVoucherAssembler.EntryInput entryWithConfidence(String code, String name,
                                                                       String direction, String amount,
                                                                       Double confidence) {
        return new AiGlVoucherAssembler.EntryInput("支付货款", code, name, direction,
                new BigDecimal(amount), confidence);
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
    void ambiguousAccountNameIsRejectedWithCandidatesWhenNoFallback() {
        KingdeeVoucherGateway gateway = mock(KingdeeVoucherGateway.class);
        when(gateway.queryAccountCatalog()).thenReturn(List.of(
                new KingdeeVoucherGateway.KingdeeAccountRef("1001", "库存现金", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1122.01", "外部往来", null),
                new KingdeeVoucherGateway.KingdeeAccountRef("1123.01", "外部往来", null)));
        KingdeeProperties noFallback = new KingdeeProperties();
        noFallback.setFallbackAccount("");
        AiGlVoucherAssembler ambiguous = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, noFallback), bankDimensionResolver, noFallback);

        BusinessException ex = assertThrows(BusinessException.class, () -> ambiguous.assemble(List.of(
                entry("1001", "库存现金", "DEBIT", "100.00"),
                entry(null, "外部往来", "CREDIT", "100.00")), statement()));
        assertTrue(ex.getMessage().contains("对应多个科目"));
        assertTrue(ex.getMessage().contains("1122.01"));
        assertTrue(ex.getMessage().contains("1123.01"));
    }

    @Test
    void missingSubjectCodeIsRejectedBeforeBuildingWhenNoFallback() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assemblerWithoutFallback().assemble(List.of(
                        entry(null, "账套里没有的名称", "DEBIT", "100.00"),
                        entry("1002", "银行存款", "CREDIT", "100.00")), statement()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("第 1 行"));
        assertTrue(ex.getMessage().contains("没有精确匹配"));
    }

    @Test
    void nameMismatchIsAcceptedWithAccountBookName() {
        // 2026-09-21 改判：账套 2232 = 应付股利，AI 写「应付账款」时不再拒绝 ——
        // 金蝶报文只发科目编码（FNumber），名称不参与推送，本地拦截属于过度拦截（用户实际被它挡住）。
        // 现改为「以账套名称为准」并留 warning 供人工复核。
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entry("2232", "应付账款", "DEBIT", "100.00"),
                entry("1002", "银行存款", "CREDIT", "100.00")), statement());

        assertEquals("2232", assembled.debitLines().get(0).account(), "科目编码保持 AI 给的值");
        assertEquals("应付股利", assembled.debitLines().get(0).accountName(), "名称以账套为准");
        assertTrue(assembled.warnings().stream().anyMatch(w -> w.contains("已按账套名称处理")),
                "名称不一致必须留痕：" + assembled.warnings());
    }

    @Test
    void unknownSubjectCodeFallsBackToConfiguredAccount() {
        // 科目在账套不存在 → 换成待确认兜底科目（默认 2241.99），保证凭证仍能推到金蝶
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entry("9999", "不存在的科目", "DEBIT", "100.00"),
                entry("1001", "库存现金", "CREDIT", "100.00")), statement());

        assertEquals("2241.99", assembled.debitLines().get(0).account(), "不可用科目替换为兜底科目");
        assertTrue(assembled.warnings().stream().anyMatch(w -> w.contains("待确认科目")),
                "替换必须留痕：" + assembled.warnings());
    }

    @Test
    void lowConfidenceSubjectFallsBackToConfiguredAccount() {
        AiGlVoucherAssembler.Assembled assembled = assembler.assemble(List.of(
                entryWithConfidence("1001", "库存现金", "DEBIT", "100.00", 0.30),
                entryWithConfidence("1002", "银行存款", "CREDIT", "100.00", 0.95)), statement());

        assertEquals("2241.99", assembled.debitLines().get(0).account(), "低置信度科目走兜底");
        assertEquals("1002", assembled.creditLines().get(0).account(), "高置信度科目保持不变");
        assertTrue(assembled.warnings().stream().anyMatch(w -> w.contains("置信度")), assembled.warnings().toString());
    }

    @Test
    void lowConfidenceWithoutUsableFallbackKeepsOriginalSubject() {
        KingdeeProperties noFallback = new KingdeeProperties();
        noFallback.setFallbackAccount("");
        AiGlVoucherAssembler bare = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, noFallback), bankDimensionResolver, noFallback);

        AiGlVoucherAssembler.Assembled assembled = bare.assemble(List.of(
                entryWithConfidence("1001", "库存现金", "DEBIT", "100.00", 0.30),
                entryWithConfidence("1002", "银行存款", "CREDIT", "100.00", 0.95)), statement());

        assertEquals("1001", assembled.debitLines().get(0).account(), "无兜底科目时按原科目推送（优先能推上去）");
        assertTrue(assembled.warnings().stream().anyMatch(w -> w.contains("未配置可用兜底科目")),
                assembled.warnings().toString());
    }

    @Test
    void fallbackAccountItselfMissingFromCatalogStillRejects() {
        // 兜底科目在账套里也不存在 ⇒ 维持原拦截，不把必然被金蝶拒的编码发出去
        KingdeeProperties bad = new KingdeeProperties();
        bad.setFallbackAccount("9999");
        AiGlVoucherAssembler withBadFallback = new AiGlVoucherAssembler(
                new KingdeeAccountCatalogService(gateway, bad), bankDimensionResolver, bad);

        BusinessException ex = assertThrows(BusinessException.class, () -> withBadFallback.assemble(List.of(
                entry("8888", "不存在的科目", "DEBIT", "100.00"),
                entry("1001", "库存现金", "CREDIT", "100.00")), statement()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
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
