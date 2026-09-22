package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KingdeeGlVoucherPayloadBuilder.class);

    private final KingdeeProperties props;
    private final ObjectMapper mapper;
    private final KingdeeAccountCatalogService catalogService;
    private final KingdeeOrgResolver orgResolver;

    public KingdeeGlVoucherPayloadBuilder(KingdeeProperties props, ObjectMapper mapper,
                                          KingdeeAccountCatalogService catalogService,
                                          KingdeeOrgResolver orgResolver) {
        this.props = props;
        this.mapper = mapper;
        this.catalogService = catalogService;
        this.orgResolver = orgResolver;
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
        // 账簿跟随组织（2026-09-22 定案）：维度值档案必须属于账簿对应组织，固定 400 会让
        // 非雪云主体的「银行账号」维度被判「不可用」。orgCode 为 null 时回退全局默认。
        model.putObject("FAccountBookID").put("FNumber",
                orgResolver.resolveAcctbookCode(orgCode, props.getGlAcctbookNumber()));
        model.putObject("FACCBOOKORGID").put("FNumber", orgCode);

        ArrayNode entries = model.putArray("FEntity");
        appendSide(entries, explanation, debitLines, DC_DEBIT);
        appendSide(entries, explanation, creditLines, DC_CREDIT);
        // 先触发按需加载再校验：规则路径上本方法是目录服务的首个调用点
        //（AI 路径由 AiGlVoucherAssembler 预热），不预热的话下方 isCatalogAvailable()
        // 会因「从未加载」误判不可用，P1-1 维度校验被静默跳过。
        catalogService.catalog();
        assertDimensionsReady(debitLines, creditLines);
        assertBankDimensionInjectedForBankAccounts(debitLines, creditLines);
        assertNoSlotCollision(debitLines, creditLines);
        if (!catalogService.isCatalogAvailable()) {
            // 目录降级口径（P1-1）：不阻断推送，但必须留痕。注意提示只能进日志——
            // 本方法返回值就是发金蝶 Save 的请求体，任何非 JSON 尾巴都会让报文解析失败。
            log.warn("GL_VOUCHER 报文构建时账套科目目录不可用，本次未做「科目是否需要银行账号维度」判定"
                    + "（若金蝶报「必录维度未录入」，请先做一次连接测试恢复目录后重推）");
        }
        log.info("GL_VOUCHER 报文摘要（诊断用，不含金额明细）：acctbook={} org={} 行数={} 分录={}",
                model.path("FAccountBookID").path("FNumber").asText(), orgCode, entries.size(),
                summarizeEntries(entries));
        return root.toString();
    }

    /**
     * 报文摘要（诊断插桩，2026-09-22）：逐行输出「科目编码 + 该行 FDetailID 的槽位=值」。
     *
     * <p>存在的原因：线上出现「金蝶报必录维度未录入，但本地各项判定都成立」的矛盾 ——
     * 静态推断已到极限，需要一个能证明「报文里到底带没带维度」的观测点。
     * **只打印科目编码与维度键值**（银行账号档案编码本身是档案号，非账号明文），不含金额与摘要。</p>
     */
    private static String summarizeEntries(ArrayNode entries) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (com.fasterxml.jackson.databind.JsonNode entry : entries) {
            index++;
            String account = entry.path("FACCOUNTID").path("FNumber").asText("(无科目)");
            text.append('#').append(index).append(' ').append(account);
            com.fasterxml.jackson.databind.JsonNode detail = entry.get("FDetailID");
            if (detail == null || !detail.isObject() || detail.isEmpty()) {
                text.append(" 维度[无]");
            } else {
                ((ObjectNode) detail).fields().forEachRemaining(field ->
                        text.append(' ').append(field.getKey()).append('=')
                                .append(field.getValue().path("FNumber").asText("-")));
            }
            text.append(" | ");
        }
        return text.toString();
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

    /**
     * P1-2（2026-09-22）：单维度与 extraDimensions 写到**同一弹性域槽位**时拒绝推送。
     *
     * <p>原先 {@code appendDimension} 是 {@code putObject} 顺序写入、后者静默覆盖前者——
     * 例如用户在「维度映射 › 值映射」里又给一条分录配了与单维度同槽位的维度（典型：把银行账号
     * 同时配成值映射），同一槽位键会被写两次，最终值取决于写入顺序。不同槽位可正常共存
     * （既有用例 {@code singleAndMultipleDimensionsCoexistOnOneEntry} 口径），只有撞键才拦。</p>
     */
    private void assertNoSlotCollision(List<KingdeeVoucherEntryDraft> debits,
                                       List<KingdeeVoucherEntryDraft> credits) {
        String singleSlotKey = props.getGlBankDimensionSlot() == null ? "" : props.getGlBankDimensionSlot().trim();
        for (List<KingdeeVoucherEntryDraft> side : List.of(debits, credits)) {
            for (KingdeeVoucherEntryDraft line : side) {
                if (line.extraDimensions() == null || line.extraDimensions().isEmpty()) {
                    continue;
                }
                boolean singleReady = line.dimensionValue() != null && !line.dimensionValue().isBlank()
                        && !"NONE".equalsIgnoreCase(line.dimension());
                if (!singleReady) {
                    continue;
                }
                for (KingdeeVoucherEntryDraft.DimensionValue dim : line.extraDimensions()) {
                    if (!dim.injectable()) {
                        continue; // 未就绪项由 assertDimensionsReady 拦下
                    }
                    String dimSlot = dim.slot() == null ? "" : dim.slot().trim();
                    if (!singleSlotKey.isEmpty() && singleSlotKey.equalsIgnoreCase(dimSlot)) {
                        throw new BusinessException(400, "科目 " + line.account()
                                + " 的单维度 " + line.dimension() + " 与维度「" + dim.dimension()
                                + "」都落在槽位 " + singleSlotKey + "，后者会覆盖前者；"
                                + "请检查「维度映射」配置，删除重复的那条后重试");
                    }
                }
            }
        }
    }

    /**
     * P1-1（2026-09-22）：科目挂「银行账号」必录维度但分录没带维度值时，推送前拦下
     * （把金蝶的「必录维度未录入」报错前移到 FINFLOW 侧，附可执行指引）。
     *
     * <p>判定来源 = 账套科目目录（BD_Account 的 FFlEXITEMPROPERTYID），与 AI 链路
     * {@code AiGlVoucherAssembler} 同一口径。目录不可用时跳过（fail-open，与既有降级
     * 语义一致——推送消息会标注「未做维度需求判定」）。规则模板漏声明 BANK_ACCOUNT、
     * 或账户级映射失效导致的缺失，都会在此处得到明确报错而不是金蝶的模糊报错。</p>
     */
    private void assertBankDimensionInjectedForBankAccounts(List<KingdeeVoucherEntryDraft> debits,
                                                            List<KingdeeVoucherEntryDraft> credits) {
        if (!catalogService.isCatalogAvailable()) {
            return; // 目录不可用：不判定（requiresBankDimension 内部已 WARN），由金蝶报错校准
        }
        for (List<KingdeeVoucherEntryDraft> side : List.of(debits, credits)) {
            for (KingdeeVoucherEntryDraft line : side) {
                if (!catalogService.requiresBankDimension(line.account())) {
                    continue;
                }
                boolean hasValue = line.dimensionValue() != null && !line.dimensionValue().isBlank()
                        && !"NONE".equalsIgnoreCase(line.dimension());
                boolean declaredInExtra = line.extraDimensions() != null && line.extraDimensions().stream()
                        .anyMatch(dim -> "BANK_ACCOUNT".equalsIgnoreCase(dim.dimension())
                                && dim.value() != null && !dim.value().isBlank());
                if (!hasValue && !declaredInExtra) {
                    throw new BusinessException(400, "科目 " + line.account() + "（"
                            + safeName(line) + "）在账套挂了必录的「银行账号」核算维度，但本条分录未携带维度值；"
                            + "请确认流水所属银行账户已做金蝶账户映射（银行账户页），"
                            + "或规则模板已声明 BANK_ACCOUNT 维度");
                }
            }
        }
    }

    private static String safeName(KingdeeVoucherEntryDraft line) {
        return line.accountName() == null ? "" : line.accountName();
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
