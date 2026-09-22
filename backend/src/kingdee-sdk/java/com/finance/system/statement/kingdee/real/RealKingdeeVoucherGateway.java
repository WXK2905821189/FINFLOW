package com.finance.system.statement.kingdee.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.KingdeeConnectionStatus;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeRealModeCondition;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.kingdee.KingdeeVoucherResult;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Real gateway: pushes a validated + approved bank statement to the Kingdee cashier
 * module as a payment bill (EXPENSE &rarr; AP_PAYBILL) or receipt bill (INCOME &rarr;
 * AR_RECEIVEBILL). Decision 2026-09-04: cashier-bill landing point, NOT direct
 * GL_VOUCHER push; the Kingdee-side workflow generates vouchers from these bills.
 *
 * <p>Active only when {@code kingdee.mock-mode=false} AND {@code kingdee.real-enabled=true}
 * AND the kingdee-sdk Maven profile compiled this class (see {@link KingdeeRealModeCondition}).
 */
@Component
@Conditional(KingdeeRealModeCondition.class)
public class RealKingdeeVoucherGateway implements KingdeeVoucherGateway {

    private final KingdeeProperties props;
    private final KingdeeSdkClient client;
    private final KingdeeBillPayloadBuilder payloadBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    public RealKingdeeVoucherGateway(KingdeeProperties props, KingdeeSdkClient client) {
        this.props = props;
        this.client = client;
        this.payloadBuilder = new KingdeeBillPayloadBuilder(props, mapper);
    }

    @Override
    public KingdeeVoucherResult push(StatementRecord statement) {
        String formId;
        try {
            formId = resolveFormId(statement.getDirection());
        } catch (IllegalArgumentException e) {
            return new KingdeeVoucherResult(null, "FAILED", e.getMessage());
        }
        ResolvedCounterparty counterparty;
        try {
            counterparty = resolveCounterparty(statement, formId);
        } catch (BusinessException e) {
            return new KingdeeVoucherResult(null, "FAILED", e.getMessage());
        }
        if (counterparty == null) {
            return new KingdeeVoucherResult(null, "FAILED",
                    "Counterparty not found in Kingdee base data (name=" + statement.getCounterpartyName()
                            + "); auto-provision is off or failed; sync BD_Customer/BD_Supplier"
                            + " first or register a name mapping");
        }
        String payload = payloadBuilder.buildPayload(statement, formId,
                counterparty.number(), counterparty.baseForm());
        String response;
        try {
            response = client.save(formId, payload);
        } catch (BusinessException e) {
            return new KingdeeVoucherResult(null, "FAILED", e.getMessage());
        }
        KingdeeVoucherResult pushed = parseResponse(formId, response);
        if (!"PUSHED".equals(pushed.status())) {
            return pushed;
        }
        return autoAuditIfEnabled(formId, pushed);
    }

    /**
     * Rule-engine journal voucher (GL_VOUCHER) draft save. Payload is prebuilt and
     * balance-checked by KingdeeGlVoucherPayloadBuilder; here we only call Save and parse.
     * Deliberately NO autoAuditIfEnabled: GL_VOUCHER entries are the legal accounting
     * record and are always reviewed by finance on the Kingdee side (T8 decision, and the
     * feasibility report's risk note). FDetailID flex-dimension slots are a known
     * calibration item — first real push may fail with the dimension error text that
     * calibrates the slot mapping (probe-iteration, same as the apiexp joint test).
     */
    @Override
    public KingdeeVoucherResult pushGlVoucher(String payloadJson) {
        String response;
        try {
            response = client.save("GL_VOUCHER", payloadJson);
        } catch (BusinessException e) {
            return new KingdeeVoucherResult(null, "FAILED", e.getMessage());
        }
        return parseResponse("GL_VOUCHER", response);
    }

