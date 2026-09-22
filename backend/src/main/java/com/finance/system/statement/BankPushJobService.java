package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankPushJob;
import com.finance.system.domain.mapper.BankPushJobMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.statement.dto.PushBatchResult;
import com.finance.system.statement.dto.PushJobResponse;
import com.finance.system.statement.dto.PushRowResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一键推送至金蝶异步任务（W16-A1，2026-09-22 用户拍板）：
 * 「一键推送至金蝶」是后台任务，不应阻塞操作界面；进度与结果统一在「凭证中心」看，
 * 摘要为「自动推 X / 问题凭证 Y / 跳过 Z」。
 *
 * <p>设计取舍（沿用 V40 异步基建模式）：
 * <ul>
 *   <li>任务行先落库（单条 insert，自动提交）再投递后台线程，避免后台线程读不到任务行；</li>
 *   <li>逐行结果 JSON 存 TEXT：写入前按 {@link #ROWS_JSON_MAX_CHARS} 截断并补一条说明行，
 *       避免超大批次把任务行写失败（超长时任务仍完成，只是结果列表不全）；</li>
 *   <li>后台线程出错 → 任务标 FAILED + message 记原因（前端在凭证中心直接展示），
 *       单行失败不影响任务状态（行级 outcome=PROBLEM_* 已带 message）。</li>
 * </ul></p>
 */
@Service
public class BankPushJobService {

    private static final Logger log = LoggerFactory.getLogger(BankPushJobService.class);

    /** rows_json 是 MySQL/H2 的 TEXT（约 64KB）：留余量后按字符截断。 */
    static final int ROWS_JSON_MAX_CHARS = 60000;

    private final BankPushJobMapper jobMapper;
    private final BankDataPushService pushService;
    private final CompanyScopeService companyScope;
    private final RbacService rbacService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public BankPushJobService(BankPushJobMapper jobMapper,
                              BankDataPushService pushService,
                              CompanyScopeService companyScope,
                              RbacService rbacService,
                              ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.pushService = pushService;
        this.companyScope = companyScope;
        this.rbacService = rbacService;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "bank-push-job-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 提交推送任务：落任务行后立即返回，实际处理在后台线程。
     * 返回任务号供前端提示「已提交 N 条」，进度在凭证中心轮询。
     */
    public PushJobResponse submit(List<Long> statementIds, Long operatorId) {
        if (statementIds == null || statementIds.isEmpty()) {
            throw new BusinessException(400, "请选择要推送的银行流水");
        }
        List<Long> ids = statementIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请选择要推送的银行流水");
        }
        long companyId = companyScope.companyIdForUser(operatorId);

        BankPushJob job = new BankPushJob();
        job.setCompanyId(companyId);
        job.setStatus(BankPushJob.STATUS_RUNNING);
        job.setTotalCount(ids.size());
        job.setPushedCount(0);
        job.setProblemCount(0);
        job.setSkippedCount(0);
        job.setAlreadyCount(0);
        job.setCreatedBy(operatorId);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);

        executor.submit(() -> run(job.getId(), ids, operatorId));
        return toResponse(job);
    }

    /** 后台执行体：跑规则编排，完成后回写计数与逐行结果。 */
    void run(Long jobId, List<Long> statementIds, Long operatorId) {
        try {
            PushBatchResult batch = pushService.pushToKingdee(statementIds, operatorId);
            BankPushJob update = new BankPushJob();
            update.setId(jobId);
            update.setStatus(BankPushJob.STATUS_COMPLETED);
            update.setBatchNo(batch.batchNo());
            update.setTotalCount(batch.totalCount());
            update.setPushedCount(batch.pushedCount());
            update.setProblemCount(batch.problemCount());
            update.setSkippedCount(batch.skippedCount());
            update.setAlreadyCount(batch.alreadyCount());
            update.setRowsJson(serializeRows(batch.rows()));
            update.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(update);
        } catch (Throwable e) {
            log.warn("一键推送任务 {} 执行失败：{}", jobId, e.getMessage(), e);
            BankPushJob update = new BankPushJob();
            update.setId(jobId);
            update.setStatus(BankPushJob.STATUS_FAILED);
            update.setMessage(rootMessage(e));
            update.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(update);
        }
    }

    /** 当前公司最近一个推送任务（前端轮询；无任务返回 null）。 */
    @Transactional(readOnly = true)
    public PushJobResponse latest(Long operatorId) {
        long companyId = companyScope.companyIdForUser(operatorId);
        BankPushJob job = jobMapper.selectOne(new LambdaQueryWrapper<BankPushJob>()
                .eq(BankPushJob::getCompanyId, companyId)
                .orderByDesc(BankPushJob::getCreatedAt)
                .orderByDesc(BankPushJob::getId)
                .last("LIMIT 1"));
        return job == null ? null : toResponse(job);
    }

    /**
     * 逐轮询任务状态（按任务号；跨公司需 bankdata:cross-company:view）。
     * 公司域口径与其余银行数据端点一致。
     */
    @Transactional(readOnly = true)
    public PushJobResponse byId(Long jobId, Long operatorId) {
        BankPushJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(404, "推送任务不存在");
        }
        long ownCompanyId = companyScope.companyIdForUser(operatorId);
        boolean crossCompany = rbacService.permissionCodesForUser(operatorId)
                .contains("bankdata:cross-company:view");
        if (job.getCompanyId() != null && job.getCompanyId() != ownCompanyId && !crossCompany) {
            throw new BusinessException(404, "推送任务不存在或不在当前公司域内");
        }
        return toResponse(job);
    }

    private PushJobResponse toResponse(BankPushJob job) {
        return new PushJobResponse(
                job.getId(), job.getStatus(), job.getBatchNo(),
                job.getTotalCount(), job.getPushedCount(), job.getProblemCount(),
                job.getSkippedCount(), job.getAlreadyCount(),
                job.getCreatedAt(), job.getFinishedAt(), job.getMessage(), parseRows(job.getRowsJson()));
    }

    /** 逐行结果序列化（含超长截断）；包内可见，便于单测直接验证截断语义。 */
    String serializeRows(List<PushRowResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
        if (json.length() <= ROWS_JSON_MAX_CHARS) {
            return json;
        }
        // 超长批次：保留前 N 行的完整 JSON，并追加一条说明行（返回对象仍是数组，前端渲染不用特判）
        int cut = json.lastIndexOf("},{", ROWS_JSON_MAX_CHARS);
        String head = cut > 0 ? json.substring(0, cut + 1) : "[";
        String note = "{\"bankDataStatementId\":null,\"statementNo\":\"--\",\"outcome\":\"SKIPPED\","
                + "\"ruleNo\":null,\"voucherNo\":null,\"pushStatus\":null,"
                + "\"message\":\"任务行数较多，逐行结果已截断展示；以凭证中心列表为准\"}";
        return head + "," + note + "]";
    }

    private List<PushRowResult> parseRows(String rowsJson) {
        if (rowsJson == null || rowsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rowsJson, new TypeReference<List<PushRowResult>>() {
            });
        } catch (Exception e) {
            log.warn("推送任务逐行结果解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return "推送任务执行失败：" + (message == null || message.isBlank()
                ? cur.getClass().getSimpleName() : message)
                + "；可重新选择流水提交，或查看服务端日志 bank-push-job 关键字。";
    }
}
