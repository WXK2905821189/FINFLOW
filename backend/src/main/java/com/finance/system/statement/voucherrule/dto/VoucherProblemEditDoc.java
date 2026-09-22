package com.finance.system.statement.voucherrule.dto;

import com.finance.system.common.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


/**
 * A2 问题凭证编辑器的人工编辑态（V44 statement_record.problem_edit_json 的文档结构）。
 *
 * <p>PUT 保存重校验后整体序列化进 problem_edit_json；submit 时反序列化并直接作为
 * {@link KingdeeVoucherEngineService#pushManual} 的分录来源——「存的是什么、推的就是什么」。</p>
 *
 * <p>校验口径（规划 2.3，前端禁按钮 + 后端 400 双保险）：</p>
 * <ul>
 *   <li>至少一条有效分录；科目编码非空、借贷方向枚举、金额正数；</li>
 *   <li>借贷合计不等禁存（|Σ借−Σ贷| ≤ 0.01）；</li>
 *   <li>科目必明细（{@code KingdeeAccountCatalogService.isDetailAccount}，父科目 400）；</li>
 *   <li>挂银行账号维度的科目必须带维度值（与引擎 ensureBankDimensionPresent 同口径，
 *       保存时即拦截而非等到推送）。</li>
 * </ul>
 */
public record VoucherProblemEditDoc(
        String summary,
        List<ProblemLine> debitLines,
        List<ProblemLine> creditLines,
        Long editedBy,
        LocalDateTime editedAt) {

    /** 编辑器单行分录（比 KingdeeVoucherEntryDraft 多保留了字段语义校验的入参形态）。 */
    public record ProblemLine(
            String account,
            String accountName,
            String dimension,
            String dimensionValue,
            BigDecimal amount,
            String share,
            String note) {

        /**
         * 转 GL 分录草稿（manual=false——编辑器保存时金额必须已填；side 由外层归属决定）。
         *
         * <p>slotResolver：维度类型 → 弹性域槽位键（{@code KingdeeDimensionMappingService.slotOf}）。
         * DimensionValue.slot 为 null 时 {@code injectable()=false}，推送会被
         * assertDimensionsReady 拒绝——编辑器保存的分录必须现场解析槽位，不能存快照
         * （槽位可在界面改，存快照会漂移）。slotResolver 为 null 或查不到槽位时保持 null，
         * 由推送链路给出「维度未就绪」的明确拒绝理由（fail-closed）。</p>
         */
        /**
         * 转 GL 分录草稿（manual=false——编辑器保存时金额必须已填；side 由外层归属决定）。
         *
         * <p>维度口径：编辑器只提供「单维度 + 值」编辑，走 KingdeeVoucherEntryDraft 的
         * 单维度字段（dimension/dimensionValue，payloadBuilder 单维度槽位路由），
         * <b>不</b>写入 extraDimensions——同一维度同时走两条路由会被
         * assertNoSlotCollision 判为槽位冲突（实测：BANK_ACCOUNT 双落 FF100002）。
         * 多维度联合注入是规则侧能力（V42 extraDimensions），编辑器本轮不放开。</p>
         *
         * <p>slotResolver 参数保留（签名稳定性）；单维度路由的槽位解析在
         * payloadBuilder 内完成，此处不再预解析。</p>
         */
        public KingdeeVoucherEntryDraft toDraft(String side,
                                                java.util.function.Function<String, String> slotResolver) {
            return new KingdeeVoucherEntryDraft(
                    side,
                    account, accountName, dimension,
                    dimensionValue == null || dimensionValue.isBlank() ? null : dimensionValue.trim(),
                    amount, share == null || share.isBlank() ? "MANUAL" : share, false, null);
        }
    }

    // ---------------- 校验与规范化 ----------------

    /** 填入编辑人信息（校验通过后由服务层调用）。 */
    public VoucherProblemEditDoc withEditor(Long editorId) {
        return new VoucherProblemEditDoc(summary, debitLines, creditLines, editorId, LocalDateTime.now());
    }

    /**
     * 逐行校验并规范化；全部通过后返回可用于序列化的新文档（editedBy/editedAt 由调用方填）。
     *
     * @param isDetailAccount 科目必明细判定（调用方注入，便于测试；null 跳过该检查）
     */
    public VoucherProblemEditDoc normalizeAndValidate(
            java.util.function.Predicate<String> isDetailAccount) {
        List<ProblemLine> debits = normalizeSide(debitLines, "借方");
        List<ProblemLine> credits = normalizeSide(creditLines, "贷方");
        if (debits.isEmpty() && credits.isEmpty()) {
            throw new BusinessException(400, "凭证至少需要一条有效分录（科目编码/借贷方向/正数金额）");
        }
        if (debits.isEmpty()) {
            throw new BusinessException(400, "凭证缺少借方分录");
        }
        if (credits.isEmpty()) {
            throw new BusinessException(400, "凭证缺少贷方分录");
        }
        BigDecimal debitTotal = debits.stream().map(ProblemLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = credits.stream().map(ProblemLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debitTotal.subtract(creditTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(400,
                    "借贷不平衡：借方合计 " + debitTotal + "，贷方合计 " + creditTotal
                            + "（差额超过 0.01，禁止保存）");
        }
        if (isDetailAccount != null) {
            for (ProblemLine line : debits) {
                requireDetail(isDetailAccount, line, "借方");
            }
            for (ProblemLine line : credits) {
                requireDetail(isDetailAccount, line, "贷方");
            }
        }
        return new VoucherProblemEditDoc(
                summary == null ? null : summary.trim(), debits, credits, editedBy, editedAt);
    }

    private static void requireDetail(java.util.function.Predicate<String> isDetailAccount,
                                      ProblemLine line, String sideLabel) {
        if (line.account() != null && !isDetailAccount.test(line.account())) {
            throw new BusinessException(400, sideLabel + "分录科目 " + line.account()
                    + "（" + safeName(line) + "）是父科目，金蝶不允许记账（FIsDetail=false）；"
                    + "请选择其下级明细科目");
        }
    }

    private static String safeName(ProblemLine line) {
        return line.accountName() == null ? "" : line.accountName();
    }

    /** 单侧分录规范化：科目编码/金额必填正数。 */
    private static List<ProblemLine> normalizeSide(List<ProblemLine> lines, String sideLabel) {
        List<ProblemLine> normalized = new ArrayList<>();
        if (lines == null) {
            return normalized;
        }
        for (ProblemLine line : lines) {
            if (line == null) {
                continue;
            }
            String account = line.account() == null ? "" : line.account().trim();
            BigDecimal amount = line.amount();
            if (account.isEmpty()) {
                throw new BusinessException(400, sideLabel + "分录缺少科目编码");
            }
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException(400, sideLabel + "分录科目 " + account + " 金额必须为正数");
            }
            if (line.dimensionValue() != null && !line.dimensionValue().isBlank()
                    && (line.dimension() == null || line.dimension().isBlank()
                        || "NONE".equalsIgnoreCase(line.dimension()))) {
                throw new BusinessException(400, sideLabel + "分录科目 " + account
                        + " 给了维度值但缺少维度类型");
            }
            normalized.add(new ProblemLine(account,
                    line.accountName() == null ? null : line.accountName().trim(),
                    line.dimension() == null ? null
                            : line.dimension().trim().toUpperCase(Locale.ROOT),
                    line.dimensionValue() == null || line.dimensionValue().isBlank()
                            ? null : line.dimensionValue().trim(),
                    amount, line.share(), line.note()));
        }
        return normalized;
    }
}