    /**
     * Read-only connectivity probe (UI "connection test"): one ExecuteBillQuery against
     * BD_Customer. By contract never saves/submits anything — safe against the "do not
     * touch the real books" boundary agreed on 2026-09-09.
     */
    @Override
    public KingdeeConnectionStatus ping() {
        String query = "{\"FormId\":\"BD_Customer\",\"FieldKeys\":\"FNumber,FName\",\"Limit\":1}";
        try {
            String response = client.executeBillQueryJson(query);
            JsonNode rows = mapper.readTree(response);
            int rowCount = rows.isArray() ? rows.size() : 0;
            String sample = rowCount > 0 ? rows.get(0).get(1).asText("") : "";
            return new KingdeeConnectionStatus(true, "REAL",
                    "已连接金蝶（" + hostOf(props.getServerUrl()) + "，账套 " + props.getAcctId()
                            + "，组织 " + props.getOrgNumber() + "；BD_Customer 查询返回 " + rowCount + " 行"
                            + (sample.isBlank() ? "" : "，示例：" + sample) + "）");
        } catch (BusinessException e) {
            return new KingdeeConnectionStatus(false, "REAL", "连接失败：" + e.getMessage());
        } catch (Exception e) {
            return new KingdeeConnectionStatus(false, "REAL",
                    "连接失败：金蝶响应无法解析 " + abbreviate(String.valueOf(e.getMessage())));
        }
    }

    private static String hostOf(String serverUrl) {
        try {
            return java.net.URI.create(serverUrl).getHost();
        } catch (Exception e) {
            return serverUrl;
        }
    }

