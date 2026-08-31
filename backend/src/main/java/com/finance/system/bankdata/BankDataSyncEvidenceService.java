package com.finance.system.bankdata;

import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Persists evidence outside normalization transactions so failed processing remains traceable. */
@Service
public class BankDataSyncEvidenceService {

    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankDataSyncLogMapper logMapper;

    public BankDataSyncEvidenceService(BankDataRawMessageMapper rawMessageMapper, BankDataSyncLogMapper logMapper) {
        this.rawMessageMapper = rawMessageMapper;
        this.logMapper = logMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankDataRawMessage persistRaw(BankDataSyncTask task, String bankRequestNo, String payload,
                                         String contentSha256, LocalDateTime receivedAt) {
        return persistRaw(task, bankRequestNo, payload, contentSha256, receivedAt,
                task.getMappingVersion() == null ? "LEGACY_V1" : task.getMappingVersion());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankDataRawMessage persistRaw(BankDataSyncTask task, String bankRequestNo, String payload,
                                         String contentSha256, LocalDateTime receivedAt, String mappingVersion) {
        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(task.getCompanyId());
        raw.setTaskId(task.getId());
        raw.setAdapterCode(task.getAdapterCode());
        raw.setMappingVersion(mappingVersion);
        raw.setBankRequestNo(bankRequestNo);
        raw.setContentSha256(contentSha256);
        raw.setPayload(payload);
        raw.setReceivedAt(receivedAt);
        raw.setRetentionUntil(receivedAt.plusDays(30));
        rawMessageMapper.insert(raw);
        record(task, "INFO", "RAW_MESSAGE_PERSISTED", "RECORDED", bankRequestNo,
                "Raw bank data response recorded before normalization");
        return raw;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(BankDataSyncTask task, String message) {
        record(task, "ERROR", "SYNC_FAILED", "FAILED", task.getBankRequestNo(), message);
    }

    private void record(BankDataSyncTask task, String level, String eventType, String result,
                        String bankRequestNo, String message) {
        BankDataSyncLog log = new BankDataSyncLog();
        log.setCompanyId(task.getCompanyId());
        log.setTaskId(task.getId());
        log.setLevel(level);
        log.setEventType(eventType);
        log.setResult(result);
        log.setRequestId(task.getRequestId());
        log.setBankRequestNo(bankRequestNo);
        log.setMessage(message == null || message.length() <= 500 ? message : message.substring(0, 500));
        logMapper.insert(log);
    }
}
