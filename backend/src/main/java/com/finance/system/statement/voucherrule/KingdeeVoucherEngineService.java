package com.finance.system.statement.voucherrule;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.KingdeeVoucherRule;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.KingdeeVoucherRuleMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.statement.kingdee.KingdeeVoucherGateway;
import com.finance.system.statement.kingdee.KingdeeVoucherResult;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherEntryDraft;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRulePreview;
import com.finance.system.statement.voucherrule.dto.KingdeeVoucherRuleResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎编排服务（V34 WP-B）：预览解析 + 确认推送。
 *
 * <p>链路（设计文档第三章）：</p>
 * <pre>
 * 标准流水(APPROVED) → 规则解析（唯一命中自动预填 / 多候选·含 MANUAL 行·未命中 → 确认队列）
 *   → 确认页人工复核修正 → GL_VOUCHER payload（借贷合计校验）
 *   → Save 草稿（不 Submit/Audit）→ 回写 statement_record + 审计事件
 * </pre>
 *
 * <p>状态口径：statement_record.pushStatus 写 {@code GL_PUSHED}/{@code GL_FAILED}，
 * 与出纳收付款单链路的 {@code PUSHED}/{@code FAILED} 区分（凭证与收付款单并存，
 * 互不覆盖）；voucher_no 存主凭证号，第二张凭证号（extra_voucher）记入审计与消息。
 * 跨公司口径与既有制证链路一致：voucher:push 持权者即为财务操作角色。</p>
 */
@Service
public class KingdeeVoucherEngineService {

    private final StatementRecordMapper statementMapper;
    private final BankAccountMapper bankAccountMapper;
    private final CompanyMapper companyMapper;
    private final KingdeeVoucherRuleMapper ruleMapper;
    private final KingdeeVoucherRuleService ruleService;
    private final KingdeeVoucherMatchingService matchingService;
    private final KingdeeGlVoucherPayloadBuilder payloadBuilder;
    private final KingdeeVoucherGateway gateway;
    private final StatementAuditEventMapper auditEventMapper;
    private final KingdeeOrgResolver orgResolver;
    private final com.finance.system.closing.ClosingService closingService;

    public KingdeeVoucherEngineService(StatementRecordMapper statementMapper,
                                       BankAccountMapper bankAccountMapper,
                                       CompanyMapper companyMapper,
                                       KingdeeVoucherRuleMapper ruleMapper,
                                       KingdeeVoucherRuleService ruleService,
                                       KingdeeVoucherMatchingService matchingService,
                                       KingdeeGlVoucherPayloadBuilder payloadBuilder,
                                       KingdeeVoucherGateway gateway,
                                       StatementAuditEventMapper auditEventMapper,
                                       KingdeeOrgResolver orgResolver,
                                       com.finance.system.closing.ClosingService closingService) {
        this.statementMapper = statementMapper;
        this.bankAccountMapper = bankAccountMapper;
        this.companyMapper = companyMapper;
        this.ruleMapper = ruleMapper;
        this.ruleService = ruleService;
        this.matchingService = matchingService;
        this.payloadBuilder = payloadBuilder;
        this.gateway = gateway;
        this.auditEventMapper = auditEventMapper;
        this.orgResolver = orgResolver;
        this.closingService = closingService;
    }

    /** Batch preview: rule-engine parse for each statement id (unknown ids are skipped silently). */
    public List<KingdeeVoucherRulePreview> preview(List<Long> statementIds) {
        List<KingdeeVoucherRulePreview> previews = new ArrayList<>(statementIds.size());
        for (Long id : statementIds) {
            StatementRecord statement = statementMapper.selectById(id);
            if (statement == null) {
                continue;
            }
            previews.add(previewOne(statement));
        }
        return previews;
    }

