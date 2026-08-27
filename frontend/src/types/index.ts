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

export type BankAccount = {
  id: number;
  bankCode: string;
  accountName: string;
  maskedAccountNumber: string;
  currency: string;
  availableBalance: number | string;
  status: string;
};

export type BankTransferRequest = {
  bankCode: string;
  payerAccountId: number;
  payeeName: string;
  payeeAccount: string;
  payeeBank: string;
  amount: number | string;
  remark: string;
};

export type BankTransferResponse = {
  bankCode: string;
  bankReference: string;
  status: string;
  message: string;
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

export type ConnectionSummary = {
  connectionCode: string;
  displayName: string;
  providerType: string;
  enabled: boolean;
  status: string;
  lastCheckedAt?: string;
};

export type ConnectionConfiguration = {
  enabled: boolean;
  status: string;
  message: string;
  supportedProviderTypes: string[];
  connections: ConnectionSummary[];
};

export type ConnectionOverview = {
  enabled: boolean;
  status: string;
  message: string;
  connections: ConnectionSummary[];
};

export type OperationTask = {
  taskNo: string;
  taskType: string;
  connectionCode?: string;
  status: string;
  requestId?: string;
  summary?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
};

export type OperationLog = {
  taskId?: number;
  level: string;
  eventType: string;
  result: string;
  requestId?: string;
  message?: string;
  occurredAt: string;
};

export type DataQueryCapability = {
  capability: string;
  enabled: boolean;
  status: string;
  message: string;
};

export type BankSyncJobTrigger = {
  jobType: string;
  connectionCode?: string;
  windowStart?: string;
  windowEnd?: string;
};

export type BankSyncJob = {
  id: number;
  jobNo: string;
  jobType: string;
  triggerType: string;
  connectionCode?: string;
  status: string;
  requestId?: string;
  summary?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
};

export type BankSyncJobEvent = {
  status: string;
  stage: string;
  message?: string;
  requestId?: string;
  occurredAt: string;
};

export type BankSyncJobDetail = {
  job: BankSyncJob;
  timeline: BankSyncJobEvent[];
};

export type BankDataProjection = {
  id: string | number;
  sourceSystem?: string;
  sourceRecordId?: string;
  status?: string;
  occurredAt?: string;
  accountMasked?: string;
  amount?: number | string;
  currency?: string;
  direction?: string;
  summary?: string;
};
