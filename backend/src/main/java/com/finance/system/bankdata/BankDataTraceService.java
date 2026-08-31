package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.dto.BankDataRawSummaryResponse;
import com.finance.system.bankdata.dto.BankDataSyncLogResponse;
import com.finance.system.bankdata.dto.BankDataTraceResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chains task number, request id, bank request number, raw summaries, normalized
 * records and projection availability into a single company-scoped lookup.
 * Raw payloads are never returned, only content digests and retention metadata.
 */
@Service
public class BankDataTraceService {

    private static final String NOT_FOUND = "Bank data trace not found";

    private final CompanyScopeService companyScope;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataStatementMapper statementMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankDataSyncResponseAssembler responseAssembler;

    public BankDataTraceService(CompanyScopeService companyScope,
                                BankDataSyncTaskMapper taskMapper,
                                BankDataRawMessageMapper rawMessageMapper,
                                BankDataStatementMapper statementMapper,
                                BankDataBalanceMapper balanceMapper,
                                BankDataSyncLogMapper logMapper,
                                BankDataSyncResponseAssembler responseAssembler) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.rawMessageMapper = rawMessageMapper;
        this.statementMapper = statementMapper;
        this.balanceMapper = balanceMapper;
        this.logMapper = logMapper;
        this.responseAssembler = responseAssembler;
    }

    public BankDataTraceResponse trace(Long userId, String taskNo, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        String safeTaskNo = blankToNull(taskNo);
        String safeRequestId = blankToNull(requestId);
        if (safeTaskNo == null && safeRequestId == null) {
            throw new BusinessException(400, "A task number or request id is required");
        }
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(safeTaskNo != null, BankDataSyncTask::getTaskNo, safeTaskNo == null ? null : safeTaskNo.trim())
                .eq(safeRequestId != null, BankDataSyncTask::getRequestId,
                        safeRequestId == null ? null : safeRequestId.trim())
                .last("LIMIT 1"));
        if (task == null && safeTaskNo == null && safeRequestId != null) {
            // Idempotent reuse (D7-A): a caller-supplied request id on a syncKey hit lives
            // in a TASK_REUSED log row pointing at the reused task, so the chain stays
            // reachable from either id. The log lookup stays company-scoped, so missing
            // and cross-company lookups still collapse into the same 404.
            BankDataSyncLog reuseLog = logMapper.selectOne(new LambdaQueryWrapper<BankDataSyncLog>()
                    .eq(BankDataSyncLog::getCompanyId, companyId)
                    .eq(BankDataSyncLog::getEventType, "TASK_REUSED")
                    .eq(BankDataSyncLog::getRequestId, safeRequestId.trim())
                    .orderByDesc(BankDataSyncLog::getId)
                    .last("LIMIT 1"));
            if (reuseLog != null) {
                task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .eq(BankDataSyncTask::getId, reuseLog.getTaskId())
                        .last("LIMIT 1"));
            }
        }
        if (task == null) {
            // Same response for missing and cross-company lookups so existence is never leaked.
            throw new BusinessException(404, NOT_FOUND);
        }
        List<BankDataRawSummaryResponse> rawSummaries = rawMessageMapper.selectList(
                        new LambdaQueryWrapper<BankDataRawMessage>()
                                .eq(BankDataRawMessage::getCompanyId, companyId)
                                .eq(BankDataRawMessage::getTaskId, task.getId())
                                .select(BankDataRawMessage::getId, BankDataRawMessage::getBankRequestNo,
                                        BankDataRawMessage::getContentSha256, BankDataRawMessage::getAdapterCode,
                                        BankDataRawMessage::getMappingVersion, BankDataRawMessage::getReceivedAt,
                                        BankDataRawMessage::getRetentionUntil)
                                .orderByAsc(BankDataRawMessage::getId))
                .stream().map(raw -> new BankDataRawSummaryResponse(raw.getId(), raw.getBankRequestNo(),
                        raw.getContentSha256(), raw.getAdapterCode(), raw.getMappingVersion(), raw.getReceivedAt(),
                        raw.getRetentionUntil()))
                .toList();
        long statementCount = statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(BankDataStatement::getTaskId, task.getId()));
        long balanceCount = balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, companyId)
                .eq(BankDataBalance::getTaskId, task.getId()));
        List<BankDataSyncLogResponse> logs = logMapper.selectList(new LambdaQueryWrapper<BankDataSyncLog>()
                        .eq(BankDataSyncLog::getCompanyId, companyId)
                        .eq(BankDataSyncLog::getTaskId, task.getId())
                        .orderByAsc(BankDataSyncLog::getCreatedAt)
                        .orderByAsc(BankDataSyncLog::getId))
                .stream().map(responseAssembler::log).toList();
        return new BankDataTraceResponse(
                responseAssembler.task(task, responseAssembler.connectionCode(companyId, task.getConnectionId())),
                rawSummaries, statementCount, balanceCount, statementCount + balanceCount > 0, logs,
                "Only content digests and retention metadata are returned; raw bank payloads stay server-side");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