    private KingdeeVoucherRulePreview previewOne(StatementRecord statement) {
        if (!"APPROVED".equals(statement.getReviewStatus())) {
            return new KingdeeVoucherRulePreview(statement.getId(), statement.getStatementNo(),
                    statement.getDirection(), statement.getAmount(),
                    KingdeeVoucherMatchingService.ST_NOT_ELIGIBLE,
                    "流水复核状态为 " + statement.getReviewStatus() + "，规则制证仅受理 APPROVED",
                    List.of());
        }
        return matchingService.preview(statement, loadAccount(statement), loadCompany(statement));
    }

    /**
     * Confirms one candidate rule for one statement and pushes the GL_VOUCHER draft(s).
     *
     * @param manualAmounts MANUAL-line amounts keyed by draft-line index within
     *                      debitLines-then-creditLines of the MAIN voucher (0-based);
     *                      extra-voucher manual lines are keyed the same way after the
     *                      main lines. Required for every manual line, rejected otherwise.
     */
    public KingdeeVoucherPushResult push(Long statementId, int ruleNo,
                                         Map<Integer, BigDecimal> manualAmounts,
                                         Long operatorId) {
        StatementRecord statement = statementMapper.selectById(statementId);
        if (statement == null) {
            throw new BusinessException(404, "流水不存在: " + statementId);
        }
        if (!"APPROVED".equals(statement.getReviewStatus())) {
            throw new BusinessException(400, "流水复核状态为 " + statement.getReviewStatus()
                    + "，规则制证仅受理 APPROVED");
        }
        // W7 账期锁：CLOSED 账期禁止规则制证推送（按流水所属公司+交易时间归月）。
        closingService.ensurePeriodOpen(statement.getCompanyId(), statement.getTransactionTime());
        KingdeeVoucherRule ruleEntity = ruleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KingdeeVoucherRule>()
                        .eq(KingdeeVoucherRule::getRuleNo, ruleNo));
        if (ruleEntity == null) {
            throw new BusinessException(404, "凭证规则不存在: ruleNo=" + ruleNo);
        }
        KingdeeVoucherRuleResponse rule = ruleService.getByRuleNo(ruleNo);

        BankAccount account = loadAccount(statement);
        Company company = loadCompany(statement);
        String orgCode = company == null ? null : orgResolver.resolveOrgCode(company.getName());
        KingdeeVoucherRulePreview.Candidate candidate =
                matchingService.buildCandidate(rule, statement, statement.getAmount(), account, orgCode);

        applyManualAmounts(candidate, manualAmounts);

        String explanation = statement.getSummary() == null || statement.getSummary().isBlank()
                ? rule.businessType()
                : statement.getSummary();
        String voucherNo = pushOne(orgCode, statement, explanation,
                candidate.debitLines(), candidate.creditLines());

        String extraVoucherNo = null;
        if (candidate.extraVoucher() != null) {
            // Same summary by design (直接确认费用): 往来凭证在前，费用凭证在后，顺序固定。
            extraVoucherNo = pushOne(orgCode, statement, explanation,
                    candidate.extraVoucher().debitLines(), candidate.extraVoucher().creditLines());
        }

