package com.finance.system.bankdata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.bankdata.dto.BankDataBalanceResponse;
import com.finance.system.bankdata.dto.BankDataStatementResponse;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.common.tenant.CompanyScopeService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV export of the real bank rows in the bank's own export layout (reconciliation use).
 * Split out of {@link BankDataQueryService}: the export block carries its own column
 * contract (36-column bank sample) and ceiling logic, none of which the on-screen
 * projection needs.
 */
@Service
public class BankDataExportService {

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

    private final BankDataTaskScope taskScope;
    private final CompanyScopeService companyScope;
    private final CompanyMapper companyMapper;
    private final BankDataStatementMapper statementMapper;
    private final BankDataBalanceMapper balanceMapper;
    private final BankDataSyncResponseAssembler responseAssembler;

    public BankDataExportService(BankDataTaskScope taskScope,
                                 CompanyScopeService companyScope,
                                 CompanyMapper companyMapper,
                                 BankDataStatementMapper statementMapper,
                                 BankDataBalanceMapper balanceMapper,
                                 BankDataSyncResponseAssembler responseAssembler) {
        this.taskScope = taskScope;
        this.companyScope = companyScope;
        this.companyMapper = companyMapper;
        this.statementMapper = statementMapper;
        this.balanceMapper = balanceMapper;
        this.responseAssembler = responseAssembler;
    }

    /**
     * Renders the real bank rows as CSV in the bank's own export layout.
     *
     * <p>The point of the export is reconciliation: the file has to be comparable to the
     * statement the bank sends. That is why 借方金额 / 贷方金额 are split back out here even
     * though storage keeps one signed figure — the bank's file carries two unsigned columns,
     * and 借贷 is derived from {@code signedAmount} falling back to {@code loanCode}.</p>
     */
    public BankDataExport export(Long userId, String resource, String status, List<Long> bankAccountIds,
                                 String keyword, LocalDateTime from, LocalDateTime to,
                                 String syncJobNo, String requestId, Long companyIdFilter) {
        String normalized = resource == null ? "" : resource.trim().toLowerCase(Locale.ROOT);
        if (!List.of("balances", "statements").contains(normalized)) {
            throw new BusinessException(404, "银行侧未开通该功能；当前仅支持 balances(余额查询) / statements(流水查询)");
        }
        long companyId;
        if (companyIdFilter != null) {
            // 导出永远单公司文件（CSV 布局镜像银行导出，无公司列），跨公司请按公司分别导出。
            if (!taskScope.hasCrossCompanyPermission(userId)) {
                throw new BusinessException(403, "跨公司导出需要 " + BankDataTaskScope.CROSS_COMPANY_PERMISSION + " 权限");
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
                    .in(bankAccountIds != null && !bankAccountIds.isEmpty(), BankDataBalance::getBankAccountId, bankAccountIds)
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
                .in(bankAccountIds != null && !bankAccountIds.isEmpty(), BankDataStatement::getBankAccountId, bankAccountIds)
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
        Map<Long, com.finance.system.domain.entity.BankDataSyncTask> tasksById = taskScope.tasksById(List.of(companyId),
                rows.stream().map(BankDataStatement::getTaskId).toList());
        Map<Long, BankDataTaskScope.AccountLabel> labels = taskScope.accountLabels(List.of(companyId),
                rows.stream().map(BankDataStatement::getBankAccountId).toList());
        List<List<String>> csv = new ArrayList<>(rows.size());
        for (BankDataStatementResponse row : responseAssembler.statements(rows, List.of(companyId)).stream()
                .map(statement -> {
                    var task = tasksById.get(statement.taskId());
                    BankDataTaskScope.AccountLabel label = labels.get(statement.bankAccountId());
                    return statement.withLineage(taskScope.taskNo(task), taskScope.requestId(task), taskScope.taskStatus(task),
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
        if (!taskScope.realDirectConnected()) {
            throw new BusinessException(503, "真实银行直联未连接：服务端未启用真实银行适配器，无法导出");
        }
        List<Long> realTasks = taskScope.realTaskIds(List.of(companyId));
        if (realTasks.isEmpty()) {
            throw new BusinessException(404, "暂无真实银行数据：请先对银行账户发起一次同步任务");
        }
        List<Long> scoped = taskScope.scopedTaskIds(List.of(companyId), syncJobNo, requestId);
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
}
