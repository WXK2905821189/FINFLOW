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
    public static final String AUDIT_CAPABILITY = "rule-import";
    private static final String GUARD_CAPABILITY = "accounting-suggestion";

    /** 系统默认提示词（W9 起可被 ai_prompt_override 覆盖，见 AiPromptCatalog）。 */
    public static final String SYSTEM_PROMPT = """
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
    private final com.finance.system.ai.AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    public KingdeeRuleImportService(KingdeeVoucherRuleService ruleService,
                                    AiGatewayService aiGatewayService,
                                    com.finance.system.ai.AiPromptService aiPromptService,
                                    ObjectMapper objectMapper) {
        this.ruleService = ruleService;
        this.aiGatewayService = aiGatewayService;
        this.aiPromptService = aiPromptService;
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
        // W8：模板升级为「说明横幅 + 表头 + 示例行」。数据起点 = 首个以「规则名称」开头的
        // 表头行的下一行；找不到表头则维持旧口径（跳过首行，兼容用户自制表头在最前的文件）。
        // 横幅与表头行不进 AI 映射；示例行仍会被解析（横幅已提示删除），保持既有口径。
        int start = 1;
        for (int i = 0; i < matrix.size(); i++) {
            if (!matrix.get(i).isEmpty() && "规则名称".equals(matrix.get(i).get(0))) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < matrix.size(); i++) {
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

    /**
     * 模板 Excel（W8 美化，2026-09-20）：
     *  · Sheet1「规则导入模板」：说明横幅 + 加粗底色表头 + 2 行示例（黄底提示行）+ 11 列定宽；
     *  · 「方向」列数据验证下拉（EXPENSE/INCOME），防手填枚举外取值；
     *  · Sheet2「填写说明」：逐列说明 + 必填标记 + 枚举取值 + 常见错误。
     */
    public byte[] template() {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("规则导入模板");
            String[] headers = {"规则名称", "业务类型", "大类", "方向", "摘要关键词", "对方单位关键词",
                    "借方科目编码", "借方科目名称", "贷方科目编码", "贷方科目名称", "备注"};

            // ---- 样式 ----
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            org.apache.poi.ss.usermodel.CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_40_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            org.apache.poi.ss.usermodel.CellStyle sampleStyle = workbook.createCellStyle();
            sampleStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            sampleStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            sampleStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            sampleStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            sampleStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            sampleStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            org.apache.poi.ss.usermodel.CellStyle bodyStyle = workbook.createCellStyle();
            bodyStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bodyStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bodyStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bodyStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            // ---- 第 0 行：说明横幅 ----
            org.apache.poi.ss.usermodel.Row banner = sheet.createRow(0);
            org.apache.poi.ss.usermodel.Cell bannerCell = banner.createCell(0);
            bannerCell.setCellValue("填写说明见第二个工作表「填写说明」；黄色示例行仅供参照，导入时会被解析为规则，请在正式数据前删除");
            bannerCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.length - 1));

            // ---- 第 1 行：表头 ----
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ---- 第 2-3 行：示例（黄底）----
            String[][] samples = {
                    {"办公室租金", "租金", "租赁费", "EXPENSE", "租金", "", "660203", "租赁费",
                            "100201", "银行存款", "按月支付办公室租金"},
                    {"收客户货款", "货款", "销售收入", "INCOME", "", "货款", "100201", "银行存款",
                            "600101", "主营业务收入", "客户回款"},
            };
            for (int r = 0; r < samples.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 2);
                for (int c = 0; c < samples[r].length; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    cell.setCellValue(samples[r][c]);
                    cell.setCellStyle(sampleStyle);
                }
            }

            // ---- 数据验证：「方向」列 D3:D500 下拉 ----
            org.apache.poi.ss.usermodel.DataValidationHelper helper = sheet.getDataValidationHelper();
            org.apache.poi.ss.usermodel.DataValidationConstraint constraint =
                    helper.createExplicitListConstraint(new String[]{"EXPENSE", "INCOME"});
            org.apache.poi.ss.util.CellRangeAddressList range = new org.apache.poi.ss.util.CellRangeAddressList(2, 500, 3, 3);
            org.apache.poi.ss.usermodel.DataValidation validation = helper.createValidation(constraint, range);
            validation.setShowErrorBox(true);
            validation.createErrorBox("方向取值无效", "方向仅允许 EXPENSE（支出/付款）或 INCOME（收入/收款）");
            ((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).addValidationData(validation);

            // ---- 列宽（autoSize 对中文在无头环境不稳定，改显式宽度；1 字符 ≈ 256）----
            int[] widths = {22, 14, 16, 12, 20, 24, 14, 16, 14, 16, 26};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 2);

            // ---- Sheet2：填写说明 ----
            org.apache.poi.ss.usermodel.Sheet guide = workbook.createSheet("填写说明");
            String[][] guideRows = {
                    {"列名", "是否必填", "填写说明"},
                    {"规则名称", "必填", "规则的业务名称，导入后展示在规则中心列表，如「办公室租金」"},
                    {"业务类型", "必填", "业务的粗分类，如 租金 / 货款 / 工资 / 水电费，用于按类型归组"},
                    {"大类", "必填", "凭证大类（与规则中心「大类规则」一致），如 租赁费 / 销售收入 / 薪酬"},
                    {"方向", "必填", "仅允许两个取值（本列有下拉校验）：EXPENSE = 支出/付款；INCOME = 收入/收款"},
                    {"摘要关键词", "选填", "匹配银行流水摘要（businessText/摘要列）包含该关键词即命中；与「对方单位关键词」至少填一个"},
                    {"对方单位关键词", "选填", "匹配收付方名称包含该关键词即命中；两个关键词都填时为「且」关系"},
                    {"借方科目编码", "必填", "金蝶科目编码，需与账套科目一致，如 660203"},
                    {"借方科目名称", "选填", "科目名称仅用于核对展示，匹配以编码为准"},
                    {"贷方科目编码", "必填", "同借方科目编码"},
                    {"贷方科目名称", "选填", "同借方科目名称"},
                    {"备注", "选填", "规则备注，导入后展示在规则详情"},
                    {"", "", ""},
                    {"常见错误", "", ""},
                    {"方向填了「支出/收入」", "", "必须用枚举值 EXPENSE / INCOME（可点击单元格用下拉选择）"},
                    {"关键词全空", "", "摘要关键词与对方单位关键词至少填一个，否则该行无法命中任何流水"},
                    {"科目编码不存在", "", "编码需为金蝶账套中真实存在的科目；导入确认页会按科目编码回显科目名称供核对"},
            };
            org.apache.poi.ss.usermodel.Font guideHeaderFont = workbook.createFont();
            guideHeaderFont.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle guideHeaderStyle = workbook.createCellStyle();
            guideHeaderStyle.setFont(guideHeaderFont);
            for (int r = 0; r < guideRows.length; r++) {
                org.apache.poi.ss.usermodel.Row row = guide.createRow(r);
                for (int c = 0; c < guideRows[r].length; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    cell.setCellValue(guideRows[r][c]);
                    if (r == 0) {
                        cell.setCellStyle(guideHeaderStyle);
                    }
                }
            }
            guide.setColumnWidth(0, 24 * 256);
            guide.setColumnWidth(1, 10 * 256);
            guide.setColumnWidth(2, 90 * 256);

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
            // W9：系统提示词支持超管在页面覆盖（ai_prompt_override），无覆盖回落 SYSTEM_PROMPT。
            LlmChatResult result = aiGatewayService.auditedChat(AUDIT_CAPABILITY, operatorId,
                    config, new LlmChatRequest(AUDIT_CAPABILITY, aiPromptService.resolve(AUDIT_CAPABILITY),
                            userPrompt, 0.1, 1024));
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
