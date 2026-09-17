package com.finance.system.statement.vouchergroup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Voucher center read model (V34 ⑦ 凭证中心): pages {@link StatementRecord} rows that
 * participate in the voucher pipeline, projected into voucher-group semantics.
 *
 * <p>Status buckets mirror the demo contract (待复核/待推送/已推送/已驳回) and cover BOTH
 * push pipelines: the legacy AI-voucher path writes {@code PUSHED}/{@code FAILED} onto
 * {@code push_status}, while the WP-B rule-engine path writes {@code GL_PUSHED}/
 * {@code GL_FAILED}. Bucket filters therefore use IN over both spellings.</p>
 *
 * <p>Company scoping reuses {@link CompanyScopeService} exactly like
 * {@code StatementService.pageStatements} — a voucher row is visible when its company
 * mirror (bank_account.companyId ownership chain resolved at statement level) is in the
 * caller's scope.</p>
 */
@Service
public class VoucherGroupService {

    /** Bucket names accepted by {@code GET /api/statements/voucher-groups?status=} */
    public static final String ST_ALL = "ALL";
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_PUSHED = "PUSHED";
    public static final String ST_FAILED = "FAILED";

    private final StatementRecordMapper statementMapper;
    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final CompanyScopeService companyScope;

    public VoucherGroupService(StatementRecordMapper statementMapper,
                               BankAccountMapper bankAccountMapper,
                               CompanyMapper companyMapper,
                               CompanyScopeService companyScope) {
        this.statementMapper = statementMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.companyScope = companyScope;
    }

    public PageResponse<VoucherGroupResponse> pageGroups(int page, int size, String status,
                                                         String keyword, Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        LambdaQueryWrapper<StatementRecord> query = new LambdaQueryWrapper<StatementRecord>()
                .eq(StatementRecord::getCompanyId, companyId);
        applyStatusFilter(query, status);
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            query.and(q -> q.like(StatementRecord::getStatementNo, like)
                    .or().like(StatementRecord::getVoucherNo, like)
                    .or().like(StatementRecord::getSummary, like)
                    .or().like(StatementRecord::getCounterpartyName, like));
        }
        query.orderByDesc(StatementRecord::getTransactionTime)
                .orderByDesc(StatementRecord::getId);
        Page<StatementRecord> result = statementMapper.selectPage(new Page<>(page, size), query);

        Map<Long, String> companyNames = loadCompanyNames(result.getRecords());
        Map<Long, BankAccount> accounts = loadAccounts(result.getRecords());
        List<VoucherGroupResponse> rows = result.getRecords().stream()
                .map(row -> toResponse(row, companyNames, accounts))
                .toList();
        return new PageResponse<>(page, size, result.getTotal(), rows);
    }

    private static void applyStatusFilter(LambdaQueryWrapper<StatementRecord> query, String status) {
        String bucket = status == null || status.isBlank() ? ST_ALL : status;
        switch (bucket) {
            // 复核状态值域为 PENDING/APPROVED/REJECTED（AI 制证草稿落库即 PENDING）。
            case ST_DRAFT -> query.eq(StatementRecord::getReviewStatus, "PENDING");
            case ST_PENDING -> query.eq(StatementRecord::getReviewStatus, "APPROVED")
                    .and(q -> q.isNull(StatementRecord::getPushStatus)
                            .or().eq(StatementRecord::getPushStatus, "")
                            .or().notIn(StatementRecord::getPushStatus, "PUSHED", "GL_PUSHED", "FAILED", "GL_FAILED"));
            case ST_PUSHED -> query.in(StatementRecord::getPushStatus, "PUSHED", "GL_PUSHED");
            case ST_FAILED -> query.in(StatementRecord::getPushStatus, "FAILED", "GL_FAILED");
            case ST_ALL -> query.and(q -> q.isNotNull(StatementRecord::getVoucherNo)
                    .or(w -> w.eq(StatementRecord::getReviewStatus, "PENDING"))
                    .or(w -> w.eq(StatementRecord::getReviewStatus, "APPROVED")));
            default -> throw new BusinessException(400, "未知的凭证状态筛选：" + bucket);
        }
    }

    private Map<Long, String> loadCompanyNames(List<StatementRecord> rows) {
        Set<Long> ids = rows.stream().map(StatementRecord::getCompanyId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
    }

    private Map<Long, BankAccount> loadAccounts(List<StatementRecord> rows) {
        Set<Long> ids = rows.stream().map(StatementRecord::getBankAccountId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return bankAccountMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(BankAccount::getId, Function.identity(), (a, b) -> a));
    }

    private VoucherGroupResponse toResponse(StatementRecord row, Map<Long, String> companyNames,
                                            Map<Long, BankAccount> accounts) {
        BankAccount account = row.getBankAccountId() == null ? null : accounts.get(row.getBankAccountId());
        String bankLabel = account == null ? null
                : account.getAccountName() + " " + maskTail(account.getAccountNumber());
        String pushStatus = normalizePushStatus(row.getPushStatus());
        return new VoucherGroupResponse(
                row.getId(),
                row.getStatementNo(),
                row.getVoucherNo(),
                row.getTransactionTime(),
                row.getCompanyId() == null ? null : companyNames.get(row.getCompanyId()),
                bankLabel,
                row.getDirection(),
                row.getAmount(),
                row.getCurrency(),
                row.getSummary(),
                row.getReviewStatus(),
                pushStatus,
                row.getPushMessage(),
                row.getPushedAt(),
                1
        );
    }

    /** Raw column may hold either spelling (legacy AI path vs WP-B GL path); normalize to buckets. */
    private static String normalizePushStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "PUSHED", "GL_PUSHED" -> "PUSHED";
            case "FAILED", "GL_FAILED" -> "FAILED";
            default -> raw;
        };
    }

    private static String maskTail(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "**** " + accountNumber.substring(accountNumber.length() - 4);
    }
}
