package com.finance.system.statement.kingdee.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.StatementRecord;
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
            String number = queryCounterpartyNumber(form, name);
            if (number != null) {
                return new ResolvedCounterparty(number, form);
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
            return new ResolvedCounterparty(number, formId);
        }
        if (isDuplicateRejection(status)) {
            String existing = queryCounterpartyNumber(formId, name);
            if (existing != null) {
                return new ResolvedCounterparty(existing, formId);
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

    private String queryCounterpartyNumber(String formId, String name) {
        String query = "{\"FormId\":\"" + formId + "\",\"FieldKeys\":\"FNumber\","
                + "\"FilterString\":\"FName='" + name.replace("'", "''") + "'\",\"Limit\":1}";
        String response = client.executeBillQueryJson(query);
        try {
            JsonNode rows = mapper.readTree(response);
            if (rows.isArray() && rows.size() > 0) {
                return rows.get(0).get(0).asText(null);
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
