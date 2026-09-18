package com.finance.system.statement.voucherrule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.ai.AiGatewayService;
import com.finance.system.ai.LlmChatRequest;
import com.finance.system.ai.LlmChatResult;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.voucherrule.dto.KingdeeRuleGroupResponse;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * W4 规则中心（2026-09-18）：规则 Excel 导入三步中的前两步支撑。
 *
 * <p><b>无状态两步设计</b>：preview（上传解析 + AI 映射 → 返回预览，不入库）→
 * 人工在规则中心勾选/修正 → confirm（把修正后的行 POST 回来，走
 * {@link KingdeeVoucherRuleService#createRule} 统一校验入库）。不建暂存表、无会话态。</p>
 *
 * <p><b>AI fail-closed 且不阻断</b>：AI 不可用（总开关关/密钥缺/能力关/网络失败/解析失败）
 * 时预览行 {@code aiMapped=false} 并附原因，用户手填映射后照常导入——AI 只加速不设卡。
 * 能力开关复用 {@code accounting-suggestion}（同属制证辅助域，AI 设置页无需新增开关），
 * 审计能力名独立为 {@code rule-import}（ai_call_log 可区分）。</p>
 *
 * <p><b>Excel 解析</b>（Apache POI，首 sheet）：首行识别列头（规则名/业务类型/大类/方向/
 * 匹配字段/匹配方式/关键词/科目编码/科目名称/借方模板/贷方模板/备注…），数据行转单元格矩阵。
 * AI 把每行自由文本映射为 {@link KingdeeRuleGroupResponse.RuleUpsertRequest} 结构。</p>
 */
@Service
public class KingdeeRuleImportService {

    private static final Logger log = LoggerFactory.getLogger(KingdeeRuleImportService.class);

    /** 审计能力名（ai_call_log.action 维度）；开关复用 accounting-suggestion。 */
    private static final String AUDIT_CAPABILITY = "rule-import";
    private static final String GUARD_CAPABILITY = "accounting-suggestion";

    private static final String SYSTEM_PROMPT = """
            你是 FINFLOW 财务系统的规则导入助手。输入是 Excel 一行的单元格文本数组（财务原始映射表），
            请把它映射为一条金蝶凭证规则，只输出一个 JSON 对象（不要 markdown 围栏）：
            {
              "businessType": "业务类型（如 社保/个税/租金/工资/差旅/办公，从原文归纳）",
              "category": "大类（与业务类型一致的简短归类）",
              "direction": "INCOME|EXPENSE|BOTH",
              "match": {"logic": "ALL|ANY", "conditions": [{"field": "SUMMARY|COUNTERPARTY_NAME", "op": "CONTAINS|IN", "values": ["关键词"]}]},
              "debitLines": [{"account": "科目编码或空", "name": "科目名称", "dimension": "BANK_ACCOUNT|ORG|EMPLOYEE|SUPPLIER|CUSTOMER|COUNTERPARTY|FIXED|NONE", "value": "FIXED 维度的值或空", "share": "FULL|EQUAL|MANUAL"}],
              "creditLines": ["结构同 debitLines"],
              "remark": "原文备注（可空）"
            }
            约定：收/收款/进账=INCOME，付/付款/支出=EXPENSE，两种都有=BOTH；
            摘要关键词→field=SUMMARY，对方单位→field=COUNTERPARTY_NAME；
            银行存款/本方账户行 dimension=BANK_ACCOUNT share=FULL；费用/往来科目 dimension=SUPPLIER 或 COUNTERPARTY。
            原文信息不足时把能确定的字段给出，无法确定的字段留空/null。""".stripIndent();

    private final KingdeeVoucherRuleService ruleService;
    private final AiGatewayService aiGatewayService;
    private final ObjectMapper objectMapper;

    public KingdeeRuleImportService(KingdeeVoucherRuleService ruleService,
                                    AiGatewayService aiGatewayService,
                                    ObjectMapper objectMapper) {
        this.ruleService = ruleService;
        this.aiGatewayService = aiGatewayService;
        this.objectMapper = objectMapper;
    }

    /** 第一步：解析 Excel + AI 映射（AI 不可用降级为 aiMapped=false，不抛异常中断）。 */
    public KingdeeRuleGroupResponse.ImportPreviewResponse preview(MultipartFile file, Long operatorId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请上传规则 Excel 文件（.xlsx）");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xlsm")) {
            throw new BusinessException(400, "仅支持 .xlsx 格式（Excel 另存为 → 工作簿 .xlsx）");
        }
        List<List<String>> matrix = parseXlsx(file);
        if (matrix.size() < 2) {
            throw new BusinessException(400, "Excel 需要表头行和至少一行数据");
        }

        com.finance.system.ai.AiEffectiveConfig config = null;
        try {
            config = aiGatewayService.auditedGuard(GUARD_CAPABILITY, operatorId);
        } catch (Exception e) {
            log.info("W4 规则导入 AI 守卫未通过（fail-closed 降级人工映射）：{}", e.getMessage());
        }
        boolean aiAvailable = config != null;
        List<KingdeeRuleGroupResponse.ImportPreviewRow> rows = new ArrayList<>();
        int mapped = 0;
        for (int i = 1; i < matrix.size(); i++) {
            List<String> cells = matrix.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            KingdeeRuleGroupResponse.ImportPreviewRow row = aiAvailable
                    ? mapWithAi(i, cells, operatorId, config)
                    : new KingdeeRuleGroupResponse.ImportPreviewRow(i, cells, null, false, null,
                            "AI 不可用（总开关/密钥/能力开关或网络），请在下方手填映射后导入");
            if (row.aiMapped()) {
                mapped++;
            }
            rows.add(row);
        }
        String summary = aiAvailable
                ? "AI 已映射 " + mapped + "/" + rows.size() + " 行，请逐行核对（勾选需导入的行，可直接修正映射结果）"
                : "AI 未参与本次导入（能力不可用），共 " + rows.size() + " 行待人工填写映射";
        return new KingdeeRuleGroupResponse.ImportPreviewResponse(rows.size(), rows, summary);
    }

    /** 第二步：确认入库（人工勾选/修正后的行）。 */
    public List<KingdeeVoucherRuleResponse> confirm(KingdeeRuleGroupResponse.ImportConfirmRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw new BusinessException(400, "请至少勾选一行待导入的规则");
        }
        return ruleService.confirmImport(request.rows(), request.defaultGroupId());
    }

    /** 模板 Excel（首个 sheet 写示例表头 + 2 行示例）。 */
    public byte[] template() {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("规则导入模板");
            String[] headers = {"规则名称", "业务类型", "大类", "方向", "摘要关键词", "对方单位关键词",
                    "借方科目编码", "借方科目名称", "贷方科目编码", "贷方科目名称", "备注"};
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            String[][] samples = {
                    {"办公室租金", "租金", "租赁费", "EXPENSE", "租金", "", "660203", "租赁费",
                            "100201", "银行存款", "按月支付办公室租金"},
                    {"收客户货款", "货款", "销售收入", "INCOME", "", "货款", "100201", "银行存款",
                            "600101", "主营业务收入", "客户回款"},
            };
            for (int r = 0; r < samples.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) {
                    row.createCell(c).setCellValue(samples[r][c]);
                }
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            try (var out = new java.io.ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new BusinessException(500, "规则模板生成失败：" + e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    private List<List<String>> parseXlsx(MultipartFile file) {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(file.getInputStream())) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> matrix = new ArrayList<>();
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                List<String> cells = new ArrayList<>();
                short last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : switch (cell.getCellType()) {
                        case STRING -> cell.getStringCellValue().trim();
                        case NUMERIC -> {
                            double d = cell.getNumericCellValue();
                            yield d == Math.floor(d) ? String.valueOf((long) d) : BigDecimal.valueOf(d).toPlainString();
                        }
                        case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                        case FORMULA -> cell.getCellFormula();
                        default -> "";
                    });
                }
                matrix.add(cells);
            }
            return matrix;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel 解析失败：" + e.getMessage());
        }
    }

    private KingdeeRuleGroupResponse.ImportPreviewRow mapWithAi(int rowIndex, List<String> cells,
                                                                Long operatorId,
                                                                com.finance.system.ai.AiEffectiveConfig config) {
        String userPrompt = "Excel 行数据（JSON 数组）：" + writeJson(cells);
        try {
            LlmChatResult result = aiGatewayService.auditedChat(AUDIT_CAPABILITY, operatorId,
                    config, new LlmChatRequest(AUDIT_CAPABILITY, SYSTEM_PROMPT, userPrompt, 0.1, 1024));
            JsonNode json = objectMapper.readTree(result.content());
            KingdeeRuleGroupResponse.RuleUpsertRequest mapped = toUpsert(json, cells);
            Double confidence = json.path("confidence").isNumber() ? json.path("confidence").asDouble() : null;
            return new KingdeeRuleGroupResponse.ImportPreviewRow(rowIndex, cells, mapped, true, confidence, null);
        } catch (Exception e) {
            log.warn("W4 规则导入 AI 映射失败（row={}）：{}", rowIndex, e.getMessage());
            return new KingdeeRuleGroupResponse.ImportPreviewRow(rowIndex, cells, null, false, null,
                    "AI 映射失败：" + trim(e.getMessage()) + "（可手填映射后导入）");
        }
    }

    private KingdeeRuleGroupResponse.RuleUpsertRequest toUpsert(JsonNode json, List<String> cells) {
        List<KingdeeVoucherRuleResponse.LineTemplate> debits = new ArrayList<>();
        List<KingdeeVoucherRuleResponse.LineTemplate> credits = new ArrayList<>();
        JsonNode debitNode = json.path("debitLines");
        if (debitNode.isArray()) {
            debitNode.forEach(n -> debits.add(line(n)));
        }
        JsonNode creditNode = json.path("creditLines");
        if (creditNode.isArray()) {
            creditNode.forEach(n -> credits.add(line(n)));
        }
        if (debits.isEmpty() && !cells.isEmpty()) {
            // AI 未给出借方时，用原文科目列兜底（借方科目编码=cells[6]、名称=cells[7]）
            String code = cellAt(cells, 6);
            String nm = cellAt(cells, 7);
            if (!code.isBlank() || !nm.isBlank()) {
                debits.add(new KingdeeVoucherRuleResponse.LineTemplate(code.isBlank() ? null : code,
                        nm.isBlank() ? null : nm, "COUNTERPARTY", null, null, "FULL"));
            }
        }
        if (credits.isEmpty() && cells.size() > 9) {
            String code = cellAt(cells, 8);
            String nm = cellAt(cells, 9);
            if (!code.isBlank() || !nm.isBlank()) {
                credits.add(new KingdeeVoucherRuleResponse.LineTemplate(code.isBlank() ? null : code,
                        nm.isBlank() ? null : nm, "BANK_ACCOUNT", null, null, "FULL"));
            }
        }
        List<KingdeeVoucherRuleResponse.Condition> conditions = new ArrayList<>();
        JsonNode condNode = json.path("match").path("conditions");
        if (condNode.isArray()) {
            condNode.forEach(n -> conditions.add(new KingdeeVoucherRuleResponse.Condition(
                    textOrNull(n, "field"), textOrNull(n, "op"), stringList(n.path("values")))));
        }
        conditions.addAll(keywordFallbackConditions(cells, conditions));
        return new KingdeeRuleGroupResponse.RuleUpsertRequest(
                null,
                textOrNull(json, "businessType"),
                textOrNull(json, "category"),
                null,
                null,
                null,
                textOrNull(json, "direction"),
                null,
                null,
                new KingdeeVoucherRuleResponse.Match(
                        "ALL".equalsIgnoreCase(json.path("match").path("logic").asText()) ? "ALL" : "ANY",
                        conditions),
                debits,
                credits,
                null,
                true,
                textOrNull(json, "remark"),
                null);
    }

    /** AI 没给 match 条件时，从原文关键词列兜底（摘要关键词=cells[4]、对方单位=cells[5]）。 */
    private List<KingdeeVoucherRuleResponse.Condition> keywordFallbackConditions(
            List<String> cells, List<KingdeeVoucherRuleResponse.Condition> existing) {
        List<KingdeeVoucherRuleResponse.Condition> fallback = new ArrayList<>();
        String summaryKeyword = cellAt(cells, 4);
        String counterpartyKeyword = cellAt(cells, 5);
        boolean hasSummary = existing.stream().anyMatch(c -> "SUMMARY".equals(c.field()));
        boolean hasCounterparty = existing.stream().anyMatch(c -> "COUNTERPARTY_NAME".equals(c.field()));
        if (!summaryKeyword.isBlank() && !hasSummary) {
            fallback.add(new KingdeeVoucherRuleResponse.Condition("SUMMARY", "CONTAINS", List.of(summaryKeyword)));
        }
        if (!counterpartyKeyword.isBlank() && !hasCounterparty) {
            fallback.add(new KingdeeVoucherRuleResponse.Condition("COUNTERPARTY_NAME", "CONTAINS",
                    List.of(counterpartyKeyword)));
        }
        return fallback;
    }

    private KingdeeVoucherRuleResponse.LineTemplate line(JsonNode n) {
        return new KingdeeVoucherRuleResponse.LineTemplate(
                textOrNull(n, "account"), textOrNull(n, "name"), textOrNull(n, "dimension"),
                textOrNull(n, "value"), null, textOrNull(n, "share"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static List<String> stringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) {
                    values.add(v.trim());
                }
            });
        }
        return values;
    }

    private static String cellAt(List<String> cells, int index) {
        return index < cells.size() && cells.get(index) != null ? cells.get(index).trim() : "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.length() > 120 ? value.substring(0, 120) : value;
    }
}
