package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BankDataRawRetentionService {

    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncLogMapper logMapper;
    private final BankDataRetentionProperties properties;

    public BankDataRawRetentionService(BankDataRawMessageMapper rawMessageMapper,
                                       BankDataSyncLogMapper logMapper,
                                       BankDataRetentionProperties properties) {
        this.rawMessageMapper = rawMessageMapper;
        this.logMapper = logMapper;
        this.properties = properties;
    }

    @Transactional
    public int cleanupExpiredRawPayloads() {
        if (!properties.isCleanupEnabled()) {
            return 0;
        }
        return purgeExpiredPayloads(LocalDateTime.now(), properties.boundedBatchLimit(), "scheduled-retention-cleanup");
    }

    @Transactional
    public int purgeExpiredPayloads(LocalDateTime now, int requestedBatchSize, String requestId) {
        int batchSize = Math.max(1, Math.min(1000, requestedBatchSize));
        List<BankDataRawMessage> expired = rawMessageMapper.selectList(new LambdaQueryWrapper<BankDataRawMessage>()
                .isNull(BankDataRawMessage::getPurgedAt)
                .le(BankDataRawMessage::getRetentionUntil, now)
                .orderByAsc(BankDataRawMessage::getRetentionUntil)
                .orderByAsc(BankDataRawMessage::getId)
                .last("LIMIT " + batchSize));
        int purged = 0;
        for (BankDataRawMessage raw : expired) {
            String marker = "[PURGED] sha256=" + raw.getContentSha256()
                    + "; retentionUntil=" + raw.getRetentionUntil();
            int updated = rawMessageMapper.update(null, new LambdaUpdateWrapper<BankDataRawMessage>()
                    .eq(BankDataRawMessage::getId, raw.getId())
                    .isNull(BankDataRawMessage::getPurgedAt)
                    .set(BankDataRawMessage::getPayload, marker)
                    .set(BankDataRawMessage::getPurgedAt, now));
            if (updated > 0) {
                purged++;
                record(raw, requestId, "Raw bank payload purged after retention expiry; digest and references retained");
            }
        }
        return purged;
    }

    private void record(BankDataRawMessage raw, String requestId, String message) {
        BankDataSyncLog log = new BankDataSyncLog();
        log.setCompanyId(raw.getCompanyId());
        log.setTaskId(raw.getTaskId());
        log.setLevel("INFO");
        log.setEventType("RAW_PAYLOAD_PURGED");
        log.setResult("PURGED");
        log.setRequestId(requestId == null || requestId.isBlank() ? "retention-cleanup" : requestId);
        log.setBankRequestNo(raw.getBankRequestNo());
        log.setMessage(message);
        logMapper.insert(log);
    }
}
