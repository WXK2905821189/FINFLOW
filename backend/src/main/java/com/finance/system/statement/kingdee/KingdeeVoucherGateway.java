package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;

public interface KingdeeVoucherGateway {

    KingdeeVoucherResult push(StatementRecord statement);

    /**
     * Saves a rule-engine journal voucher (GL_VOUCHER) as a DRAFT. The payload is built by
     * the rule engine (V34 WP-B) and must already be debit/credit balanced; the gateway
     * only performs the save call and response parsing. No auto submit/audit — vouchers
     * are always reviewed by finance on the Kingdee side (T8 decision).
     *
     * @param payloadJson GL_VOUCHER save payload (see KingdeeGlVoucherPayloadBuilder)
     */
    KingdeeVoucherResult pushGlVoucher(String payloadJson);

    /**
     * Read-only connectivity probe for the UI "connection test" button. Must never
     * create or modify data on the Kingdee side (query-only by contract).
     */
    KingdeeConnectionStatus ping();

    /**
     * 只读拉取账套科目表（BD_Account）：科目编码 + 名称 + 必录核算维度类型。
     *
     * <p>用途（2026-09-21 方案 B）：① 制证前的**科目校验**——AI/规则给出的科目编码与名称
     * 必须与账套一致，否则会静默记错科目（实测案例：账套 2232=应付股利，而 AI 把它当「应付账款」）；
     * ② 判断该科目是否必须带**银行账号**核算维度（1002 挂 ZDY0001），从而决定是否注入维度。</p>
     *
     * <p>契约：只读、不写入；Mock 返回内置样例，Unavailable 返回空表（调用方按「目录不可用」降级处理）。</p>
     */
    java.util.List<KingdeeAccountRef> queryAccountCatalog();

    /**
     * 账套科目引用（BD_Account 一行）。
     *
     * @param number        科目编码（FNumber，如 1002 / 1122.01）
     * @param name          科目名称（FName）
     * @param dimensionCode 必录核算维度类型编码（FFlEXITEMPROPERTYID.FNumber，如 ZDY0001 银行账号）；无维度为 null
     */
    record KingdeeAccountRef(String number, String name, String dimensionCode) {
    }

    /**
     * 只读拉取账套银行账号档案（CN_BANKACNT），用于「FINFLOW 银行账户 → 金蝶账户」映射。
     *
     * <p>用途（2026-09-21）：总账凭证里挂「银行账号」必录核算维度（ZDY0001）的科目，
     * 报文必须带 CN_BANKACNT 档案编码；维度值应跟随**该笔流水所属的我方账户**，
     * 而不是全局一个默认值——一家公司有多个银行账户（实测账套 142 个档案分属 23 个组织）。</p>
     *
     * <p>契约：只读、不写入；Mock 返回内置样例，Unavailable 返回空表（调用方按「档案表不可用」
     * 降级处理，不阻断账户管理）。</p>
     */
    java.util.List<KingdeeBankAccountRef> queryBankAccountCatalog();

    /**
     * 账套银行账号档案（CN_BANKACNT 一行）。
     *
     * @param number    档案编码（FNumber）——核算维度值用的就是它（实测 142 个中 102 个即账号本体）
     * @param name      档案名称（FName），多为开户主体名或虚拟账户说明
     * @param orgNumber 所属组织编码（FCreateOrgId.FNumber），同账号跨组织重名时用于消歧
     */
    record KingdeeBankAccountRef(String number, String name, String orgNumber) {
    }
}
