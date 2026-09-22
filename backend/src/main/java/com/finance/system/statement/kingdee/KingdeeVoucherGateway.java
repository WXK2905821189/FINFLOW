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

    /**
     * 只读批量回查基础资料档案状态（P1-3，2026-09-22）。
     *
     * <p>用途：维度映射批量导入前，回查映射指向的金蝶档案（供应商/客户/员工）是否**已审核**
     * ——FIX-006 的教训：金蝶单据只能引用已审核基础资料，暂存(A)档案被引用视同未填。
     * 映射里的档案是财务在金蝶侧手工维护的，不走 FINFLOW 的 auto-audit，暂存态只能在
     * 导入时提前暴露（导入结果标注、不阻断），否则要等到首推才收到「未录入或不可用」。</p>
     *
     * <p>契约：只读、不写入；返回「档案编码 → FDocumentStatus（A 暂存/B 已提交/C 已审核）」，
     * 查不到的编码不出现在结果里。Unavailable 返回空 Map（调用方按「无法回查」跳过标注）。</p>
     *
     * @param formId    基础资料表单（BD_Supplier / BD_Customer / BD_Empinfo）
     * @param numbers   档案编码清单（FNumber）
     */
    java.util.Map<String, String> queryBaseDataDocumentStatus(String formId, java.util.Collection<String> numbers);

    /**
     * 只读全量拉取一类基础资料档案（2026-09-22 用户授权：供应商/客户/员工档案只读同步）。
     *
     * <p>用途：AI 制证退役、规则制证唯一化后，维度映射（IN_SUPPLIER_LIST / IN_EMPLOYEE_LIST /
     * IN_CUSTOMER_MAPPING 算子的命中前提）需要与金蝶侧档案保持同步——金蝶新增/改名档案后，
     * FINFLOW 侧在「维度映射」页一键拉取比对，而不是等映射失配才暴露。员工维度尤其依赖：
     * 人事表只有姓名，金蝶员工档案编码（FNumber）只能从账套拉取后按姓名 join 生成映射。</p>
     *
     * <p>契约：只读、不写入；单次至多 TopRowCount 2000 行（超出一类档案 2000 行的场景
     * 目前不存在，出现时再分页）。Mock 返回内置样例；Unavailable 返回空表（调用方
     * 按「档案目录不可用」提示，不阻断映射维护）。</p>
     *
     * @param formId 基础资料表单（BD_Supplier / BD_Customer / BD_Empinfo）
     */
    java.util.List<KingdeeBaseDataRef> queryBaseDataCatalog(String formId);

    /**
     * 金蝶基础资料档案（BD_Supplier / BD_Customer / BD_Empinfo 一行）。
     *
     * @param number          档案编码（FNumber）——维度映射 kingdee_value 用的就是它
     * @param name            档案名称（FName）
     * @param documentStatus  文档状态（FDocumentStatus：A 暂存 / B 已提交 / C 已审核；查询未返回该列时为 null）
     */
    record KingdeeBaseDataRef(String number, String name, String documentStatus) {
    }
}
