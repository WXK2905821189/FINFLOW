package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.bankdata.adapter.BankAdapterExecutionMode;
import com.finance.system.bankdata.adapter.BankDataAdapter;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataConnectionResponse;
import com.finance.system.bankdata.dto.BankDataProjectionPageResponse;
import com.finance.system.bankdata.dto.BankDataReconciliationResponse;
import com.finance.system.bankdata.dto.BankDataStatementDetailResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.bankdata.dto.BankDataSyncLogResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskDetailResponse;
import com.finance.system.bankdata.dto.BankDataSyncTaskResponse;
import com.finance.system.bankdata.dto.BankSyncJobDetailResponse;
import com.finance.system.bankdata.dto.BankSyncJobEventResponse;
import com.finance.system.bankdata.dto.BankSyncJobResponse;
import com.finance.system.bankdata.dto.BankTaskReconciliationResponse;
import com.finance.system.bankdata.dto.CompanyOptionResponse;
import com.finance.system.common.api.PageResponse;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.common.tenant.CompanyScopeService;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncLog;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.ConnectionProfile;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncLogMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.ConnectionProfileMapper;
import com.finance.system.rbac.RbacService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read side of the bank data pipeline: projections, statements, balances,
 * sync task listings and reconciliation summaries. Every query is company
 * scoped; write paths (triggering and executing synchronizations) stay in
 * {@link BankDataSyncService}.
 */
@Service
public class BankDataQueryService {

    /** 跨公司主体查询权限（V24）：持有者可在余额/流水投影中查看全部 ACTIVE 公司的数据。 */
    public static final String CROSS_COMPANY_PERMISSION = "bankdata:cross-company:view";

    private final CompanyScopeService companyScope;
    private final BankDataSyncTaskMapper taskMapper;
    private final BankDataStatementMapper statementMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataSyncLogMapper logMapper;
    private final ConnectionProfileMapper connectionProfileMapper;
    private final BankAccountMapper bankAccountMapper;
    private final BankDataSyncResponseAssembler responseAssembler;
    private final CompanyMapper companyMapper;
    private final RbacService rbacService;
    /** True when at least one REAL (non-simulated) bank adapter bean is active in this deployment. */
    private final boolean realDirectConnected;
    /** Adapter codes of the active REAL adapters (e.g. CMB); empty when直联未连接. */
    private final List<String> realAdapterCodes;

    public BankDataQueryService(CompanyScopeService companyScope,
                                BankDataSyncTaskMapper taskMapper,
                                BankDataStatementMapper statementMapper,
                                BankDataBalanceMapper balanceMapper,
                                BankDataSyncLogMapper logMapper,
                                ConnectionProfileMapper connectionProfileMapper,
                                BankAccountMapper bankAccountMapper,
                                BankDataSyncResponseAssembler responseAssembler,
                                CompanyMapper companyMapper,
                                RbacService rbacService,
                                List<BankDataAdapter> bankDataAdapters) {
        this.companyScope = companyScope;
        this.taskMapper = taskMapper;
        this.statementMapper = statementMapper;
        this.balanceMapper = balanceMapper;
        this.logMapper = logMapper;
        this.connectionProfileMapper = connectionProfileMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.responseAssembler = responseAssembler;
        this.companyMapper = companyMapper;
        this.rbacService = rbacService;
        List<BankDataAdapter> realAdapters = bankDataAdapters == null ? List.of() : bankDataAdapters.stream()
                .filter(adapter -> adapter.executionMode() == BankAdapterExecutionMode.REAL)
                .toList();
        this.realAdapterCodes = realAdapters.stream().map(BankDataAdapter::adapterCode).toList();
        this.realDirectConnected = !realAdapterCodes.isEmpty();
    }

    public PageResponse<BankSyncJobResponse> listJobs(Long userId, int page, int size, String status, String jobType,
                                                      String connectionCode, String requestId) {
        if (jobType != null && !jobType.isBlank() && !"STATEMENT_PULL".equalsIgnoreCase(jobType)) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        PageResponse<BankDataSyncTaskResponse> tasks = listTasks(userId, page, size, status, null,
                connectionCode, requestId);
        List<BankSyncJobResponse> records = tasks.records().stream()
                .map(task -> responseAssembler.job(task, "STATEMENT_PULL", "MANUAL"))
                .toList();
        return new PageResponse<>(tasks.page(), tasks.size(), tasks.total(), records);
    }

    public BankSyncJobDetailResponse getJob(Long userId, Long taskId) {
        BankDataSyncTaskDetailResponse detail = getTaskDetail(userId, taskId);
        BankDataSyncTaskResponse task = detail.task();
        BankSyncJobResponse job = responseAssembler.job(task, "STATEMENT_PULL", "MANUAL");
        List<BankSyncJobEventResponse> timeline = detail.logs().stream()
                .map(log -> new BankSyncJobEventResponse(log.result(), log.eventType(), log.message(),
                        log.requestId(), log.createdAt()))
                .toList();
        return new BankSyncJobDetailResponse(job, timeline);
    }

