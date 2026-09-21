package com.finance.system.statement.voucherrule;

import com.finance.system.ai.dto.VoucherEntry;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.BankAccountDimensionResolver;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 制证分录 → 总账凭证草稿组装（2026-09-21 方案 B）。
 *
 * <p>把「一键 AI 制证」产出的结构化建议（{@code statement_record.ai_suggestion_json} 里的
 * 借贷分录）转成 {@link KingdeeVoucherEntryDraft}，交给 {@link KingdeeGlVoucherPayloadBuilder}
 * 生成 GL_VOUCHER 报文。选择总账落点的原因见 {@link KingdeeProperties#isGlTarget()}（FIX-007：
 * 账套未启用出纳模块，收付款单保存被拒）。</p>
 *
 * <p><b>上线前的三道闸</b>（都在这里把关，避免金蝶侧才报错或更糟——静默记错账）：</p>
 * <ol>
 *   <li><b>科目编码必须存在</b>：AI 只给名称或给错编码时拒绝，提示去草稿工作台补齐；</li>
 *   <li><b>科目名称必须与账套一致</b>：实测账套 2232=应付股利 而 AI 当「应付账款」，
 *   名称不符即拒绝（{@link KingdeeAccountCatalogService#check}）；</li>
 *   <li><b>银行类科目注入核算维度</b>：科目挂 ZDY0001 银行账号时，按账套实测形态注入；
 *   值取<b>该笔流水所属我方账户</b>的金蝶档案编码（账户级映射，见
 *   {@code KingdeeAccountMappingService}）——未映射即拒绝，不做模糊猜测。</li>
 * </ol>
 */
@Component
public class AiGlVoucherAssembler {

    /** 借贷方向字面量（与 VoucherEntry 一致）。 */
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private final KingdeeAccountCatalogService catalogService;
    private final BankAccountDimensionResolver bankDimensionResolver;

    public AiGlVoucherAssembler(KingdeeAccountCatalogService catalogService,
                                BankAccountDimensionResolver bankDimensionResolver) {
        this.catalogService = catalogService;
        this.bankDimensionResolver = bankDimensionResolver;
    }

    /**
     * 组装结果。
     *
     * @param debitLines  借方分录草稿（已带科目校验与维度）
     * @param creditLines 贷方分录草稿
     * @param warnings    非阻断提示（如「科目目录不可用，本次未校验」），随推送消息回显
     */
    public record Assembled(List<KingdeeVoucherEntryDraft> debitLines,
                            List<KingdeeVoucherEntryDraft> creditLines,
                            List<String> warnings) {
    }

    /** 单条待组装分录（与 ai_suggestion_json 的 entries 元素对应）。 */
    public record EntryInput(String summary, String subjectCode, String subjectName,
                             String direction, BigDecimal amount) {
    }

    public Assembled assemble(List<EntryInput> entries, StatementRecord statement) {
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException(400, "该流水尚未生成凭证分录：请先在凭证草稿工作台生成 AI 建议后再推送");
        }
        List<String> warnings = new ArrayList<>();
        // 目录是懒加载：先预热再判断可用性，否则首次制证会误报「未校验」
        // （真实缺陷，由 AiGlVoucherAssemblerTest.incomeVoucherGetsBankDimensionOnBankLineOnly 抓出）
        catalogService.catalog();
        if (!catalogService.isCatalogAvailable()) {
            warnings.add("账套科目目录不可用，本次未做科目校验");
        }

        List<KingdeeVoucherEntryDraft> debits = new ArrayList<>();
        List<KingdeeVoucherEntryDraft> credits = new ArrayList<>();
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        // 银行账号维度按需解析一次（懒加载，避免无银行科目时白查一次账户）
        String bankAccountDimension = null;
        boolean bankDimensionResolved = false;

        for (int i = 0; i < entries.size(); i++) {
            EntryInput entry = entries.get(i);
            int line = i + 1;
            if (entry == null) {
                throw new BusinessException(400, "第 " + line + " 行分录为空，无法制证");
            }
            String code = entry.subjectCode() == null ? "" : entry.subjectCode().trim();
            if (code.isEmpty()) {
                // AI 提示词允许「编码不确定就给空字符串」，而 GL 落点必须有编码 —— 按名称反查账套科目表
                KingdeeAccountCatalogService.NameResolution resolution =
                        catalogService.resolveByName(entry.subjectName());
                if (resolution.unique()) {
                    code = resolution.code();
                } else if (resolution.ambiguous()) {
                    throw new BusinessException(400, "第 " + line + " 行分录科目名称「" + entry.subjectName()
                            + "」在金蝶账套中对应多个科目（" + String.join("、", resolution.candidates())
                            + "）；请在凭证草稿工作台指定具体科目编码后重试");
                } else {
                    throw new BusinessException(400, "第 " + line + " 行分录缺少科目编码，且名称「"
                            + entry.subjectName() + "」在账套科目表中没有精确匹配；"
                            + "请在凭证草稿工作台选定账套科目后再推送");
                }
            }
            BigDecimal amount = entry.amount();
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException(400, "第 " + line + " 行分录金额无效（必须为正数）");
            }
            String direction = entry.direction() == null ? "" : entry.direction().trim().toUpperCase();
            if (!DEBIT.equals(direction) && !CREDIT.equals(direction)) {
                throw new BusinessException(400, "第 " + line + " 行分录借贷方向无效：" + entry.direction());
            }

            KingdeeAccountCatalogService.AccountCheck check =
                    catalogService.check(code, entry.subjectName());
            if (check.catalogUnavailable()) {
                warnings.add("科目 " + code + " 未校验（目录不可用）");
            }
            String dimension = null;
            String dimensionValue = null;
            if (catalogService.requiresBankDimension(code)) {
                if (!bankDimensionResolved) {
                    // 账户级映射：取该笔流水所属我方账户的金蝶档案编码；未映射 → 400 阻断（含处置指引）
                    bankAccountDimension = bankDimensionResolver.resolve(statement);
                    bankDimensionResolved = true;
                }
                if (bankAccountDimension == null) {
                    throw new BusinessException(400, "科目 " + code + "（" + entry.subjectName()
                            + "）需要「银行账号」核算维度，但该流水未关联我方银行账户、且未配置兜底账户"
                            + "（KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER）；请先确认流水归属账户后重试");
                }
                dimension = "BANK_ACCOUNT";
                dimensionValue = bankAccountDimension;
            }

            KingdeeVoucherEntryDraft draft = new KingdeeVoucherEntryDraft(
                    direction, code, entry.subjectName(), dimension, dimensionValue,
                    amount.setScale(2, java.math.RoundingMode.HALF_UP), "FULL", false);
            if (DEBIT.equals(direction)) {
                debits.add(draft);
                debitTotal = debitTotal.add(draft.amount());
            } else {
                credits.add(draft);
                creditTotal = creditTotal.add(draft.amount());
            }
        }

        if (debits.isEmpty() || credits.isEmpty()) {
            throw new BusinessException(400, "凭证分录必须同时包含借方与贷方，当前为 借 " + debits.size()
                    + " 行 / 贷 " + credits.size() + " 行");
        }
        if (debitTotal.subtract(creditTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(400, "借贷不平衡：借方合计 " + debitTotal
                    + "，贷方合计 " + creditTotal + "；请在凭证草稿工作台修正后重试");
        }
        return new Assembled(debits, credits, warnings);
    }

    /** 供引擎取用：把 AI 建议的分录（VoucherEntry）转成组装输入，DTO 结构变化不外溢到本类之外。 */
    public static List<EntryInput> toInputs(List<VoucherEntry> entries) {
        List<EntryInput> inputs = new ArrayList<>();
        if (entries == null) {
            return inputs;
        }
        for (VoucherEntry entry : entries) {
            inputs.add(new EntryInput(entry.summary(), entry.subjectCode(), entry.subjectName(),
                    entry.direction(), entry.amount()));
        }
        return inputs;
    }
}
