export type ApiEnvelope<T> = {
  code: number;
  message: string;
  data: T;
  timestamp: string;
};

export type PageResponse<T> = {
  page: number;
  size: number;
  total: number;
  records: T[];
};

export type User = {
  id: number;
  username: string;
  email: string;
  phone?: string;
  status: string;
  roles: string[];
  permissions: string[];
};

export type AuthTokenResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
};

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
  auditTrail: StatementAuditEvent[];
};
