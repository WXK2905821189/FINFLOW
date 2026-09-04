package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.aggregation.BankDataAdapterRegistry;
import com.finance.system.bankdata.dto.BankDataRawMessageDetailResponse;
import com.finance.system.bankdata.dto.BankDataRawMessageResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Company-scoped read access to the captured bank responses.
 *
 * <p>Every bank response has been persisted since V4, but nothing could read it back:
 * the trace endpoint deliberately exposes digests only and is keyed to a single task,
 * so "did we actually reach the bank" could not be answered from the product. This
 * service is the read side of the raw message module - browse, open, and evidence.
 *
 * <p>Two rules keep it honest:
 * <ul>
 *   <li>list queries never select {@code payload} - a statement batch is large and a
 *       list only needs the metadata that proves a bank answered;</li>
 *   <li>{@code realDirect} is derived from the adapter registry's REAL-mode adapters,
 *       never from a stored flag, so a simulated run can never masquerade as evidence
 *       of a live connection.</li>
 * </ul>
 */
@Service
public class RawMessageQueryService {

    private static final int MAX_SIZE = 100;

    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncTaskMapper taskMapper;
    private final CompanyScopeService companyScope;
    private final BankDataAdapterRegistry registry;

    public RawMessageQueryService(BankDataRawMessageMapper rawMessageMapper,
                                  BankDataSyncTaskMapper taskMapper,
                                  CompanyScopeService companyScope,
                                  BankDataAdapterRegistry registry) {
        this.rawMessageMapper = rawMessageMapper;
        this.taskMapper = taskMapper;
        this.companyScope = companyScope;
        this.registry = registry;
    }

    public PageResponse<BankDataRawMessageResponse> list(Long userId, int page, int size,
                                                         Long bankAccountId, String taskNo,
                                                         String adapterCode,
                                                         LocalDateTime from, LocalDateTime to) {
        long companyId = companyScope.companyIdForUser(userId);
        int bounded = Math.min(Math.max(1, size), MAX_SIZE);
        List<Long> taskIds = scopedTaskIds(companyId, bankAccountId, taskNo);
        if (taskIds != null && taskIds.isEmpty()) {
            return new PageResponse<>(Math.max(1, page), bounded, 0, List.of());
        }
        LambdaQueryWrapper<BankDataRawMessage> query = new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getCompanyId, companyId)
                .in(taskIds != null, BankDataRawMessage::getTaskId, taskIds)
                .eq(adapterCode != null && !adapterCode.isBlank(), BankDataRawMessage::getAdapterCode,
                        adapterCode == null ? null : adapterCode.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataRawMessage::getReceivedAt, from)
                .le(to != null, BankDataRawMessage::getReceivedAt, to)
                .select(BankDataRawMessage::getId, BankDataRawMessage::getTaskId,
                        BankDataRawMessage::getAdapterCode, BankDataRawMessage::getBankRequestNo,
                        BankDataRawMessage::getContentSha256, BankDataRawMessage::getReceivedAt,
                        BankDataRawMessage::getRetentionUntil, BankDataRawMessage::getPurgedAt)
                .orderByDesc(BankDataRawMessage::getReceivedAt)
                .orderByDesc(BankDataRawMessage::getId);
        Page<BankDataRawMessage> result = rawMessageMapper.selectPage(
                new Page<>(Math.max(1, page), bounded), query);
        Map<Long, BankDataSyncTask> tasks = tasksById(companyId,
                result.getRecords().stream().map(BankDataRawMessage::getTaskId).distinct().toList());
        Set<String> realAdapters = registry.realAdapterCodes();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream()
                        .map(raw -> toResponse(raw, tasks, realAdapters))
                        .toList());
    }

    public BankDataRawMessageDetailResponse detail(Long userId, Long id) {
        long companyId = companyScope.companyIdForUser(userId);
        BankDataRawMessage raw = rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getId, id)
                .eq(BankDataRawMessage::getCompanyId, companyId));
        if (raw == null) {
            throw new BusinessException(404, "Raw bank message not found in the current company");
        }
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, raw.getTaskId())
                .eq(BankDataSyncTask::getCompanyId, companyId));
        String payload = raw.getPayload() == null ? "" : raw.getPayload();
        boolean realDirect = registry.realAdapterCodes().contains(raw.getAdapterCode());
        return new BankDataRawMessageDetailResponse(raw.getId(), raw.getTaskId(),
                task == null ? null : task.getTaskNo(),
                task == null ? null : task.getBankAccountId(),
                raw.getAdapterCode(), raw.getBankRequestNo(), raw.getContentSha256(),
                raw.getReceivedAt(), raw.getRetentionUntil(), raw.getPurgedAt(), realDirect,
                payload, payload.getBytes(StandardCharsets.UTF_8).length);
    }

    private BankDataRawMessageResponse toResponse(BankDataRawMessage raw,
                                                  Map<Long, BankDataSyncTask> tasks,
                                                  Set<String> realAdapters) {
        BankDataSyncTask task = tasks.get(raw.getTaskId());
        return new BankDataRawMessageResponse(raw.getId(), raw.getTaskId(),
                task == null ? null : task.getTaskNo(),
                task == null ? null : task.getBankAccountId(),
                raw.getAdapterCode(), raw.getBankRequestNo(), raw.getContentSha256(),
                raw.getReceivedAt(), raw.getRetentionUntil(), raw.getPurgedAt(),
                realAdapters.contains(raw.getAdapterCode()));
    }

    /** Returns {@code null} when no task filter applies, so callers can skip the IN clause entirely. */
    private List<Long> scopedTaskIds(long companyId, Long bankAccountId, String taskNo) {
        if (bankAccountId == null && (taskNo == null || taskNo.isBlank())) {
            return null;
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .eq(bankAccountId != null, BankDataSyncTask::getBankAccountId, bankAccountId)
                        .eq(taskNo != null && !taskNo.isBlank(), BankDataSyncTask::getTaskNo,
                                taskNo == null ? null : taskNo.trim())
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    private Map<Long, BankDataSyncTask> tasksById(long companyId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .in(BankDataSyncTask::getId, ids)
                        .select(BankDataSyncTask::getId, BankDataSyncTask::getTaskNo,
                                BankDataSyncTask::getBankAccountId))
                .stream().collect(Collectors.toMap(BankDataSyncTask::getId, Function.identity(),
                        (left, right) -> left));
    }
}
