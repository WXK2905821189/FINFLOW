package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GL_VOUCHER 记账凭证 payload 构建（V34 WP-B）：规则分录草稿 → 金蝶总账凭证 Save 报文。
 *
 * <p>字段依据 2026-09-11 实账 {@code QueryBusinessInfo(GL_VOUCHER)} 快照
 * （docs/kingdee-openapi/openapi-docs/GL_VOUCHER/）与可行性报告路径 B 字段映射：</p>
 * <ul>
 *   <li>表头：FDate 记账日期 / FBUSDATE 业务日期（取流水交易日期）、FVOUCHERGROUPID
 *   凭证字（{@code kingdee.gl.voucher-group-number}，PRE001=「记」）、FAccountBookID
 *   账簿 + FACCBOOKORGID 核算组织（{@code kingdee.gl.acctbook-number}，账套 400，
 *   per-org 账簿待 REAL 校准）；</li>
 *   <li>分录：FEXPLANATION 摘要、FACCOUNTID 科目、FDC 借贷方向（1=借 / -1=贷，
 *   REAL 首推校准点）、FAMOUNTFOR 原币金额、FDEBIT/FREDIT 贷方、FCURRENCYID 币别
 *   （PRE001）、FEXCHANGERATETYPE（HLTX01_SYS）；</li>
 *   <li><b>FDetailID 核算维度（弹性域）</b>：2026-09-21 真实账套校准完成——两层形态
 *   {@code {"FDetailID": {"FDETAILID__FF100002": {"FNumber": "..."}}}}，槽位可配置；
 *   科目挂必录维度（如 1002 → ZDY0001 银行账号）时不注入会被金蝶拒绝。详见
 *   {@link #appendDimension} 与 docs/kingdee-openapi/gl-voucher-calibration-20260921.md；</li>
 *   <li>借贷合计校验：|Σ借-Σ贷| ≤ 0.01，不平拒绝构建（400）；MANUAL 行金额为 null
 *   时拒绝（400，确认页必须先补齐）；不自动 Submit/Audit（凭证由财务在金蝶侧复核，
 *   T8 拍板 + 可行性报告风险提示）。</li>
 * </ul>
 */
@Component
public class KingdeeGlVoucherPayloadBuilder {

    static final int DC_DEBIT = 1;
    /** 贷方方向值 = 2（2026-09-21 真实账套实测：FDC=2 的贷方分录保存成功，凭证 16043；
     *  原 -1 从未在真实环境验证过，按证据改为 2）。 */
    static final int DC_CREDIT = 2;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final KingdeeProperties props;
    private final ObjectMapper mapper;

    public KingdeeGlVoucherPayloadBuilder(KingdeeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** Builds one GL_VOUCHER save payload from prefilled drafts; validates balance and manual amounts. */
    public String buildPayload(String orgCode,
                               LocalDateTime voucherDateTime,
                               String explanation,
                               List<KingdeeVoucherEntryDraft> debitLines,
                               List<KingdeeVoucherEntryDraft> creditLines) {
        BigDecimal debitTotal = sumSide(debitLines);
        BigDecimal creditTotal = sumSide(creditLines);
        if (debitTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "凭证借方合计必须大于 0");
        }
        if (debitTotal.subtract(creditTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(400,
                    "借贷不平衡：借方合计 " + debitTotal + "，贷方合计 " + creditTotal);
        }

        ObjectNode root = mapper.createObjectNode();
        root.putArray("NeedUpDateFields");
        ArrayNode needReturn = root.putArray("NeedReturnFields");
        needReturn.add("FBillNo").add("FVOUCHERGROUPNO");
        root.put("IsDeleteEntry", "true");
        root.put("IsAutoSubmitAndAudit", "false"); // 凭证必须人工审核（T8），双重保险字段

        ObjectNode model = root.putObject("Model");
        String date = voucherDateTime == null ? null : DATE.format(voucherDateTime);
        model.put("FDate", date);
        model.put("FBUSDATE", date);
        model.putObject("FVOUCHERGROUPID").put("FNumber", props.getGlVoucherGroupNumber());
        model.putObject("FAccountBookID").put("FNumber", props.getGlAcctbookNumber());
        model.putObject("FACCBOOKORGID").put("FNumber", orgCode);

        ArrayNode entries = model.putArray("FEntity");
        appendSide(entries, explanation, debitLines, DC_DEBIT);
        appendSide(entries, explanation, creditLines, DC_CREDIT);
        assertDimensionsReady(debitLines, creditLines);
        return root.toString();
    }

    /**
     * 多维度就绪校验（V42）：分录声明了核算维度但槽位/值未配齐时**拒绝推送**并给出补齐指引。
     *
     * <p>为什么 fail-closed：维度缺失被金蝶按「未录入必录维度」拒绝还算好的；若科目维度非必录，
     * 缺维度会**静默记成错账**。宁可让财务先在「维度映射」页补一行配置。</p>
     */
    private static void assertDimensionsReady(List<KingdeeVoucherEntryDraft> debits,
                                             List<KingdeeVoucherEntryDraft> credits) {
        for (List<KingdeeVoucherEntryDraft> side : List.of(debits, credits)) {
            for (KingdeeVoucherEntryDraft line : side) {
                if (line.extraDimensions() == null) {
                    continue;
                }
                for (KingdeeVoucherEntryDraft.DimensionValue dim : line.extraDimensions()) {
                    if (!dim.injectable()) {
                        throw new BusinessException(400, "科目 " + line.account() + " 的核算维度「"
                                + dim.dimension() + "」未就绪，无法推送："
                                + (dim.note() == null ? "请在「维度映射」页补齐槽位与值映射" : dim.note()));
                    }
                }
            }
        }
    }

    private static BigDecimal sumSide(List<KingdeeVoucherEntryDraft> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (KingdeeVoucherEntryDraft line : lines) {
            if (line.manual() && line.amount() == null) {
                throw new BusinessException(400, "分录含未填金额的人工行（科目 " + line.account()
                        + "），请在确认页补齐后再推送");
            }
            if (line.amount() != null) {
                total = total.add(line.amount());
            }
        }
        return total;
    }

    private void appendSide(ArrayNode entries, String explanation,
                            List<KingdeeVoucherEntryDraft> lines, int dc) {
        for (KingdeeVoucherEntryDraft line : lines) {
            ObjectNode entry = entries.addObject();
            entry.put("FEXPLANATION", explanation);
            entry.putObject("FACCOUNTID").put("FNumber", line.account());
            entry.put("FDC", dc);
            entry.put("FAMOUNTFOR", line.amount());
            if (dc == DC_DEBIT) {
                entry.put("FDEBIT", line.amount());
                entry.put("FCREDIT", 0);
            } else {
                entry.put("FDEBIT", 0);
                entry.put("FCREDIT", line.amount());
            }
            entry.putObject("FCURRENCYID").put("FNumber", props.getCurrencyNumber());
            entry.putObject("FEXCHANGERATETYPE").put("FNumber", "HLTX01_SYS");
            appendDimension(entry, line);
        }
    }

    /**
     * 核算维度注入（2026-09-21 真实账套校准，报错驱动）。
     *
     * <p>科目挂了必录维度时不注入会被金蝶拒绝：
     * {@code 第N行分录：科目（1002-银行存款）设置的下列必录维度未录入或不可用：银行账号}。</p>
     *
     * <p><b>报文形态（实测得出，非文档推断）</b>：必须两层——
     * <pre>{"FDetailID": {"FDETAILID__FF100002": {"FNumber": "11050160520009100036"}}}</pre>
     * 外层 {@code FDetailID} 必须是对象（传数组会报
     * {@code 无法将类型为"JSONArray"的对象强制转换为类型"Dictionary"}），
     * 内层键必须是**带前缀的完整字段名**（裸槽位名 {@code FF100002} 无效）。
     * 实测凭证 16043 保存成功后回滚。</p>
     *
     * <p>槽位由 {@code kingdee.gl.bank-dimension-slot} 配置（默认 FF100002=银行账号 ZDY0001）。</p>
     */
    private void appendDimension(ObjectNode entry, KingdeeVoucherEntryDraft line) {
        ObjectNode detail = null;
        String value = line.dimensionValue();
        boolean singleReady = value != null && !value.isBlank()
                && !"NONE".equalsIgnoreCase(line.dimension());
        String singleSlot = props.getGlBankDimensionSlot();
        if (singleReady && singleSlot != null && !singleSlot.isBlank()) {
            detail = entry.putObject("FDetailID");
            detail.putObject("FDETAILID__" + singleSlot.trim()).put("FNumber", value.trim());
        }
        if (line.extraDimensions() == null || line.extraDimensions().isEmpty()) {
            return;
        }
        for (KingdeeVoucherEntryDraft.DimensionValue dim : line.extraDimensions()) {
            if (!dim.injectable()) {
                continue; // 未就绪项由 assertDimensionsReady 在构建前拦下；此处防御性跳过
            }
            if (detail == null) {
                detail = entry.putObject("FDetailID");
            }
            detail.putObject("FDETAILID__" + dim.slot().trim()).put("FNumber", dim.value().trim());
        }
    }
}