        recordPush(statement, ruleNo, "GL_PUSHED", voucherNo, extraVoucherNo, operatorId, null);
        return new KingdeeVoucherPushResult(statementId, ruleNo, voucherNo, extraVoucherNo,
                "PUSHED", extraVoucherNo == null
                        ? "GL_VOUCHER 草稿已保存：" + voucherNo
                        : "GL_VOUCHER 草稿已保存：" + voucherNo + " + " + extraVoucherNo + "（第二张）");
    }

    private String pushOne(String orgCode, StatementRecord statement, String explanation,
                           List<KingdeeVoucherEntryDraft> debits, List<KingdeeVoucherEntryDraft> credits) {
        String payload = payloadBuilder.buildPayload(orgCode, statement.getTransactionTime(),
                explanation, debits, credits);
        KingdeeVoucherResult result = gateway.pushGlVoucher(payload);
        if (!"PUSHED".equals(result.status())) {
            recordPush(statement, null, "GL_FAILED", null, null, null, result.message());
            throw new BusinessException(502, "GL_VOUCHER 推送失败：" + result.message());
        }
        return result.voucherNo();
    }

    /**
     * Fills MANUAL-line amounts from the confirmation page. Keys are 0-based indexes over
     * the concatenated debitLines+creditLines of the main voucher, CONTINUING into the
     * extra voucher lines (offset by main-line count) — matching the field order the
     * preview response hands to the UI (WP-C).
     */
    private static void applyManualAmounts(KingdeeVoucherRulePreview.Candidate candidate,
                                           Map<Integer, BigDecimal> manualAmounts) {
        if (manualAmounts == null) {
            manualAmounts = Map.of();
        }
        List<KingdeeVoucherEntryDraft> mainLines = new ArrayList<>(candidate.debitLines());
        mainLines.addAll(candidate.creditLines());
        applyToLines(mainLines, manualAmounts, 0);
        if (candidate.extraVoucher() != null) {
            List<KingdeeVoucherEntryDraft> extraLines = new ArrayList<>(candidate.extraVoucher().debitLines());
            extraLines.addAll(candidate.extraVoucher().creditLines());
            applyToLines(extraLines, manualAmounts, mainLines.size());
        }
    }

    private static void applyToLines(List<KingdeeVoucherEntryDraft> lines,
                                     Map<Integer, BigDecimal> manualAmounts, int indexOffset) {
        for (int i = 0; i < lines.size(); i++) {
            KingdeeVoucherEntryDraft line = lines.get(i);
            if (line.manual()) {
                BigDecimal amount = manualAmounts.get(i + indexOffset);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException(400, "人工分录行（科目 " + line.account()
                            + "）缺少金额，请在确认页填写");
                }
                lines.set(i, new KingdeeVoucherEntryDraft(line.side(), line.account(), line.accountName(),
                        line.dimension(), line.dimensionValue(), amount, line.share(), true));
            }
        }
    }

    private void recordPush(StatementRecord statement, Integer ruleNo, String status,
                            String voucherNo, String extraVoucherNo, Long operatorId, String failure) {
        StatementRecord update = new StatementRecord();
        update.setId(statement.getId());
        update.setPushStatus(status);
        update.setVoucherNo(voucherNo);
        update.setPushedAt(LocalDateTime.now());
        String message = failure != null ? failure
                : "GL_VOUCHER " + (ruleNo == null ? "" : "规则" + ruleNo + " ")
                        + voucherNo + (extraVoucherNo == null ? "" : " / " + extraVoucherNo);
        update.setPushMessage(truncate(message, 500));
        statementMapper.updateById(update);

        StatementAuditEvent event = new StatementAuditEvent();
        event.setCompanyId(statement.getCompanyId());
        event.setStatementId(statement.getId());
        event.setBatchId(statement.getBatchId());
        event.setAction("GL_VOUCHER_PUSH");
        event.setResult("GL_FAILED".equals(status) ? "FAILED" : "SUCCEEDED");
        event.setPreviousStatus(statement.getPushStatus());
        event.setCurrentStatus(status);
        event.setOperatorId(operatorId);
        event.setDetail(truncate(message, 1000));
        auditEventMapper.insert(event);
    }

    private BankAccount loadAccount(StatementRecord statement) {
        return statement.getBankAccountId() == null ? null
                : bankAccountMapper.selectById(statement.getBankAccountId());
    }

    private Company loadCompany(StatementRecord statement) {
        return statement.getCompanyId() == null ? null
                : companyMapper.selectById(statement.getCompanyId());
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** Push outcome for the confirmation page (WP-C). */
    public record KingdeeVoucherPushResult(Long statementId, Integer ruleNo, String voucherNo,
                                           String extraVoucherNo, String status, String message) {
    }
}
