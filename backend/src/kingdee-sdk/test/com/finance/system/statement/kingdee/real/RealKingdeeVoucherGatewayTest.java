package com.finance.system.statement.kingdee.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.finance.system.statement.kingdee.KingdeeVoucherResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the real gateway routing + counterparty resolution + response parsing.
 * No network access: {@link KingdeeSdkClient} is mocked. Runs only in builds with the
 * kingdee-sdk profile. Field expectations follow the 2026-09-04 demo-environment calibration.
 */
class RealKingdeeVoucherGatewayTest {

    private KingdeeSdkClient client;
    private RealKingdeeVoucherGateway gateway;
    private KingdeeProperties props;

    @BeforeEach
    void setUp() {
        props = new KingdeeProperties();
        client = mock(KingdeeSdkClient.class);
        gateway = new RealKingdeeVoucherGateway(props, client);
    }

    private StatementRecord record(String direction) {
        StatementRecord r = new StatementRecord();
        r.setId(1L);
        r.setDirection(direction);
        r.setAmount(new BigDecimal("1234.56"));
        r.setCurrency("CNY");
        r.setCounterpartyName("测试对手方");
        r.setCounterpartyAccount("6222000012345678");
        r.setSummary("FINFLOW 联调测试流水");
        r.setTransactionTime(LocalDateTime.of(2026, 9, 4, 10, 0));
        return r;
    }

    private void stubCounterpartyLookup(String number) {
        when(client.executeBillQueryJson(contains("BD_Supplier"))).thenReturn("[[\"" + number + "\"]]");
        when(client.executeBillQueryJson(contains("BD_Customer"))).thenReturn("[]");
    }

    @Test
    void expenseRoutesToPayBill() throws Exception {
        stubCounterpartyLookup("GYS0001");
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0001"));
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("PUSHED", result.status());
        assertEquals("CSPAY0001", result.voucherNo());
    }

    @Test
    void incomeRoutesToReceiveBill() throws Exception {
        when(client.executeBillQueryJson(contains("BD_Customer"))).thenReturn("[[\"KHS0001\"]]");
        when(client.executeBillQueryJson(contains("BD_Supplier"))).thenReturn("[]");
        when(client.save(eq("AR_RECEIVEBILL"), anyString())).thenReturn(successResponse("CSRCV0001"));
        KingdeeVoucherResult result = gateway.push(record("INCOME"));
        assertEquals("PUSHED", result.status());
        assertEquals("CSRCV0001", result.voucherNo());
    }

