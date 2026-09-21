package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;

/**
 * 解析某笔流水应注入的「银行账号」核算维度值（金蝶 CN_BANKACNT 档案编码）——2026-09-21。
 *
 * <p>维度值必须跟随**该笔流水所属的我方账户**：一家公司有多个银行账户，凭证里
 * 银行存款分录记的是其中某一个；全局一个默认账户只是联调期占位做法。</p>
 *
 * <p>实现放在 {@code com.finance.system.bank}（需要账户档案与金蝶映射服务），
 * 组装器只依赖本接口，避免 statement 侧反向耦合账户模块。</p>
 */
public interface BankAccountDimensionResolver {

    /**
     * @param statement 待制证流水
     * @return 金蝶银行账号档案编码（CN_BANKACNT.FNumber）；流水无账户归属且无兜底配置时返回 null
     * @throws com.finance.system.common.exception.BusinessException 400：账户已归属但未映射金蝶档案
     *         编码（阻断口径——错映射会静默记错账户，宁可先补映射）
     */
    String resolve(StatementRecord statement);
}
