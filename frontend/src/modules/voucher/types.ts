/**
 * V34 ⑦ 凭证中心 / ② 大类规则 前端类型（voucher-groups 与 kingdee voucher-rules 契约）。
 */

/** 凭证中心列表行（GET /statements/voucher-groups，voucher:push）。 */
export type VoucherGroupRow = {
  statementId: number;
  statementNo: string;
  /** 金蝶凭证号（凭证组号）；未推送时为 null。 */
  voucherNo: string | null;
  businessDate: string;
  companyName: string | null;
  /** 银行账户显示名（账户名 + 脱敏账号）。 */
  bankAccount: string | null;
  /** 收（INCOME）/ 付（EXPENSE）。 */
  direction: string;
  amount: number | string;
  currency: string;
  summary: string | null;
  reviewStatus: string;
  /** 已归一：PUSHED / FAILED / null（未推送）。 */
  pushStatus: string | null;
  pushMessage: string | null;
  pushedAt: string | null;
  /** 组内流水笔数（一期推送链路 1 笔 → 1 张凭证，恒为 1）。 */
  statementCount: number;
};

/** 凭证组状态签（列表 Segmented）。 */
export type VoucherGroupFilter = 'ALL' | 'DRAFT' | 'PENDING' | 'PUSHED' | 'FAILED';

/** 规则分录模板行（后端 KingdeeVoucherRuleResponse.LineTemplate 镜像）。 */
export type VoucherRuleLine = {
  account: string;
  name: string | null;
  /** 辅助维度来源：BANK_ACCOUNT/ORG/EMPLOYEE/SUPPLIER/CUSTOMER/COUNTERPARTY/FIXED/BY_SUMMARY_BRANCH/NONE。 */
  dimension: string | null;
  /** FIXED 维度的固定值。 */
  value: string | null;
  branches: Array<{ keyword: string; supplier: string }> | null;
  /** 金额分摊：FULL / EQUAL（尾差末行吸收）/ MANUAL（人工确认）。 */
  share: string;
};

/** 大类规则行（GET /kingdee/voucher-rules，voucher:push）。 */
export type VoucherRuleRow = {
  id: number;
  ruleNo: number;
  businessType: string;
  category: string;
  priority: number;
  scopeOrgs: string[];
  scopeBankChannels: string[];
  direction: string;
  amountMin: string | null;
  amountMax: string | null;
  match: { logic: string; conditions: Array<{ field: string; op: string; values: string[] }> } | null;
  debitLines: VoucherRuleLine[];
  creditLines: VoucherRuleLine[];
  extraVoucher: { debitLines: VoucherRuleLine[]; creditLines: VoucherRuleLine[] } | null;
  enabled: boolean;
  remark: string | null;
  /** W4 规则中心：所属分组（null = 未分组）。 */
  groupId?: number | null;
  groupName?: string | null;
};

/** 规则分组（W4 规则中心，GET /kingdee/voucher-rule-groups）。 */
export type VoucherRuleGroup = {
  id: number;
  name: string;
  description: string | null;
  sortNo: number;
  ruleCount: number;
};

/** 规则创建/更新载荷（POST/PUT /kingdee/voucher-rules）。 */
export type VoucherRuleUpsertPayload = {
  ruleNo?: number | null;
  businessType: string;
  category: string;
  priority: number;
  scopeOrgs?: string[] | null;
  scopeBankChannels?: string[] | null;
  direction: string;
  amountMin?: string | null;
  amountMax?: string | null;
  match: { logic: string; conditions: Array<{ field: string; op: string; values: string[] }> } | null;
  debitLines: VoucherRuleLine[];
  creditLines: VoucherRuleLine[];
  extraVoucher?: VoucherRuleRow['extraVoucher'];
  enabled: boolean;
  remark?: string | null;
  groupId?: number | null;
};

/** Excel 导入预览行：原文单元格 + AI 映射（aiMapped=false 时由人工补齐）。 */
export type VoucherRuleImportRow = {
  rowIndex: number;
  sourceCells: string[];
  mapped: VoucherRuleUpsertPayload | null;
  aiMapped: boolean;
  confidence: number | null;
  aiNote: string | null;
};

/** Excel 导入预览响应（无状态两步导入第一步，不入库）。 */
export type VoucherRuleImportPreview = {
  totalRows: number;
  rows: VoucherRuleImportRow[];
  aiSummary: string;
};