    @Test
    void unknownCounterpartyFailsClosedWithoutSaving() {
        props.setAutoCreateCounterparty(false);
        when(client.executeBillQueryJson(anyString())).thenReturn("[]");
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("not found in Kingdee base data"));
    }

    @Test
    void autoProvisionsMissingCounterpartyThenPushes() throws Exception {
        // primary (BD_Supplier for EXPENSE) lookup misses, auto-provision succeeds,
        // then the bill save resolves through the created record.
        when(client.executeBillQueryJson(contains("BD_Supplier"))).thenReturn("[]");
        when(client.executeBillQueryJson(contains("BD_Customer"))).thenReturn("[]");
        when(client.save(eq("BD_Supplier"), anyString())).thenReturn(successResponse("FINFLW1234"));
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0002"));

        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));

        assertEquals("PUSHED", result.status());
        assertEquals("CSPAY0002", result.voucherNo());
    }

    @Test
    void duplicateProvisionRejectionIsTreatedAsExisting() throws Exception {
        // 2026-09-07 calibration: re-saving an existing FNumber is rejected with
        // "组织内编码唯一" + FieldName FNumber -> re-query by name must reuse it.
        when(client.executeBillQueryJson(contains("BD_Supplier")))
                .thenReturn("[]", "[[\"FINFLWEXIST01\"]]");
        when(client.executeBillQueryJson(contains("BD_Customer"))).thenReturn("[]");
        when(client.save(eq("BD_Supplier"), anyString())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"FieldName\":\"FNumber,FUseOrgId\","
                        + "\"Message\":\"编码为\\\"FINFLWEXIST01\\\"的供应商，组织内编码唯一\"}],\"MsgCode\":11}}}");
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0003"));

        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));

        assertEquals("PUSHED", result.status());
        assertEquals("CSPAY0003", result.voucherNo());
    }

    @Test
    void failedAutoProvisionFailsThePush() throws Exception {
        when(client.executeBillQueryJson(anyString())).thenReturn("[]");
        when(client.save(eq("BD_Supplier"), anyString())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"FieldName\":\"FName\","
                        + "\"Message\":\"名称不能为空\"}],\"MsgCode\":8}}}");
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("auto-provision failed"));
        assertTrue(result.message().contains("名称不能为空"));
    }

    @Test
    void unknownDirectionFailsClosedWithoutCallingSdk() {
        KingdeeVoucherResult result = gateway.push(record("TRANSFER"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("direction"));
    }

    @Test
    void sdkExceptionBecomesFailedResult() throws Exception {
        stubCounterpartyLookup("GYS0001");
        when(client.save(anyString(), anyString()))
                .thenThrow(new BusinessException(502, "Kingdee save failed: connection refused"));
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("connection refused"));
        assertNull(result.voucherNo());
    }

    @Test
    void businessErrorFromKingdeeBecomesFailedWithFirstMessage() throws Exception {
        stubCounterpartyLookup("GYS0001");
        when(client.save(anyString(), anyString())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"FieldName\":\"FDATE\","
                        + "\"Message\":\"日期不能为空\"}],\"MsgCode\":8}}}");
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("日期不能为空"));
    }

    @Test
    void unparseableResponseBecomesFailed() throws Exception {
        stubCounterpartyLookup("GYS0001");
        when(client.save(anyString(), anyString())).thenReturn("<html>gateway error</html>");
        KingdeeVoucherResult result = gateway.push(record("INCOME"));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("Unparseable"));
    }

    @Test
    void payloadContainsCalibratedMandatoryFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        KingdeeBillPayloadBuilder builder = new KingdeeBillPayloadBuilder(props, mapper);
        String json = builder.buildPayload(record("EXPENSE"), props.getPayBillFormId(), "GYS0001", "BD_Supplier");
        JsonNode root = mapper.readTree(json);
        JsonNode model = root.path("Model");
        assertEquals("2026-09-04", model.path("FDATE").asText());
        assertEquals("100", model.path("FPAYORGID").path("FNumber").asText());
        // 2026-09-04 校准：FCONTACTUNIT/FRECTUNIT(+TYPE) 必填；2026-09-07：TYPE 跟随解析类型
        assertEquals("BD_Supplier", model.path("FCONTACTUNITTYPE").asText());
        assertEquals("GYS0001", model.path("FCONTACTUNIT").path("FNumber").asText());
        assertEquals("BD_Supplier", model.path("FRECTUNITTYPE").asText());
        assertEquals("GYS0001", model.path("FRECTUNIT").path("FNumber").asText());
        JsonNode entry = model.path("FPAYBILLENTRY").get(0);
        assertEquals("JSFS04_SYS", entry.path("FSETTLETYPEID").path("FNumber").asText());
        assertEquals(0, entry.path("FPAYAMOUNTFOR_E").decimalValue().compareTo(new BigDecimal("1234.56")));
        assertEquals("测试对手方", entry.path("FOPPOSITECCOUNTNAME").asText());
        assertEquals("6222000012345678", entry.path("FOPPOSITEBANKACCOUNT").asText());
    }

    @Test
    void receivePayloadUsesReceiveEntry() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        KingdeeBillPayloadBuilder builder = new KingdeeBillPayloadBuilder(props, mapper);
        String json = builder.buildPayload(record("INCOME"), props.getReceiveBillFormId(), "KHS0001", "BD_Customer");
        JsonNode root = mapper.readTree(json);
        JsonNode entry = root.path("Model").path("FRECEIVEBILLENTRY").get(0);
        assertEquals(0, entry.path("FRECAMOUNTFOR_E").decimalValue().compareTo(new BigDecimal("1234.56")));
        assertEquals("BD_Customer", root.path("Model").path("FCONTACTUNITTYPE").asText());
        assertEquals("KHS0001", root.path("Model").path("FCONTACTUNIT").path("FNumber").asText());
    }

    @Test
    void counterpartyPayloadIsDeterministicFromName() throws Exception {
        // 2026-09-07 校准：自动建档 FNumber = 前缀 + SHA-256(name) 前 10 位十六进制
        ObjectMapper mapper = new ObjectMapper();
        KingdeeBillPayloadBuilder builder = new KingdeeBillPayloadBuilder(props, mapper);
        String number = builder.counterpartyNumber("某供应商甲");
        assertTrue(number.startsWith(props.getCounterpartyNumberPrefix()));
        assertEquals(number, builder.counterpartyNumber("某供应商甲"));

        JsonNode model = mapper.readTree(builder.buildCounterpartyPayload("BD_Supplier", "某供应商甲")).path("Model");
        assertEquals(number, model.path("FNumber").asText());
        assertEquals("某供应商甲", model.path("FName").asText());
        assertEquals("100", model.path("FCreateOrgId").path("FNumber").asText());
        assertEquals("100", model.path("FUseOrgId").path("FNumber").asText());
        // BD_Customer 才带类别，BD_Supplier 不带（2026-09-07 实测：供应商可省略）
        JsonNode customerModel = mapper.readTree(
                builder.buildCounterpartyPayload("BD_Customer", "某客户乙")).path("Model");
        assertEquals("KHLB001_SYS", customerModel.path("FCustTypeId").path("FNumber").asText());
    }

    @Test
    void autoAuditDisabledByDefaultSkipsSubmitAudit() throws Exception {
        stubCounterpartyLookup("GYS0001");
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0010"));
        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));
        assertEquals("PUSHED", result.status());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .excuteOperation(anyString(), anyString(), anyString());
    }

    @Test
    void autoAuditEnabledSubmitsAndAuditsByBillNumber() throws Exception {
        props.setAutoAudit(true);
        stubCounterpartyLookup("GYS0001");
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0011"));
        when(client.excuteOperation(eq("AP_PAYBILL"), eq("Submit"), contains("CSPAY0011")))
                .thenReturn(successResponse("CSPAY0011"));
        when(client.excuteOperation(eq("AP_PAYBILL"), eq("Audit"), contains("CSPAY0011")))
                .thenReturn(successResponse("CSPAY0011"));

        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));

        assertEquals("PUSHED", result.status());
        assertEquals("CSPAY0011", result.voucherNo());
        assertTrue(result.message().contains("submit=true"));
        assertTrue(result.message().contains("audit=true"));
    }

    @Test
    void auditFailureKeepsPushedWithOutcomeInMessage() throws Exception {
        // 单据已建成：审核失败不得把推送打成 FAILED（避免重推造成重复单据）
        props.setAutoAudit(true);
        stubCounterpartyLookup("GYS0001");
        when(client.save(eq("AP_PAYBILL"), anyString())).thenReturn(successResponse("CSPAY0012"));
        when(client.excuteOperation(eq("AP_PAYBILL"), eq("Submit"), contains("CSPAY0012")))
                .thenReturn(successResponse("CSPAY0012"));
        when(client.excuteOperation(eq("AP_PAYBILL"), eq("Audit"), contains("CSPAY0012"))).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"Message\":\"审核失败\"}]}}}");

        KingdeeVoucherResult result = gateway.push(record("EXPENSE"));

        assertEquals("PUSHED", result.status());
        assertEquals("CSPAY0012", result.voucherNo());
        assertTrue(result.message().contains("audit=false"));
    }

    private static String successResponse(String number) {
        return "{\"Result\":{\"Id\":100001,\"Number\":\"" + number + "\","
                + "\"ResponseStatus\":{\"IsSuccess\":true,\"MsgCode\":0}}}";
    }
}
