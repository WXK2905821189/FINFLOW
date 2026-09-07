package com.finance.system.statement.kingdee.real;

import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the cashier-bill save payload for a bank statement record.
 *
 * <p>Field names follow the official API-doc snapshots captured on 2026-09-04
 * (docs/kingdee-openapi/openapi-docs/AP_PAYBILL/Save.json, AR_RECEIVEBILL/Save.json),
 * with mandatory fields calibrated against the demo environment the same day:
 * FCONTACTUNIT/FRECTUNIT (+TYPE) are REQUIRED for payment bills (报错驱动校准),
 * settlement type JSFS04_SYS (电汇) and bank account numbers verified via
 * ExecuteBillQuery on BD_SETTLETYPE / CN_BANKACNT.
 *
 * <p>Known demo-environment limitation (apiexp, NOT a payload problem): any payment
 * bill save that carries FACCOUNTID fails with SQL "列名 'CREDITTYPE'/'ARRIVALDETAILBILL'
 * 无效" — the demo schema lacks columns in the bank-account validation join. Joint test
 * on the real environment (9/11) must verify the FACCOUNTID path.
 *
 * <p>TODO(kingdee-lianTiao): per-account FACCOUNTID mapping (FINFLOW bank account ->
 * CN_BANKACNT FNumber) belongs to the bank account registry; purpose FPURPOSEID left
 * empty (demo env has no such base data exposed; calibrate on real env).
 */
public class KingdeeBillPayloadBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final KingdeeProperties props;
    private final ObjectMapper mapper;

    public KingdeeBillPayloadBuilder(KingdeeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @param counterpartyNumber   the Kingdee base-data FNumber of the counterparty
     *                             (resolved or auto-provisioned by the gateway); must not be null.
     * @param counterpartyBaseType the BD_* form the number was resolved in ("BD_Supplier" or
     *                             "BD_Customer"); drives FCONTACTUNITTYPE/FRECTUNITTYPE, which
     *                             must agree with the referenced number (calibrated 2026-09-07).
     */
    public String buildPayload(StatementRecord record, String formId, String counterpartyNumber,
                               String counterpartyBaseType) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("NeedUpDateFields");
        root.putArray("NeedReturnFields");
        root.put("IsDeleteEntry", "true");
        root.put("IsVerifyBaseDataField", "false");
        root.put("IsEntryBatchFill", "true");
        root.put("ValidateFlag", "true");
        root.put("NumberSearch", "true");
        root.put("IsAutoAdjustField", "false");
        root.put("IsControlPrecision", "false");
        root.put("ValidateRepeatJson", "false");

        ObjectNode model = root.putObject("Model");
        model.put("FDATE", dateFormat(record.getTransactionTime()));
        ObjectNode payOrg = model.putObject("FPAYORGID");
        payOrg.put("FNumber", props.getOrgNumber());
        ObjectNode settleOrg = model.putObject("FSETTLEORGID");
        settleOrg.put("FNumber", props.getOrgNumber());
        ObjectNode currency = model.putObject("FCURRENCYID");
        currency.put("FNumber", props.getCurrencyNumber());
        // Counterparty (calibrated 2026-09-04: FCONTACTUNIT/FRECTUNIT are REQUIRED;
        // 2026-09-07: TYPE must follow the base-data form the number resolved in)
        model.put("FCONTACTUNITTYPE", counterpartyBaseType);
        model.putObject("FCONTACTUNIT").put("FNumber", counterpartyNumber);
        model.put("FRECTUNITTYPE", counterpartyBaseType);
        model.putObject("FRECTUNIT").put("FNumber", counterpartyNumber);
        model.put("FREMARK", record.getSummary());

        ObjectNode entry = buildEntry(record, formId);
        if (props.getPayBillFormId().equals(formId)) {
            ArrayNode entries = model.putArray("FPAYBILLENTRY");
            entries.add(entry);
        } else {
            ArrayNode entries = model.putArray("FRECEIVEBILLENTRY");
            entries.add(entry);
        }
        return root.toString();
    }

    private ObjectNode buildEntry(StatementRecord record, String formId) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("FEntryID", 0);
        ObjectNode settleType = entry.putObject("FSETTLETYPEID");
        settleType.put("FNumber", props.getSettleTypeNumber());
        if (props.getPayBillFormId().equals(formId)) {
            entry.put("FPAYAMOUNTFOR_E", record.getAmount());
            entry.put("FPAYTOTALAMOUNTFOR", record.getAmount());
        } else {
            entry.put("FRECAMOUNTFOR_E", record.getAmount());
            entry.put("FRECTOTALAMOUNTFOR", record.getAmount());
        }
        ObjectNode account = entry.putObject("FACCOUNTID");
        account.put("FNumber", props.getDefaultBankAccountNumber() == null ? "" : props.getDefaultBankAccountNumber());
        entry.put("FOPPOSITECCOUNTNAME", record.getCounterpartyName());
        entry.put("FOPPOSITEBANKACCOUNT", record.getCounterpartyAccount());
        entry.put("FCOMMENT", record.getSummary());
        return entry;
    }

    private static String dateFormat(LocalDateTime time) {
        return time == null ? null : DATE.format(time);
    }

    /**
     * Deterministic counterparty FNumber: prefix + first 10 hex chars of SHA-256(name),
     * so re-pushes of the same counterparty converge on the same record
     * (calibrated 2026-09-07: duplicate-number rejection is the idempotency signal).
     */
    public String counterpartyNumber(String name) {
        return props.getCounterpartyNumberPrefix() + sha256Hex(name).substring(0, 10);
    }

    /**
     * Minimal base-data save payload for auto-provisioning. Calibrated 2026-09-07 against
     * the demo environment: BD_Supplier accepts number/name/org only; BD_Customer requires
     * the customer type (FCustTypeId, demo: KHLB001_SYS).
     */
    public String buildCounterpartyPayload(String formId, String name) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("NeedUpDateFields");
        root.putArray("NeedReturnFields").add("FNumber").add("FName");
        root.put("IsVerifyBaseDataField", "false");
        root.put("NumberSearch", "true");
        root.put("ValidateFlag", "true");
        ObjectNode model = root.putObject("Model");
        model.put("FNumber", counterpartyNumber(name));
        model.put("FName", name);
        model.putObject("FCreateOrgId").put("FNumber", props.getOrgNumber());
        model.putObject("FUseOrgId").put("FNumber", props.getOrgNumber());
        if ("BD_Customer".equals(formId) && props.getCustomerTypeNumber() != null
                && !props.getCustomerTypeNumber().isBlank()) {
            model.putObject("FCustTypeId").put("FNumber", props.getCustomerTypeNumber());
        }
        return root.toString();
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
