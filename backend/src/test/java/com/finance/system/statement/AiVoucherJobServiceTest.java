package com.finance.system.statement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.AiVoucherJob;
import com.finance.system.domain.mapper.AiVoucherJobMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.dto.AiVoucherBatchResponse;
import com.finance.system.statement.dto.AiVoucherJobResponse;
import com.finance.system.statement.dto.AiVoucherRowResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

/**
 * AI 制证后台任务（V40）单测：提交即返回、后台回写计数、失败可说明、超长结果截断。
 * 后台线程的真实并发在测试里用 Mockito 的 timeout 等待断言，不引入 sleep 循环。
 */
class AiVoucherJobServiceTest {

    private AiVoucherJobMapper jobMapper;
    private BankDataAccountingService accountingService;
    private CompanyScopeService companyScope;
    private RbacService rbacService;
    private AiVoucherJobService service;

    @BeforeEach
    void setUp() {
        jobMapper = mock(AiVoucherJobMapper.class);
        accountingService = mock(BankDataAccountingService.class);
        companyScope = mock(CompanyScopeService.class);
        rbacService = mock(RbacService.class);
        when(companyScope.companyIdForUser(anyLong())).thenReturn(1L);
        when(rbacService.permissionCodesForUser(anyLong())).thenReturn(List.of());
        service = spy(new AiVoucherJobService(jobMapper, accountingService, companyScope,
                rbacService, new ObjectMapper()));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static AiVoucherRowResult row(String outcome, String message) {
        return new AiVoucherRowResult(11L, "SGD001", outcome, "OK", "采购付款", "支付货款",
                "2232", 0.4, null, "NOT_PUSHED", message);
    }

    private static AiVoucherBatchResponse batch(List<AiVoucherRowResult> rows) {
        long draft = rows.stream().filter((r) -> "DRAFT_CREATED".equals(r.outcome())).count();
        long skipped = rows.stream().filter((r) -> r.outcome() != null && r.outcome().startsWith("SKIPPED")).count();
        long failed = rows.stream().filter((r) -> "FAILED".equals(r.outcome())).count();
        return new AiVoucherBatchResponse("BATCH-1", rows.size(), (int) draft, 0, 0, (int) skipped, (int) failed, rows);
    }

    @Test
    void submitRejectsEmptySelection() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(List.of(), 9L));
        assertEquals(400, error.getCode());
    }

    @Test
    void submitReturnsImmediatelyAndJobCompletesInBackground() {
        doReturn(batch(List.of(row("DRAFT_CREATED", "草稿已生成"), row("FAILED", "对手方未建档"))))
                .when(accountingService).createVouchers(any(), anyLong(), eq("DRAFT"));

        AiVoucherJobResponse submitted = service.submit(List.of(11L, 12L), 9L);

        assertTrue(submitted.rows().isEmpty(), "提交时不应带逐行结果（后台还没跑完）；空结果用空数组而非 null，前端免特判");
        assertNull(submitted.finishedAt());
        assertEquals(2, submitted.totalCount());

        ArgumentCaptor<AiVoucherJob> captor = ArgumentCaptor.forClass(AiVoucherJob.class);
        verify(jobMapper, timeout(3000).atLeastOnce()).updateById(captor.capture());
        AiVoucherJob done = captor.getValue();
        assertEquals(AiVoucherJob.STATUS_COMPLETED, done.getStatus());
        assertEquals("BATCH-1", done.getBatchNo());
        assertEquals(1, done.getDraftCount());
        assertEquals(1, done.getFailedCount());
        assertNotNull(done.getRowsJson());
        assertTrue(done.getRowsJson().contains("对手方未建档"), "失败原因必须落库供前端展示");
        assertNotNull(done.getFinishedAt());
    }

    @Test
    void backgroundFailureMarksJobFailedWithActionableMessage() {
        when(accountingService.createVouchers(any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("数据库连接中断"));

        service.submit(List.of(11L), 9L);

        ArgumentCaptor<AiVoucherJob> captor = ArgumentCaptor.forClass(AiVoucherJob.class);
        verify(jobMapper, timeout(3000).atLeastOnce()).updateById(captor.capture());
        AiVoucherJob failed = captor.getValue();
        assertEquals(AiVoucherJob.STATUS_FAILED, failed.getStatus());
        assertTrue(failed.getMessage().contains("数据库连接中断"));
        assertTrue(failed.getMessage().contains("重新选择流水提交"), "任务级失败也要给处置指引");
    }

    @Test
    void oversizedRowsAreTruncatedWithAnExplanatoryRow() {
        List<AiVoucherRowResult> rows = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            rows.add(row("FAILED", "第 " + i + " 行失败原因：金蝶返回了较长的校验错误文本，用于把逐行结果撑到 TEXT 上限"));
        }
        String json = service.serializeRows(rows);

        assertTrue(json.length() <= AiVoucherJobService.ROWS_JSON_MAX_CHARS + 400,
                "截断后长度必须回到 TEXT 安全区，实际 " + json.length());
        assertTrue(json.startsWith("["), "仍是数组，前端无需特判");
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("逐行结果已截断展示"), "必须显式说明结果被截断");
    }

    @Test
    void latestReturnsCompanyScopedJobWithParsedRows() {
        AiVoucherJob job = new AiVoucherJob();
        job.setId(7L);
        job.setCompanyId(1L);
        job.setMode("DRAFT");
        job.setStatus(AiVoucherJob.STATUS_COMPLETED);
        job.setTotalCount(1);
        job.setDraftCount(1);
        job.setPushedCount(0);
        job.setAlreadyCount(0);
        job.setSkippedCount(0);
        job.setFailedCount(0);
        job.setRowsJson("[{\"bankDataStatementId\":11,\"statementNo\":\"SGD001\",\"outcome\":\"DRAFT_CREATED\","
                + "\"aiStatus\":\"OK\",\"aiBusinessCategory\":null,\"aiSuggestedSummary\":null,"
                + "\"aiSuggestedSubject\":null,\"aiConfidence\":null,\"voucherNo\":null,"
                + "\"pushStatus\":null,\"message\":\"草稿已生成，待人工复核后推送\"}]");
        when(jobMapper.selectOne(any(Wrapper.class))).thenReturn(job);

        AiVoucherJobResponse response = service.latest(9L);

        assertEquals(7L, response.id());
        assertEquals("COMPLETED", response.status());
        assertEquals(1, response.rows().size());
        assertEquals("DRAFT_CREATED", response.rows().get(0).outcome());
    }

    @Test
    void latestReturnsNullWhenNoJobYet() {
        when(jobMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertNull(service.latest(9L));
    }

    @Test
    void jobByIdIsHiddenOutsideCompanyScope() {
        AiVoucherJob job = new AiVoucherJob();
        job.setId(7L);
        job.setCompanyId(2L);
        when(jobMapper.selectById(7L)).thenReturn(job);

        BusinessException error = assertThrows(BusinessException.class, () -> service.byId(7L, 9L));
        assertEquals(404, error.getCode());
    }
}