    public BankDataProjectionPageResponse<?> queryProjection(Long userId, String resource,
                                                             int page, int size, String status,
                                                             Long bankAccountId, String keyword,
                                                             LocalDateTime from, LocalDateTime to,
                                                             String sourceSystem, String syncJobNo,
                                                             String requestId, Long companyIdFilter) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("balances", "statements").contains(normalized)) {
            throw new BusinessException(404,
                    "银行侧未开通该功能；当前仅支持 balances(余额查询) / statements(流水查询)");
        }
        List<Long> companyIds = projectionScope(userId, companyIdFilter);
        String normalizedSource = sourceSystem == null || sourceSystem.isBlank()
                ? null : sourceSystem.trim().toUpperCase(Locale.ROOT);
        if (normalizedSource != null && !"BANKDATA".equals(normalizedSource)) {
            return emptyProjectionPage(page, size, "模拟/测试数据已下线：仅展示真实银行直联数据（来源 BANKDATA）");
        }
        List<Long> taskIds = scopedTaskIds(companyIds, syncJobNo, requestId);
        if (!realDirectConnected) {
            return notConnectedPage(page, size);
        }
        List<Long> realTasks = realTaskIds(companyIds);
        if (realTasks.isEmpty()) {
            return emptyProjectionPage(page, size, "暂无真实银行数据：请先对银行账户发起一次同步任务");
        }
        taskIds = taskIds.isEmpty() ? realTasks : taskIds.stream().filter(realTasks::contains).toList();
        if (taskIds.isEmpty()) {
            return emptyProjectionPage(page, size, "指定任务不是真实银行直联的同步任务，或没有匹配记录");
        }
        if ("balances".equals(normalized)) {
            PageResponse<BankDataBalanceResponse> balances = listBalances(companyIds, page, size, bankAccountId,
                    status, from, to, taskIds);
            Map<Long, BankDataSyncTask> tasksById = tasksById(companyIds,
                    balances.records().stream().map(BankDataBalanceResponse::taskId).toList());
            Map<Long, String> companyNames = companyNames(companyIds);
            List<BankDataBalanceResponse> records = balances.records().stream()
                    .map(balance -> {
                        BankDataSyncTask task = tasksById.get(balance.taskId());
                        return balance.withLineage(taskNo(task), requestId(task), taskStatus(task))
                                .withCompanyName(companyNames.get(task == null ? null : task.getCompanyId()));
                    })
                    .toList();
            return projectionPage(balances.page(), balances.size(), balances.total(), records,
                    companyIds.get(0), "BANKDATA", balances.records().stream().map(BankDataBalanceResponse::createdAt)
                            .max(LocalDateTime::compareTo).orElse(null));
        }
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .in(BankDataStatement::getCompanyId, companyIds)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .in(BankDataStatement::getTaskId, taskIds)
                .eq(status != null && !status.isBlank(), BankDataStatement::getValidationStatus,
                        status == null ? null : status.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .and(keyword != null && !keyword.isBlank(), nested -> nested
                        .like(BankDataStatement::getStatementNo, keyword.trim())
                        .or().like(BankDataStatement::getSummary, keyword.trim())
                        .or().like(BankDataStatement::getCounterpartyName, keyword.trim())
                        .or().like(BankDataStatement::getBusinessText, keyword.trim())
                        .or().like(BankDataStatement::getRemarkTextClt, keyword.trim())
                        .or().like(BankDataStatement::getYurRef, keyword.trim())
                        .or().like(BankDataStatement::getBillNumber, keyword.trim())
                        .or().like(BankDataStatement::getBankRequestNo, keyword.trim()))
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId);
        Page<BankDataStatement> result = statementMapper.selectPage(
                new Page<>(Math.max(1, page), boundedSize(size)), query);
        Map<Long, BankDataSyncTask> tasksById = tasksById(companyIds,
                result.getRecords().stream().map(BankDataStatement::getTaskId).toList());
        Map<Long, AccountLabel> accountLabels = accountLabels(companyIds,
                result.getRecords().stream().map(BankDataStatement::getBankAccountId).toList());
        Map<Long, String> companyNames = companyNames(companyIds);
        List<BankDataStatementResponse> records = responseAssembler.statements(result.getRecords(), companyIds)
                .stream()
                .map(statement -> {
                    BankDataSyncTask task = tasksById.get(statement.taskId());
                    AccountLabel label = accountLabels.get(statement.bankAccountId());
                    return statement.withLineage(taskNo(task), requestId(task), taskStatus(task),
                            label == null ? null : label.maskedNumber(),
                            label == null ? null : label.name())
                            .withCompanyName(companyNames.get(task == null ? null : task.getCompanyId()));
                })
                .toList();
        return projectionPage(result.getCurrent(), result.getSize(), result.getTotal(), records,
                companyIds.get(0), "BANKDATA", result.getRecords().stream().map(BankDataStatement::getCreatedAt)
                        .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
    }

    /**
     * Our side of a row: the masked account number plus the account name. The bank's statement
     * rows never carry our account name, so it is joined from {@code bank_account} — needed by
     * the export, which mirrors the bank's own export layout including its 账号名称 column.
     */
    private Map<Long, AccountLabel> accountLabels(Collection<Long> companyIds, List<Long> accountIds) {
        List<Long> ids = accountIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return bankAccountMapper.selectList(new LambdaQueryWrapper<BankAccount>()
                        .in(BankAccount::getCompanyId, companyIds)
                        .in(BankAccount::getId, ids)
                        .select(BankAccount::getId, BankAccount::getAccountNumber, BankAccount::getAccountName))
                .stream()
                .collect(Collectors.toMap(BankAccount::getId, account -> new AccountLabel(
                        responseAssembler.maskAccount(account.getAccountNumber()), account.getAccountName())));
    }

    private record AccountLabel(String maskedNumber, String name) {
    }

    /** Hard ceiling on an export: a runaway filter must not stream the whole table out. */
    private static final long EXPORT_LIMIT = 20_000L;
    private static final DateTimeFormatter EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter EXPORT_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter EXPORT_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    /**
     * 招行流水导出件的列序（用户提供的对账样本，36 列）。导出的文件要和银行那份能并排比对，
     * 所以顺序与列名必须与之一致；我们能填的填，银行接口不返回的留空——不编造。
     */
    private static final List<String> STATEMENT_EXPORT_HEADERS = List.of(
            "账号", "账号名称", "币种", "交易日", "交易时间", "起息日", "交易类型",
            "借方金额", "贷方金额", "余额", "摘要", "流水号", "流程实例号", "业务名称",
            "用途", "业务参考号", "业务摘要", "其它摘要",
            "收(付)方分行名", "收(付)方名称", "收(付)方账号", "收(付)方开户行行号",
            "收(付)方开户行名", "收(付)方开户行地址",
            "母(子)公司账号分行名", "母(子)公司账号", "母(子)公司名称",
            "信息标志", "有否附件信息", "冲账标志", "扩展摘要", "交易分析码",
            "票据号", "商务支付订单号", "内部编号", "公司一卡通号");
    private static final List<String> BALANCE_EXPORT_HEADERS = List.of(
            "快照时间", "账号", "账号名称", "币种", "可用余额", "联机余额", "冻结余额",
            "上日余额", "科目", "分行号", "客户关系号", "银行请求号", "同步任务号");
    /** 已知币种代码；其余原样输出（附录码表未随文档镜像，不猜测）。 */
    private static final Map<String, String> CURRENCY_TEXT = Map.of("10", "人民币");

    /**
     * Renders the real bank rows as CSV in the bank's own export layout.
     *
     * <p>The point of the export is reconciliation: the file has to be comparable to the
     * statement the bank sends. That is why 借方金额 / 贷方金额 are split back out here even
     * though storage keeps one signed figure — the bank's file carries two unsigned columns,
     * and 借贷 is derived from {@code signedAmount} falling back to {@code loanCode}.</p>
     */
    public BankDataExport export(Long userId, String resource, String status, Long bankAccountId,
                                 String keyword, LocalDateTime from, LocalDateTime to,
                                 String syncJobNo, String requestId, Long companyIdFilter) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("balances", "statements").contains(normalized)) {
            throw new BusinessException(404, "银行侧未开通该功能；当前仅支持 balances(余额查询) / statements(流水查询)");
        }
        long companyId;
        if (companyIdFilter != null) {
            // 导出永远单公司文件（CSV 布局镜像银行导出，无公司列），跨公司请按公司分别导出。
            if (!hasCrossCompanyPermission(userId)) {
                throw new BusinessException(403, "跨公司导出需要 " + CROSS_COMPANY_PERMISSION + " 权限");
            }
            Company selected = companyMapper.selectById(companyIdFilter);
            if (selected == null) {
                throw new BusinessException(404, "公司不存在");
            }
            companyId = selected.getId();
        } else {
            companyId = companyScope.companyIdForUser(userId);
        }
        List<Long> taskIds = exportScope(companyId, syncJobNo, requestId);
        String stamp = LocalDateTime.now().format(EXPORT_STAMP);
        if ("balances".equals(normalized)) {
            List<BankDataBalance> rows = exportRows(new LambdaQueryWrapper<BankDataBalance>()
                    .eq(BankDataBalance::getCompanyId, companyId)
                    .eq(bankAccountId != null, BankDataBalance::getBankAccountId, bankAccountId)
                    .in(BankDataBalance::getTaskId, taskIds)
                    .eq(status != null && !status.isBlank(), BankDataBalance::getValidationStatus,
                            status == null ? null : status.trim().toUpperCase(Locale.ROOT))
                    .ge(from != null, BankDataBalance::getAsOfTime, from)
                    .le(to != null, BankDataBalance::getAsOfTime, to)
                    .orderByDesc(BankDataBalance::getAsOfTime)
                    .orderByDesc(BankDataBalance::getId), balanceMapper);
            List<List<String>> csv = new ArrayList<>(rows.size());
            for (BankDataBalanceResponse row : responseAssembler.balances(rows, List.of(companyId))) {
                csv.add(List.of(
                        row.asOfTime() == null ? "" : row.asOfTime().format(EXPORT_DAY) + " "
                                + row.asOfTime().format(EXPORT_CLOCK),
                        text(row.bankAccountNo()), text(row.accountMasked() == null ? null : row.bankAccountName()),
                        currencyText(row.vendorCurrencyCode()),
                        BankDataCsvWriter.amount(row.availableBalance()),
                        BankDataCsvWriter.amount(row.onlineBalance()),
                        BankDataCsvWriter.amount(row.frozenBalance()),
                        BankDataCsvWriter.amount(row.previousDayBalance()),
                        text(row.accountItem()), text(row.branchCode()), text(row.customerRelationNo()),
                        text(row.bankRequestNo()), text(row.taskNo())));
            }
            return new BankDataExport("银行余额_" + stamp + ".csv",
                    BankDataCsvWriter.write(BALANCE_EXPORT_HEADERS, csv));
        }
        List<BankDataStatement> rows = exportRows(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .in(BankDataStatement::getTaskId, taskIds)
                .eq(status != null && !status.isBlank(), BankDataStatement::getValidationStatus,
                        status == null ? null : status.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .and(keyword != null && !keyword.isBlank(), nested -> nested
                        .like(BankDataStatement::getStatementNo, keyword.trim())
                        .or().like(BankDataStatement::getSummary, keyword.trim())
                        .or().like(BankDataStatement::getCounterpartyName, keyword.trim())
                        .or().like(BankDataStatement::getBusinessText, keyword.trim())
                        .or().like(BankDataStatement::getRemarkTextClt, keyword.trim())
                        .or().like(BankDataStatement::getYurRef, keyword.trim())
                        .or().like(BankDataStatement::getBillNumber, keyword.trim())
                        .or().like(BankDataStatement::getBankRequestNo, keyword.trim()))
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId), statementMapper);
        Map<Long, BankDataSyncTask> tasksById = tasksById(List.of(companyId),
                rows.stream().map(BankDataStatement::getTaskId).toList());
        Map<Long, AccountLabel> labels = accountLabels(List.of(companyId),
                rows.stream().map(BankDataStatement::getBankAccountId).toList());
        List<List<String>> csv = new ArrayList<>(rows.size());
        for (BankDataStatementResponse row : responseAssembler.statements(rows, List.of(companyId)).stream()
                .map(statement -> {
                    BankDataSyncTask task = tasksById.get(statement.taskId());
                    AccountLabel label = labels.get(statement.bankAccountId());
                    return statement.withLineage(taskNo(task), requestId(task), taskStatus(task),
                            label == null ? null : label.maskedNumber(),
                            label == null ? null : label.name());
                }).toList()) {
            csv.add(statementExportRow(row));
        }
        return new BankDataExport("银行流水_" + stamp + ".csv",
                BankDataCsvWriter.write(STATEMENT_EXPORT_HEADERS, csv));
    }

    /**
     * One row in the bank's own column order. 借方/贷方 are split from the signed amount:
     * a debit (D) is negative, so it lands in 借方金额 as a positive figure with 贷方金额 empty —
     * exactly how the bank's file renders it.
     */
    private List<String> statementExportRow(BankDataStatementResponse row) {
        BigDecimal signed = row.signedAmount();
        boolean debit;
        if (signed != null) {
            debit = signed.signum() < 0;
        } else {
            debit = "D".equalsIgnoreCase(row.loanCode());
        }
        BigDecimal magnitude = signed != null ? signed.abs() : row.amount();
        return List.of(
                text(row.bankAccountNo()),
                text(row.accountName()),
                currencyText(row.vendorCurrencyCode()),
                row.transactionTime() == null ? "" : row.transactionTime().format(EXPORT_DAY),
                row.transactionTime() == null ? "" : row.transactionTime().format(EXPORT_CLOCK),
                row.valueDate() == null ? "" : row.valueDate().format(EXPORT_DAY),
                text(row.textCode()),
                debit ? BankDataCsvWriter.amount(magnitude) : "",
                debit ? "" : BankDataCsvWriter.amount(magnitude),
                BankDataCsvWriter.amount(row.acctOnlineBal()),
                text(row.remarkTextClt()),
                text(row.statementNo()),
                text(row.requestNbr()),
                text(row.businessName()),
                "",                                  // 用途：接口未返回
                text(row.yurRef()),
                text(row.businessText()),
                "",                                  // 其它摘要：接口未返回
                "",                                  // 收(付)方分行名：接口未返回
                text(row.counterpartyName()),
                text(row.ctpAcctNbr()),
                "",                                  // 收(付)方开户行行号：接口未返回
                text(row.ctpBankName()),
                text(row.ctpBankAddress()),
                "",                                  // 母(子)公司账号分行名：接口未返回
                text(row.fatOrSonAccount()),
                text(row.fatOrSonCompanyName()),
                text(row.infoFlag()),
                "",                                  // 有否附件信息：接口未返回
                text(row.reversalFlag()),
                text(row.extendedRemark()),
                "",                                  // 交易分析码：接口未返回
                text(row.billNumber()),
                text(row.mchOrderNbr()),
                "",                                  // 内部编号：接口未返回
                "");                                 // 公司一卡通号：transCardNbr 是记账卡号，语义未确认
    }

    private String currencyText(String vendorCurrencyCode) {
        if (vendorCurrencyCode == null || vendorCurrencyCode.isBlank()) {
            return "";
        }
        return CURRENCY_TEXT.getOrDefault(vendorCurrencyCode.trim(), vendorCurrencyCode.trim());
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    /** Runs an export query with a one-row lookahead so an oversized result is refused, not truncated. */
    private <T> List<T> exportRows(LambdaQueryWrapper<T> query,
                                   com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper) {
        Page<T> page = mapper.selectPage(new Page<>(1, EXPORT_LIMIT + 1), query);
        if (page.getTotal() > EXPORT_LIMIT) {
            throw new BusinessException(400, "导出行数超过上限 " + EXPORT_LIMIT
                    + " 条（当前匹配 " + page.getTotal() + " 条），请缩小时间范围或增加筛选条件");
        }
        return page.getRecords();
    }

    /**
     * Same gating as the on-screen query, but an export has no page to render an empty state on,
     * so an unusable bank link fails loudly instead of returning an empty file.
     */
    private List<Long> exportScope(long companyId, String syncJobNo, String requestId) {
        if (!realDirectConnected) {
            throw new BusinessException(503, "真实银行直联未连接：服务端未启用真实银行适配器，无法导出");
        }
        List<Long> realTasks = realTaskIds(List.of(companyId));
        if (realTasks.isEmpty()) {
            throw new BusinessException(404, "暂无真实银行数据：请先对银行账户发起一次同步任务");
        }
        List<Long> scoped = scopedTaskIds(List.of(companyId), syncJobNo, requestId);
        List<Long> taskIds = scoped.isEmpty() ? realTasks
                : scoped.stream().filter(realTasks::contains).toList();
        if (taskIds.isEmpty()) {
            throw new BusinessException(404, "指定任务不是真实银行直联的同步任务，或没有匹配记录");
        }
        return taskIds;
    }

    /** A rendered CSV export: file name plus body, ready to hand to the response. */
    public record BankDataExport(String filename, String csv) {
    }

    public PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                               Long bankAccountId, LocalDateTime from, LocalDateTime to) {
        return listBalances(userId, page, size, bankAccountId, from, to, null, null);
    }

    public PageResponse<BankDataBalanceResponse> listBalances(Long userId, int page, int size,
                                                               Long bankAccountId, LocalDateTime from, LocalDateTime to,
                                                               String taskNo, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<Long> taskIds = scopedTaskIds(List.of(companyId), taskNo, requestId);
        if ((taskNo != null && !taskNo.isBlank() || requestId != null && !requestId.isBlank())
                && taskIds.isEmpty()) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        return listBalances(List.of(companyId), page, size, bankAccountId, null, from, to, taskIds);
    }

    private PageResponse<BankDataBalanceResponse> listBalances(Collection<Long> companyIds, int page, int size,
                                                                Long bankAccountId, String validationStatus,
                                                                LocalDateTime from, LocalDateTime to,
                                                                List<Long> taskIds) {
        LambdaQueryWrapper<BankDataBalance> query = new LambdaQueryWrapper<BankDataBalance>()
                .in(BankDataBalance::getCompanyId, companyIds)
                .eq(bankAccountId != null, BankDataBalance::getBankAccountId, bankAccountId)
                .in(taskIds != null && !taskIds.isEmpty(), BankDataBalance::getTaskId, taskIds)
                .eq(validationStatus != null && !validationStatus.isBlank(), BankDataBalance::getValidationStatus,
                        validationStatus == null ? null : validationStatus.trim().toUpperCase(Locale.ROOT))
                .ge(from != null, BankDataBalance::getAsOfTime, from)
                .le(to != null, BankDataBalance::getAsOfTime, to)
                .orderByDesc(BankDataBalance::getAsOfTime)
                .orderByDesc(BankDataBalance::getId);
        Page<BankDataBalance> result = balanceMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.balances(result.getRecords(), companyIds));
    }

    private List<Long> scopedTaskIds(Collection<Long> companyIds, String syncJobNo, String requestId) {
        if ((syncJobNo == null || syncJobNo.isBlank()) && (requestId == null || requestId.isBlank())) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .eq(syncJobNo != null && !syncJobNo.isBlank(), BankDataSyncTask::getTaskNo, syncJobNo == null ? null : syncJobNo.trim())
                        .eq(requestId != null && !requestId.isBlank(), BankDataSyncTask::getRequestId, requestId == null ? null : requestId.trim())
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    private Map<Long, BankDataSyncTask> tasksById(Collection<Long> companyIds, List<Long> taskIds) {
        List<Long> ids = taskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .in(BankDataSyncTask::getId, ids)
                        .select(BankDataSyncTask::getId, BankDataSyncTask::getTaskNo,
                                BankDataSyncTask::getRequestId, BankDataSyncTask::getStatus,
                                BankDataSyncTask::getCompanyId))
                .stream().collect(Collectors.toMap(BankDataSyncTask::getId, java.util.function.Function.identity()));
    }

    /** 公司主体显示名（按 id 批量）；用于跨公司投影行的归属列。 */
    private Map<Long, String> companyNames(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return Map.of();
        return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                        .in(Company::getId, companyIds)
                        .select(Company::getId, Company::getName))
                .stream().collect(Collectors.toMap(Company::getId, Company::getName));
    }

    /**
     * Projection scope resolution (2026-09-07 cross-company):
     * explicit companyId filter → requires the cross-company permission (403 otherwise);
     * no filter + permission → every ACTIVE company (group view);
     * no filter + no permission → the user's own company only (unchanged behavior).
     */
    private List<Long> projectionScope(Long userId, Long companyIdFilter) {
        long own = companyScope.companyIdForUser(userId);
        if (companyIdFilter != null) {
            if (!hasCrossCompanyPermission(userId)) {
                throw new BusinessException(403, "跨公司查询需要 " + CROSS_COMPANY_PERMISSION + " 权限");
            }
            Company company = companyMapper.selectById(companyIdFilter);
            if (company == null) {
                throw new BusinessException(404, "公司不存在");
            }
            return List.of(company.getId());
        }
        if (hasCrossCompanyPermission(userId)) {
            return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                            .eq(Company::getStatus, "ACTIVE")
                            .select(Company::getId))
                    .stream().map(Company::getId).toList();
        }
        return List.of(own);
    }

    private boolean hasCrossCompanyPermission(Long userId) {
        return rbacService.permissionCodesForUser(userId).contains(CROSS_COMPANY_PERMISSION);
    }

    /** 公司下拉数据源：跨公司权限者返回全部 ACTIVE 公司；否则仅本公司。 */
    public List<CompanyOptionResponse> companyOptions(Long userId) {
        long own = companyScope.companyIdForUser(userId);
        if (!hasCrossCompanyPermission(userId)) {
            Company company = companyMapper.selectById(own);
            return company == null ? List.of()
                    : List.of(new CompanyOptionResponse(company.getId(), company.getName()));
        }
        return companyMapper.selectList(new LambdaQueryWrapper<Company>()
                        .eq(Company::getStatus, "ACTIVE")
                        .orderByAsc(Company::getId))
                .stream().map(company -> new CompanyOptionResponse(company.getId(), company.getName())).toList();
    }

    private String taskNo(BankDataSyncTask task) {
        return task == null ? null : task.getTaskNo();
    }

    private String requestId(BankDataSyncTask task) {
        return task == null ? null : task.getRequestId();
    }

    private String taskStatus(BankDataSyncTask task) {
        return task == null ? null : task.getStatus();
    }

    private <T> BankDataProjectionPageResponse<T> projectionPage(long page, long size, long total,
                                                                  List<T> records,
                                                                  long companyId, String sourceSystem,
                                                                  LocalDateTime lastSyncedAt) {
        String message = records.isEmpty()
                ? "已连接真实银行直联；当前筛选无数据，请先发起同步或调整条件"
                : "已连接真实银行直联，以下为银行返回的真实数据";
        return new BankDataProjectionPageResponse<>(page, size, total, records, true, "REAL",
                message, null, sourceSystem, lastSyncedAt);
    }

    /** Real bank direct link is connected but nothing matched the criteria (or no sync ran yet). */
    private BankDataProjectionPageResponse<Object> emptyProjectionPage(int page, int size, String message) {
        return BankDataProjectionPageResponse.empty(page, size, "REAL", message, true);
    }

    /** No REAL bank adapter is active in this deployment: the UI must show an explicit red "not connected". */
    private BankDataProjectionPageResponse<Object> notConnectedPage(int page, int size) {
        return BankDataProjectionPageResponse.empty(page, size, "NOT_CONFIGURED",
                "真实银行直联未连接：服务端未启用真实银行适配器（需配置 CMB 直联并开启 BANKDATA_CMB_REAL_ENABLED）",
                false);
    }

    /** Task ids produced by the active REAL adapters only (mock/simulated tasks are never projected). */
    private List<Long> realTaskIds(Collection<Long> companyIds) {
        if (realAdapterCodes.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                        .in(BankDataSyncTask::getCompanyId, companyIds)
                        .in(BankDataSyncTask::getAdapterCode, realAdapterCodes)
                        .select(BankDataSyncTask::getId))
                .stream().map(BankDataSyncTask::getId).toList();
    }

    public List<BankDataConnectionResponse> listConnections(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        return connectionProfileMapper.selectList(new LambdaQueryWrapper<ConnectionProfile>()
                        .eq(ConnectionProfile::getCompanyId, companyId)
                        .orderByAsc(ConnectionProfile::getConnectionCode)
                        .orderByAsc(ConnectionProfile::getId))
                .stream().map(profile -> new BankDataConnectionResponse(profile.getConnectionCode(),
                        profile.getDisplayName(), profile.getProviderType(), Boolean.TRUE.equals(profile.getEnabled()),
                        profile.getStatus(), profile.getLastCheckedAt(),
                        "No credential, private key, token, or certificate content is stored or returned"))
                .toList();
    }

    public PageResponse<BankDataSyncTaskResponse> listTasks(Long userId, int page, int size,
                                                              String status, String adapterCode) {
        return listTasks(userId, page, size, status, adapterCode, null, null);
    }

    private PageResponse<BankDataSyncTaskResponse> listTasks(Long userId, int page, int size,
                                                              String status, String adapterCode,
                                                              String connectionCode, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        Long connectionId = connectionId(companyId, connectionCode);
        LambdaQueryWrapper<BankDataSyncTask> query = new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId)
                .eq(connectionId != null, BankDataSyncTask::getConnectionId, connectionId)
                .eq(connectionCode != null && !connectionCode.isBlank() && connectionId == null,
                        BankDataSyncTask::getConnectionId, -1L)
                .eq(requestId != null && !requestId.isBlank(), BankDataSyncTask::getRequestId,
                        requestId == null ? null : requestId.trim())
                .eq(status != null && !status.isBlank(), BankDataSyncTask::getStatus, normalize(status, null))
                .eq(adapterCode != null && !adapterCode.isBlank(), BankDataSyncTask::getAdapterCode, normalize(adapterCode, null))
                .orderByDesc(BankDataSyncTask::getCreatedAt)
                .orderByDesc(BankDataSyncTask::getId);
        Page<BankDataSyncTask> result = taskMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.tasks(result.getRecords(), companyId));
    }

    private Long connectionId(long companyId, String connectionCode) {
        if (connectionCode == null || connectionCode.isBlank()) return null;
        ConnectionProfile profile = connectionProfileMapper.selectOne(new LambdaQueryWrapper<ConnectionProfile>()
                .eq(ConnectionProfile::getCompanyId, companyId)
                .eq(ConnectionProfile::getConnectionCode, connectionCode.trim()));
        return profile == null ? null : profile.getId();
    }

    public BankDataSyncTaskDetailResponse getTaskDetail(Long userId, Long taskId) {
        return getTaskDetail(taskId, companyScope.companyIdForUser(userId));
    }

    BankDataSyncTaskDetailResponse getTaskDetail(Long taskId, long companyId) {
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, taskId)
                .eq(BankDataSyncTask::getCompanyId, companyId));
        if (task == null) throw new BusinessException(404, "Bank data sync task not found");
        List<BankDataSyncLogResponse> logs = logMapper.selectList(new LambdaQueryWrapper<BankDataSyncLog>()
                        .eq(BankDataSyncLog::getCompanyId, companyId)
                        .eq(BankDataSyncLog::getTaskId, taskId)
                        .orderByAsc(BankDataSyncLog::getCreatedAt)
                        .orderByAsc(BankDataSyncLog::getId))
                .stream().map(responseAssembler::log).toList();
        return new BankDataSyncTaskDetailResponse(responseAssembler.task(task,
                responseAssembler.connectionCode(companyId, task.getConnectionId())), logs);
    }

    public PageResponse<BankDataStatementResponse> listStatements(Long userId, int page, int size,
                                                                    Long bankAccountId, String direction,
                                                                    LocalDateTime from, LocalDateTime to) {
        return listStatements(userId, page, size, bankAccountId, direction, from, to, null, null);
    }

    public PageResponse<BankDataStatementResponse> listStatements(Long userId, int page, int size,
                                                                    Long bankAccountId, String direction,
                                                                    LocalDateTime from, LocalDateTime to,
                                                                    String taskNo, String requestId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<Long> taskIds = scopedTaskIds(List.of(companyId), taskNo, requestId);
        if ((taskNo != null && !taskNo.isBlank() || requestId != null && !requestId.isBlank())
                && taskIds.isEmpty()) {
            return new PageResponse<>(Math.max(1, page), boundedSize(size), 0, List.of());
        }
        LambdaQueryWrapper<BankDataStatement> query = new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId)
                .eq(bankAccountId != null, BankDataStatement::getBankAccountId, bankAccountId)
                .in(!taskIds.isEmpty(), BankDataStatement::getTaskId, taskIds)
                .eq(direction != null && !direction.isBlank(), BankDataStatement::getDirection, normalize(direction, null))
                .ge(from != null, BankDataStatement::getTransactionTime, from)
                .le(to != null, BankDataStatement::getTransactionTime, to)
                .orderByDesc(BankDataStatement::getTransactionTime)
                .orderByDesc(BankDataStatement::getId);
        Page<BankDataStatement> result = statementMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                responseAssembler.statements(result.getRecords(), List.of(companyId)));
    }

    public BankDataStatementDetailResponse getStatement(Long userId, Long statementId) {
        long companyId = companyScope.companyIdForUser(userId);
        BankDataStatement statement = statementMapper.selectOne(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getId, statementId)
                .eq(BankDataStatement::getCompanyId, companyId));
        if (statement == null) throw new BusinessException(404, "Bank data statement not found");
        BankDataSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getId, statement.getTaskId())
                .eq(BankDataSyncTask::getCompanyId, companyId));
        List<BankDataSyncLogResponse> logs = logMapper.selectList(new LambdaQueryWrapper<BankDataSyncLog>()
                        .eq(BankDataSyncLog::getCompanyId, companyId)
                        .eq(BankDataSyncLog::getTaskId, statement.getTaskId())
                        .orderByAsc(BankDataSyncLog::getCreatedAt)
                        .orderByAsc(BankDataSyncLog::getId))
                .stream().map(responseAssembler::log).toList();
        return new BankDataStatementDetailResponse(responseAssembler.statement(statement, companyId),
                responseAssembler.task(task, task == null ? null : responseAssembler.connectionCode(companyId, task.getConnectionId())), logs);
    }

    public BankDataReconciliationResponse reconciliation(Long userId) {
        long companyId = companyScope.companyIdForUser(userId);
        List<BankDataStatement> statements = statementMapper.selectList(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyId));
        List<BankDataSyncTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getCompanyId, companyId));
        BigDecimal total = statements.stream().map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal income = statements.stream().filter(s -> "INCOME".equals(s.getDirection())).map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = statements.stream().filter(s -> "EXPENSE".equals(s.getDirection())).map(BankDataStatement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long duplicates = tasks.stream().mapToLong(task -> task.getDuplicateCount() == null ? 0 : task.getDuplicateCount()).sum();
        long invalid = tasks.stream().mapToLong(task -> task.getInvalidCount() == null ? 0 : task.getInvalidCount()).sum();
        return new BankDataReconciliationResponse(statements.size(), statements.size(), duplicates, invalid,
                total.setScale(2), income.setScale(2), expense.setScale(2), tasks.size(),
                tasks.stream().filter(task -> "FAILED".equals(task.getStatus())).count());
    }

    /**
     * Per-task reconciliation: what the bank attested (Z1 totals on the task row) versus
     * what the platform actually normalized, counted straight from the statement table.
     * Null consistency flags mean the bank reported no totals for that window — they are
     * neither a pass nor a failure.
     */
    public PageResponse<BankTaskReconciliationResponse> taskReconciliation(Long userId, int page, int size) {
        long companyId = companyScope.companyIdForUser(userId);
        Page<BankDataSyncTask> result = taskMapper.selectPage(new Page<>(Math.max(1, page), boundedSize(size)),
                new LambdaQueryWrapper<BankDataSyncTask>()
                        .eq(BankDataSyncTask::getCompanyId, companyId)
                        .orderByDesc(BankDataSyncTask::getId));
        List<BankDataSyncTask> tasks = result.getRecords();
        Map<Long, StatementAggregate> aggregates = aggregatesByTask(companyId,
                tasks.stream().map(BankDataSyncTask::getId).toList());
        List<BankTaskReconciliationResponse> records = tasks.stream()
                .map(task -> toReconciliation(task, aggregates.get(task.getId())))
                .toList();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    private Map<Long, StatementAggregate> aggregatesByTask(long companyId, List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = statementMapper.selectMaps(new QueryWrapper<BankDataStatement>()
                .select("task_id",
                        "SUM(CASE WHEN direction = 'INCOME' THEN 1 ELSE 0 END) AS income_cnt",
                        "SUM(CASE WHEN direction = 'EXPENSE' THEN 1 ELSE 0 END) AS expense_cnt",
                        "SUM(CASE WHEN direction = 'INCOME' THEN amount ELSE 0 END) AS income_amount",
                        "SUM(CASE WHEN direction = 'EXPENSE' THEN amount ELSE 0 END) AS expense_amount")
                .eq("company_id", companyId)
                .in("task_id", taskIds)
                .groupBy("task_id"));
        Map<Long, StatementAggregate> aggregates = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object taskId = column(row, "task_id");
            if (taskId instanceof Number number) {
                aggregates.put(number.longValue(), StatementAggregate.from(row));
            }
        }
        return aggregates;
    }

    /**
     * Case-insensitive column lookup: H2 upper-cases unquoted select aliases (TASK_ID)
     * while MySQL keeps them as written, and the aggregate must read the same on both.
     */
    private static Object column(Map<String, Object> row, String name) {
        Object exact = row.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private BankTaskReconciliationResponse toReconciliation(BankDataSyncTask task, StatementAggregate aggregate) {
        StatementAggregate agg = aggregate == null ? StatementAggregate.EMPTY : aggregate;
        Integer debitNums = task.getDebitNums();
        Integer creditNums = task.getCreditNums();
        Boolean countConsistent = null;
        if (debitNums != null || creditNums != null) {
            countConsistent = num(debitNums) == agg.expenseCount && num(creditNums) == agg.incomeCount;
        }
        Boolean amountConsistent = null;
        if (task.getDebitAmount() != null || task.getCreditAmount() != null) {
            amountConsistent = amountMatches(task.getDebitAmount(), agg.expenseAmount)
                    && amountMatches(task.getCreditAmount(), agg.incomeAmount);
        }
        return new BankTaskReconciliationResponse(task.getId(), task.getTaskNo(), task.getAdapterCode(),
                task.getStatus(), task.getWindowStart(), task.getWindowEnd(),
                debitNums, task.getDebitAmount(), creditNums, task.getCreditAmount(),
                agg.expenseCount, agg.incomeCount,
                agg.expenseAmount.setScale(2), agg.incomeAmount.setScale(2),
                countConsistent, amountConsistent);
    }

    private static long num(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean amountMatches(BigDecimal bankAmount, BigDecimal platformAmount) {
        return bankAmount == null ? platformAmount.compareTo(BigDecimal.ZERO) == 0
                : bankAmount.compareTo(platformAmount) == 0;
    }

    /** Aggregated statement figures for one task, straight from the normalized rows. */
    private record StatementAggregate(long incomeCount, long expenseCount,
                                      BigDecimal incomeAmount, BigDecimal expenseAmount) {

        static final StatementAggregate EMPTY =
                new StatementAggregate(0, 0, BigDecimal.ZERO, BigDecimal.ZERO);

        static StatementAggregate from(Map<String, Object> row) {
            return new StatementAggregate(
                    longValue(columnValue(row, "income_cnt")), longValue(columnValue(row, "expense_cnt")),
                    decimalValue(columnValue(row, "income_amount")), decimalValue(columnValue(row, "expense_amount")));
        }

        private static Object columnValue(Map<String, Object> row, String name) {
            Object exact = row.get(name);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static long longValue(Object value) {
            return value instanceof Number number ? number.longValue() : 0;
        }

        private static BigDecimal decimalValue(Object value) {
            return value instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
        }
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private long boundedSize(int size) {
        return Math.min(100, Math.max(1, size));
    }
}
