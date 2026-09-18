package com.finance.system.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.domain.entity.AccountingMapping;
import com.finance.system.domain.entity.AiCallLog;
import com.finance.system.domain.entity.PaymentTransfer;
import com.finance.system.domain.entity.PaymentTransferAuditEvent;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.SystemAuditEvent;
import com.finance.system.domain.entity.ValidationRule;
import com.finance.system.domain.mapper.AccountingMappingMapper;
import com.finance.system.domain.mapper.AiCallLogMapper;
import com.finance.system.domain.mapper.PaymentTransferAuditEventMapper;
import com.finance.system.domain.mapper.PaymentTransferMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.SystemAuditEventMapper;
import com.finance.system.domain.mapper.ValidationRuleMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V36-W5（需求5）：物理删除账号前的引用检查，V34 拍板③「物理删除 + 外键检查」口径。
 *
 * <p>只统计会阻塞物理删除的业务/审计引用；角色绑定（sys_user_role）、登录会话（auth_session）、
 * 表格偏好（account_preference）属从属数据，随账号一起删除，不在此列。</p>
 *
 * <p>登录成功本身会写 system_audit（LOGIN_SUCCESS），因此登录过的账号必然有审计引用——
 * 这是有意行为：删除仅用于清理从未真正使用的误建账号，有活动痕迹的账号走「停用」
 * （停用保留全部历史且立即失效会话）。</p>
 *
 * <p>付款申请按 V6 的三个外键口径检查（created_by / approved_by / executed_by），
 * resolved_by 虽无外键也一并计入，避免后续补外键时再爆雷。</p>
 */
@Component
public class UserReferenceChecker {

    private final SystemAuditEventMapper systemAuditEventMapper;
    private final StatementImportBatchMapper statementImportBatchMapper;
    private final StatementAuditEventMapper statementAuditEventMapper;
    private final PaymentTransferMapper paymentTransferMapper;
    private final PaymentTransferAuditEventMapper paymentTransferAuditEventMapper;
    private final ValidationRuleMapper validationRuleMapper;
    private final AccountingMappingMapper accountingMappingMapper;
    private final AiCallLogMapper aiCallLogMapper;

    public UserReferenceChecker(SystemAuditEventMapper systemAuditEventMapper,
                                StatementImportBatchMapper statementImportBatchMapper,
                                StatementAuditEventMapper statementAuditEventMapper,
                                PaymentTransferMapper paymentTransferMapper,
                                PaymentTransferAuditEventMapper paymentTransferAuditEventMapper,
                                ValidationRuleMapper validationRuleMapper,
                                AccountingMappingMapper accountingMappingMapper,
                                AiCallLogMapper aiCallLogMapper) {
        this.systemAuditEventMapper = systemAuditEventMapper;
        this.statementImportBatchMapper = statementImportBatchMapper;
        this.statementAuditEventMapper = statementAuditEventMapper;
        this.paymentTransferMapper = paymentTransferMapper;
        this.paymentTransferAuditEventMapper = paymentTransferAuditEventMapper;
        this.validationRuleMapper = validationRuleMapper;
        this.accountingMappingMapper = accountingMappingMapper;
        this.aiCallLogMapper = aiCallLogMapper;
    }

    /** 返回「引用来源 → 行数」；空 Map = 无引用，可物理删除。键序固定便于文案稳定。 */
    public Map<String, Long> countReferences(Long userId) {
        Map<String, Long> references = new LinkedHashMap<>();
        add(references, "系统审计", systemAuditEventMapper.selectCount(
                new LambdaQueryWrapper<SystemAuditEvent>().eq(SystemAuditEvent::getActorId, userId)));
        add(references, "导入批次", statementImportBatchMapper.selectCount(
                new LambdaQueryWrapper<StatementImportBatch>().eq(StatementImportBatch::getCreatedBy, userId)));
        add(references, "流水操作审计", statementAuditEventMapper.selectCount(
                new LambdaQueryWrapper<StatementAuditEvent>().eq(StatementAuditEvent::getOperatorId, userId)));
        add(references, "付款申请", paymentTransferMapper.selectCount(
                new LambdaQueryWrapper<PaymentTransfer>()
                        .eq(PaymentTransfer::getCreatedBy, userId)
                        .or().eq(PaymentTransfer::getApprovedBy, userId)
                        .or().eq(PaymentTransfer::getExecutedBy, userId)
                        .or().eq(PaymentTransfer::getResolvedBy, userId)));
        add(references, "付款审计", paymentTransferAuditEventMapper.selectCount(
                new LambdaQueryWrapper<PaymentTransferAuditEvent>().eq(PaymentTransferAuditEvent::getOperatorId, userId)));
        add(references, "校验规则", validationRuleMapper.selectCount(
                new LambdaQueryWrapper<ValidationRule>().eq(ValidationRule::getCreatedBy, userId)));
        add(references, "记账映射", accountingMappingMapper.selectCount(
                new LambdaQueryWrapper<AccountingMapping>().eq(AccountingMapping::getCreatedBy, userId)));
        add(references, "AI 调用", aiCallLogMapper.selectCount(
                new LambdaQueryWrapper<AiCallLog>().eq(AiCallLog::getUserId, userId)));
        return references;
    }

    private void add(Map<String, Long> references, String label, Long count) {
        if (count != null && count > 0) {
            references.put(label, count);
        }
    }
}
