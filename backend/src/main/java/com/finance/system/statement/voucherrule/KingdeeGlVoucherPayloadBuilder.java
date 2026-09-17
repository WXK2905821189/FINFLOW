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
 *   <li><b>FDetailID 核算维度（弹性域）一期不写入</b>：槽位键（FFLEX4~13）为账套级
 *   配置，无法离线推断——1002 银行科目「必须带银行账号维度」在 REAL 首推时会报错，
 *   以报错文本校准槽位映射（探针迭代模式，与 apiexp 联调同款）。维度值已由匹配服务
 *   解析存 {@link KingdeeVoucherEntryDraft#dimensionValue()}，确认页可见可改，校准后
 *   直接落位；</li>
 *   <li>借贷合计校验：|Σ借-Σ贷| ≤ 0.01，不平拒绝构建（400）；MANUAL 行金额为 null
 *   时拒绝（400，确认页必须先补齐）；不自动 Submit/Audit（凭证由财务在金蝶侧复核，
 *   T8 拍板 + 可行性报告风险提示）。</li>
 * </ul>
 */
@Component
public class KingdeeGlVoucherPayloadBuilder {

    static final int DC_DEBIT = 1;
    static final int DC_CREDIT = -1;

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
        return root.toString();
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
            // FDetailID (flex dimension) intentionally omitted — see class javadoc calibration note.
        }
    }
}
