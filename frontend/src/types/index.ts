// Barrel re-export: type definitions now live in modules/<domain>/types.ts.
// This file keeps existing `from '../types'` imports working; new code should
// import from the domain module directly.
export type { ApiEnvelope, PageResponse } from '../modules/shared/api';
export type { User, AuthTokenResponse } from '../modules/auth/types';
export type {
  BankAccount,
  ConnectionSummary,
  ConnectionOverview,
  OperationLog,
  DataQueryCapability,
  BankSyncJobTrigger,
  BankSyncJob,
  BankSyncJobEvent,
  BankSyncJobDetail,
  BankDataStatementRow,
  BankDataBalanceRow,
  BankDataProjectionPage,
  BankRawMessage,
  BankRawMessageDetail,
} from '../modules/bank-access/types';
export type {
  StatementRecordInput,
  StatementImportRequest,
  StatementImportBatch,
  StatementRecord,
  StatementReviewRequest,
  StatementDashboard,
  StatementAuditEvent,
  StatementDetail,
  ValidationRule,
  AccountingMapping,
} from '../modules/statements/types';
export type {
  FeishuConnectionItem,
  FeishuDestinationItem,
  FeishuPolicyItem,
  FeishuOverview,
  NotificationDelivery,
} from '../modules/feishu/types';
export type { ClosingPeriod } from '../modules/closing/types';
export type { SystemAuditEvent } from '../modules/audit/types';
