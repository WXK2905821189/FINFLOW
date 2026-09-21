export type StatementRecordInput = {
  statementNo: string;
  bankAccountId?: number;
  transactionTime: string;
  direction: string;
  amount: number | string;
  currency?: string;
  counterpartyName?: string;
  counterpartyAccount?: string;
  summary?: string;
};

export type StatementImportRequest = {
  sourceName?: string;
  records: StatementRecordInput[];
};

export type StatementImportBatch = {
  id: number;
  batchNo: string;
  sourceType: string;
  sourceName?: string;
  status: string;
  totalCount: number;
  importedCount: number;
  duplicateCount: number;
  invalidCount: number;
  createdBy?: number;
  createdAt: string;
  completedAt?: string;
  errorMessage?: string;
};

export type StatementRecord = {
  id: number;
  batchId: number;
  statementNo: string;
  bankAccountId?: number;
  transactionTime?: string;
  direction?: string;
  amount?: number | string;
  currency?: string;
  counterpartyName?: string;
  maskedCounterpartyAccount?: string;
  summary?: string;
  validationStatus: string;
  validationMessage?: string;
  reviewStatus: string;
  reviewComment?: string;
  reviewedBy?: number;
  reviewedAt?: string;
  pushStatus: string;
  voucherNo?: string;
  pushMessage?: string;
  pushedAt?: string;
  createdAt: string;
};

export type StatementReviewRequest = {
  action: 'APPROVE' | 'REJECT';
  comment?: string;
};

/** 批量复核/推送逐行结果（/statements/batch-review、/statements/batch-push）。 */
export type StatementBatchOpRowResult = {
  id: number;
  statementNo?: string | null;
  outcome: 'APPROVED' | 'REJECTED' | 'PUSHED' | 'ALREADY_PUSHED' | 'SKIPPED' | 'FAILED';
  voucherNo?: string | null;
  message?: string | null;
};

export type StatementBatchOpResult = {
  totalCount: number;
  successCount: number;
  skippedCount: number;
  failedCount: number;
  rows: StatementBatchOpRowResult[];
};

/** AI 入账建议（/statements/{id}/ai-suggestion 重新生成，AI 只建议不执行）。 */
export type AiAccountingSuggestion = {
  statementId: number;
  businessCategory?: string | null;
  suggestedSummary?: string | null;
  counterpartyType?: string | null;
  settlementMethod?: string | null;
  suggestedSubject?: string | null;
  riskNotes?: string | null;
  confidence?: number | null;
  rationale?: string | null;
  model?: string | null;
  durationMillis?: number | null;
};

/** 凭证分录预填行（V33 凭证草稿详情：AI 预填 + 逐行置信度，人工可改）。 */
export type VoucherEntry = {
  summary?: string | null;
  subjectCode?: string | null;
  subjectName: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number | string;
  confidence?: number | null;
  /**
   * 辅助核算维度（供应商 / 客户 / 员工 / 银行账号 / 部门·项目 等，2026-09-21 预留）。
   * 后端分录目前不产出该字段（随金蝶凭证规则引擎落地）；为 null 时详情页不渲染该列，
   * 数据一到即自动出现，避免先摆一个空列。
   */
  dimension?: string | null;
};

/** 凭证草稿的结构化 AI 建议（GET /statements/{id} 的 aiSuggestion 字段，V33）。 */
export type AiVoucherSuggestion = {
  businessCategory?: string | null;
  suggestedSummary?: string | null;
  counterpartyType?: string | null;
  settlementMethod?: string | null;
  suggestedSubject?: string | null;
  riskNotes?: string | null;
  confidence?: number | null;
  rationale?: string | null;
  model?: string | null;
  durationMillis?: number | null;
  entries: VoucherEntry[];
  balanced?: boolean | null;
  edited: boolean;
  editedBy?: number | null;
  editedAt?: string | null;
};

/** 保存人工修正后的凭证分录（PUT /statements/{id}/voucher-draft）。 */
export type VoucherDraftSavePayload = {
  summary?: string;
  entries: VoucherEntry[];
};

export type StatementDashboard = {
  totalCount: number;
  pendingReviewCount: number;
  approvedCount: number;
  rejectedCount: number;
  pushedCount: number;
  invalidCount: number;
  totalAmount: number | string;
  approvedAmount: number | string;
  pushedAmount: number | string;
};

export type StatementAuditEvent = {
  id: number;
  action: string;
  result: string;
  previousStatus?: string;
  currentStatus?: string;
  operatorId?: number;
  detail?: string;
  createdAt: string;
};

export type StatementDetail = {
  statement: StatementRecord;
  /** V33 凭证草稿的结构化 AI 建议（未生成过 AI 建议时为 null）。 */
  aiSuggestion?: AiVoucherSuggestion | null;
  auditTrail: StatementAuditEvent[];
};

export type ValidationRule = {
  id: number;
  ruleCode: string;
  name: string;
  ruleType: string;
  expression: string;
  versionNo: number;
  status: string;
  priority: number;
  createdBy?: number;
  updatedAt: string;
};

export type AccountingMapping = {
  id: number;
  mappingCode: string;
  name: string;
  direction: string;
  counterpartyKeyword?: string;
  debitSubject: string;
  creditSubject: string;
  voucherTemplate: string;
  versionNo: number;
  status: string;
  updatedAt: string;
};
