package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncLogResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Converts bank-data persistence objects to response objects without exposing
 * raw payloads or unmasked account numbers.
 */
@Component
public class BankDataSyncResponseAssembler {

    private final BankDataRawMessageMapper rawMessageMapper;
    private final BankAccountMapper bankAccountMapper;
    private final ConnectionProfileMapper connectionProfileMapper;

    public BankDataSyncResponseAssembler(BankDataRawMessageMapper rawMessageMapper,
                                          BankAccountMapper bankAccountMapper,
                                          ConnectionProfileMapper connectionProfileMapper) {
        this.rawMessageMapper = rawMessageMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.connectionProfileMapper = connectionProfileMapper;
    }

    public String connectionCode(long companyId, Long connectionId) {
        if (connectionId == null) return null;
        ConnectionProfile profile = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getId, connectionId)
                .eq(ConnectionProfile::getCompanyId, companyId));
        return profile == null ? null : profile.getConnectionCode();
    }

    public BankDataSyncTaskResponse task(BankDataSyncTask task, String connectionCode) {
        if (task == null) return null;
        return new BankDataSyncTaskResponse(task.getId(), task.getTaskNo(), task.getAdapterCode(), connectionCode,
                task.getBankAccountId(), task.getRequestId(), task.getBankRequestNo(), task.getStatus(), task.getRawCount(),
                task.getNormalizedCount(), task.getDuplicateCount(), task.getInvalidCount(), task.getErrorMessage(),
                task.getStartedAt(), task.getCompletedAt(), task.getCreatedAt());
    }

    public BankSyncJobResponse job(BankDataSyncTaskResponse task, String jobType, String triggerType) {
        return new BankSyncJobResponse(task.id(), task.taskNo(), jobType, triggerType, task.connectionCode(),
                task.status(), task.requestId(), "raw=" + task.rawCount() + ", normalized=" + task.normalizedCount()
                + ", duplicates=" + task.duplicateCount() + ", invalid=" + task.invalidCount(),
                task.startedAt(), task.completedAt(), task.createdAt());
    }

    public BankDataStatementResponse statement(BankDataStatement statement, long companyId) {
        BankDataRawMessage raw = rawMessage(statement.getRawMessageId(), companyId);
        return new BankDataStatementResponse(statement.getId(), statement.getTaskId(), statement.getRawMessageId(),
                raw == null ? null : raw.getContentSha256(), raw == null ? null : raw.getRetentionUntil(),
                statement.getBankAccountId(), statement.getBankRequestNo(), statement.getStatementNo(),
                statement.getTransactionTime(), statement.getDirection(), statement.getAmount(), statement.getCurrency(),
                statement.getCounterpartyName(), statement.getCounterpartyAccountMasked(), statement.getSummary(),
                statement.getValidationStatus(), statement.getValidationMessage(), statement.getCreatedAt());
    }

    public BankDataBalanceResponse balance(BankDataBalance balance, long companyId) {
        BankDataRawMessage raw = rawMessage(balance.getRawMessageId(), companyId);
        BankAccount account = bankAccountMapper.selectOne(new LambdaQueryWrapper<BankAccount>()
                .eq(BankAccount::getId, balance.getBankAccountId())
                .eq(BankAccount::getCompanyId, companyId));
        return new BankDataBalanceResponse(balance.getId(), balance.getTaskId(), balance.getRawMessageId(),
                raw == null ? null : raw.getContentSha256(), raw == null ? null : raw.getRetentionUntil(),
                balance.getBankAccountId(), maskAccount(account == null ? null : account.getAccountNumber()),
                balance.getBankRequestNo(), balance.getAvailableBalance(), balance.getCurrency(), balance.getAsOfTime(),
                balance.getValidationStatus(), balance.getValidationMessage(), balance.getCreatedAt());
    }

    public BankDataSyncLogResponse log(BankDataSyncLog log) {
        return new BankDataSyncLogResponse(log.getId(), log.getLevel(), log.getEventType(), log.getResult(),
                log.getRequestId(), log.getBankRequestNo(), sanitize(log.getMessage()), log.getCreatedAt());
    }

    public String sanitize(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)(password|secret|token|authorization|private[_ -]?key)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                .replaceAll("(?<!\\d)\\d{8,}(?!\\d)", "****");
    }

    public String maskAccount(String value) {
        if (value == null || value.isBlank()) return null;
        String account = value.trim();
        return account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4);
    }

    private BankDataRawMessage rawMessage(Long rawMessageId, long companyId) {
        return rawMessageMapper.selectOne(new LambdaQueryWrapper<BankDataRawMessage>()
                .eq(BankDataRawMessage::getId, rawMessageId)
                .eq(BankDataRawMessage::getCompanyId, companyId));
    }
}