    /**
     * 只读拉取账套科目表（BD_Account）：FNumber / FName / 必录维度类型编码。
     *
     * <p>2026-09-21 实测该查询在真实账套可用（含点分层编码如 1122.01、以及 1002 的挂账维度 ZDY0001）。
     * 任一行解析失败按「无维度」处理，不因个别科目异常整表失败。</p>
     */
    @Override
    public java.util.List<KingdeeAccountRef> queryAccountCatalog() {
        String query = "{\"FormId\":\"BD_Account\",\"FieldKeys\":\"FNumber,FName,FFlEXITEMPROPERTYID.FNumber\","
                + "\"TopRowCount\":2000}";
        java.util.List<KingdeeAccountRef> catalog = new java.util.ArrayList<>();
        try {
            JsonNode rows = mapper.readTree(client.executeBillQueryJson(query));
            if (!rows.isArray()) {
                return catalog;
            }
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() == 0) {
                    continue;
                }
                String number = row.get(0).asText(null);
                if (number == null || number.isBlank()) {
                    continue;
                }
                String name = row.size() > 1 ? row.get(1).asText(null) : null;
                String dimension = row.size() > 2 && !row.get(2).isNull() ? row.get(2).asText(null) : null;
                catalog.add(new KingdeeAccountRef(number.trim(), name, dimension));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "账套科目表解析失败：" + abbreviate(String.valueOf(e.getMessage())));
        }
        return catalog;
    }

    /**
     * 只读拉取账套银行账号档案（CN_BANKACNT）：FNumber / FName / 所属组织。
     *
     * <p>2026-09-21 实测：账套 142 个档案分属 23 个组织，其中 102 个 FNumber 就是银行账号本体
     * （可直接与 FINFLOW 账户的 account_number 匹配），其余为虚拟账户编码（支付宝邮箱、
     * 薪福通、分贝通、携程商旅等）须人工指定。同一账号可能在不同组织各有一个档案，
     * 故一并取回组织编码供消歧。</p>
     */
    @Override
    public java.util.List<KingdeeBankAccountRef> queryBankAccountCatalog() {
        String query = "{\"FormId\":\"CN_BANKACNT\",\"FieldKeys\":\"FNumber,FName,FCreateOrgId.FNumber\","
                + "\"TopRowCount\":2000}";
        java.util.List<KingdeeBankAccountRef> catalog = new java.util.ArrayList<>();
        try {
            JsonNode rows = mapper.readTree(client.executeBillQueryJson(query));
            if (!rows.isArray()) {
                return catalog;
            }
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() == 0) {
                    continue;
                }
                String number = row.get(0).asText(null);
                if (number == null || number.isBlank()) {
                    continue;
                }
                String name = row.size() > 1 ? row.get(1).asText(null) : null;
                String org = row.size() > 2 && !row.get(2).isNull() ? row.get(2).asText(null) : null;
                catalog.add(new KingdeeBankAccountRef(number.trim(), name, org));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502,
                    "账套银行账号档案解析失败：" + abbreviate(String.valueOf(e.getMessage())));
        }
        return catalog;
    }

    /**
     * 只读批量回查基础资料档案状态（P1-3，2026-09-22）：按 FNumber 过滤一次 BillQuery，
     * 返回「编码 → FDocumentStatus」。查不到的编码不在结果里（导入侧按「档案不存在」标注）。
     *
     * <p>沿用 FIX-006 的状态语义：A=暂存 / B=已提交 / C=已审核；单据只能引用 C。</p>
     */
    @Override
    public java.util.Map<String, String> queryBaseDataDocumentStatus(String formId, java.util.Collection<String> numbers) {
        java.util.Map<String, String> statuses = new java.util.LinkedHashMap<>();
        if (formId == null || formId.isBlank() || numbers == null || numbers.isEmpty()) {
            return statuses;
        }
        // FNumber 可能含引号类字符的场景极少（档案编码），仍按既有口径做转义
        String quoted = numbers.stream()
                .filter(number -> number != null && !number.isBlank())
                .map(number -> "'" + number.trim().replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
        if (quoted.isEmpty()) {
            return statuses;
        }
        String query = "{\"FormId\":\"" + formId + "\",\"FieldKeys\":\"FNumber,FDocumentStatus\","
                + "\"FilterString\":\"FNumber in (" + quoted + ")\",\"Limit\":2000}";
        try {
            JsonNode rows = mapper.readTree(client.executeBillQueryJson(query));
            if (!rows.isArray()) {
                return statuses;
            }
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 2) {
                    continue;
                }
                String number = row.get(0).asText(null);
                String status = row.get(1).isNull() ? null : row.get(1).asText(null);
                if (number != null && !number.isBlank()) {
                    statuses.put(number.trim(), status);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "基础资料状态回查解析失败：" + abbreviate(String.valueOf(e.getMessage())));
        }
        return statuses;
    }

    private String resolveFormId(String direction) {
        if ("EXPENSE".equalsIgnoreCase(direction)) {
            return props.getPayBillFormId();
        }
        if ("INCOME".equalsIgnoreCase(direction)) {
            return props.getReceiveBillFormId();
        }
        throw new IllegalArgumentException("Unknown statement direction: " + direction
                + "; expected INCOME or EXPENSE");
    }

    /** A counterparty number plus the BD_* base-data form it was resolved in. */
    private record ResolvedCounterparty(String number, String baseForm) {
    }

    /**
     * 基础资料引用：编码 + 文档状态（FDocumentStatus；null = 查询未返回该列）。
     * FIX-006 需要状态来判断「已审核(C)」还是「暂存(A)」——暂存档案不能被单据引用。
     */
    private record CounterpartyRef(String number, String documentStatus) {
    }

    /**
     * Resolves the counterparty by exact name lookup (payment bills try suppliers first
     * as the typical outflow counterparty, receipt bills try customers first, both fall
     * back to the other type). On a total miss, auto-provisions the base-data record
     * from the statement name (calibrated 2026-09-07: minimal save = number/name/org;
     * a duplicate save is the idempotency signal) and re-queries.
     */
    private ResolvedCounterparty resolveCounterparty(StatementRecord statement, String formId) {
        String name = statement.getCounterpartyName();
        if (name == null || name.isBlank()) {
            return null;
        }
        String primary = props.getPayBillFormId().equals(formId) ? "BD_Supplier" : "BD_Customer";
        String secondary = "BD_Supplier".equals(primary) ? "BD_Customer" : "BD_Supplier";
        for (String form : new String[] {primary, secondary}) {
            CounterpartyRef ref = queryCounterpartyRef(form, name);
            if (ref != null) {
                // FIX-006：复用历史档案前先确保已审核（暂存档案会让单据报「往来单位是必填项」）
                ensureBaseDataAudited(form, ref.number(), name, ref.documentStatus());
                return new ResolvedCounterparty(ref.number(), form);
            }
        }
        if (Boolean.TRUE.equals(props.getAutoCreateCounterparty())) {
            return autoProvision(primary, name);
        }
        return null;
    }

    /**
     * Creates the base-data record with a deterministic FNumber (prefix + SHA-256 of the
     * name, see {@link KingdeeBillPayloadBuilder#counterpartyNumber}) so re-pushes of the
     * same counterparty converge; a duplicate-number rejection is treated as "already
     * exists" and resolved by re-querying the name.
     */
    private ResolvedCounterparty autoProvision(String formId, String name) {
        String number = payloadBuilder.counterpartyNumber(name);
        String response = client.save(formId, payloadBuilder.buildCounterpartyPayload(formId, name));
        JsonNode status;
        try {
            status = mapper.readTree(response).path("Result").path("ResponseStatus");
        } catch (Exception e) {
            throw new BusinessException(502, formId + " auto-provision: unparseable response "
                    + abbreviate(response));
        }
        if (status.path("IsSuccess").asBoolean(false)) {
            // FIX-006：建档后必须提交+审核，否则档案是暂存态、单据引用时报「往来单位必填」
            ensureBaseDataAudited(formId, number, name, null);
            return new ResolvedCounterparty(number, formId);
        }
        if (isDuplicateRejection(status)) {
            CounterpartyRef existing = queryCounterpartyRef(formId, name);
            if (existing != null) {
                ensureBaseDataAudited(formId, existing.number(), name, existing.documentStatus());
                return new ResolvedCounterparty(existing.number(), formId);
            }
        }
        throw new BusinessException(502, formId + " auto-provision failed: " + firstError(formId, status));
    }

    /**
     * Optional submit+audit after a successful save (kingdee.auto-audit, default off).
     * The bill already exists at this point, so an audit failure never fails the push
     * or triggers a duplicate re-push — the outcome is appended to the result message.
     * Submit/Audit contract calibrated 2026-09-07: api.submit verified on the demo env;
     * audit reached server-side processing (blocked only by the apiexp DB outage).
     */
    private KingdeeVoucherResult autoAuditIfEnabled(String formId, KingdeeVoucherResult pushed) {
        if (!Boolean.TRUE.equals(props.getAutoAudit())) {
            return pushed;
        }
        String numbers = "{\"Numbers\":[\"" + pushed.voucherNo() + "\"]}";
        String message = pushed.message();
        try {
            String submitResp = client.excuteOperation(formId, "Submit", numbers);
            message = append(message, "submit=" + isSuccess(submitResp));
            String auditResp = client.excuteOperation(formId, "Audit", numbers);
            message = append(message, "audit=" + isSuccess(auditResp));
        } catch (BusinessException e) {
            message = append(message, "submit/audit error: " + e.getMessage());
        }
        return new KingdeeVoucherResult(pushed.voucherNo(), pushed.status(), message);
    }

    private boolean isSuccess(String repoRetJson) {
        try {
            return mapper.readTree(repoRetJson).path("Result").path("ResponseStatus")
                    .path("IsSuccess").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String append(String message, String tail) {
        return message + "; " + tail;
    }

    /** Duplicate signal per 2026-09-07 calibration: "编码为...组织内唯一" with FNumber in FieldName. */    private static boolean isDuplicateRejection(JsonNode status) {
        JsonNode errors = status.path("Errors");
        if (!errors.isArray()) {
            return false;
        }
        for (JsonNode error : errors) {
            String field = error.path("FieldName").asText("");
            String message = error.path("Message").asText("");
            if (field.contains("FNumber") || message.contains("唯一")) {
                return true;
            }
        }
        return false;
    }

    /** 金蝶基础资料已审核状态字面量（FDocumentStatus：A=暂存 / B=已提交 / C=已审核）。 */
    private static final String AUDITED = "C";

    /**
     * FIX-006（2026-09-21 真实账套实测）：金蝶业务单据只能引用**已审核**的基础资料。
     * 自动建档原本只做 Save，档案停在「暂存(A)」，收款单保存时被金蝶判定为
     * 「字段"往来单位"是必填项」——不是字段漏传，是档案状态问题（实测证据见
     * docs/pending-fixes.md FIX-006）。此处建档/复用后统一补 Submit + Audit 并回查状态，
     * 无法确认已审核时抛出可执行的错误信息，避免把问题推到金蝶侧报错。
     */
    private void ensureBaseDataAudited(String formId, String number, String name, String knownStatus) {
        if (AUDITED.equalsIgnoreCase(knownStatus)) {
            return;
        }
        String body = "{\"Numbers\":[\"" + number + "\"]}";
        boolean submitted;
        boolean audited;
        try {
            submitted = isSuccess(client.excuteOperation(formId, "Submit", body));
            audited = isSuccess(client.excuteOperation(formId, "Audit", body));
        } catch (BusinessException e) {
            throw new BusinessException(502,
                    baseDataNotAuditedMessage(formId, number, name, false, false, e.getMessage()));
        }
        CounterpartyRef after = queryCounterpartyRef(formId, name);
        if (after != null && AUDITED.equalsIgnoreCase(after.documentStatus())) {
            return;
        }
        throw new BusinessException(502,
                baseDataNotAuditedMessage(formId, number, name, submitted, audited, null));
    }

    private static String baseDataNotAuditedMessage(String formId, String number, String name,
                                                    boolean submitted, boolean audited, String error) {
        return "基础资料未审核，业务单据无法引用：" + formId + " " + number + "（" + name + "）"
                + "；已尝试提交/审核（submit=" + submitted + ", audit=" + audited + "）"
                + (error == null ? "" : "，错误：" + error)
                + "；请先在金蝶打开该档案完成「提交 → 审核」后重试推送"
                + "（若为集团内部主体，建议改为在规则中映射到组织机构维度，不建外部档案）";
    }

    /**
     * 按名称精确查询对手方档案，同时取回文档状态（复用时需要判断是否已审核）。
     * 第二列缺失时 status 为 null（旧桩数据/字段裁剪场景），交由 ensureBaseDataAudited 补审核。
     */
    private CounterpartyRef queryCounterpartyRef(String formId, String name) {
        String query = "{\"FormId\":\"" + formId + "\",\"FieldKeys\":\"FNumber,FDocumentStatus\","
                + "\"FilterString\":\"FName='" + name.replace("'", "''") + "'\",\"Limit\":1}";
        String response = client.executeBillQueryJson(query);
        try {
            JsonNode rows = mapper.readTree(response);
            if (rows.isArray() && rows.size() > 0) {
                JsonNode row = rows.get(0);
                String number = row.get(0).asText(null);
                if (number == null) {
                    return null;
                }
                String status = row.size() > 1 ? row.get(1).asText(null) : null;
                return new CounterpartyRef(number, status);
            }
            return null;
        } catch (Exception e) {
            throw new BusinessException(502, "Unparseable Kingdee base-data response: "
                    + (response == null ? "" : response.substring(0, Math.min(200, response.length()))));
        }
    }

    /** Parses the RepoRet envelope: Result.ResponseStatus.IsSuccess + Result.Number. */
    private KingdeeVoucherResult parseResponse(String formId, String response) {
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode result = root.path("Result");
            JsonNode status = result.path("ResponseStatus");
            boolean success = status.path("IsSuccess").asBoolean(false);
            if (!success) {
                return new KingdeeVoucherResult(null, "FAILED", firstError(formId, status));
            }
            String number = result.path("Number").asText(null);
            return new KingdeeVoucherResult(number, "PUSHED",
                    "Saved to " + formId + (number == null ? "" : " as " + number));
        } catch (Exception e) {
            return new KingdeeVoucherResult(null, "FAILED",
                    "Unparseable Kingdee response: " + abbreviate(response));
        }
    }

    private static String firstError(String formId, JsonNode status) {
        JsonNode errors = status.path("Errors");
        if (errors.isArray() && errors.size() > 0) {
            String msg = errors.get(0).path("Message").asText(null);
            if (msg != null) {
                return formId + ": " + msg;
            }
        }
        return formId + ": " + abbreviate(status.toString());
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
