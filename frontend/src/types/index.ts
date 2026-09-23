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
  BankRawReplayResult,
  BankTaskReconciliationRow,
  CompanyOption,
  BankSyncLogRow,
  StatementTransferResult,
  BankSyncScheduleRow,
  AccountPreference,
} from '../modules/bank-access/types';
export type {
  StatementRecordInput,
  StatementImportRequest,
  StatementImportBatch,
  StatementRecord,
  StatementReviewRequest,
  StatementBatchOpRowResult,
  StatementBatchOpResult,
  AiAccountingSuggestion,
  VoucherEntry,
  AiVoucherSuggestion,
  VoucherDraftSavePayload,
  StatementDashboard,
  StatementAuditEvent,
  StatementDetail,
  ValidationRule,
  AccountingMapping,
} from '../modules/statements/types';
export type {
  FeishuAppConfigView,
  FeishuAppConfigPayload,
} from '../modules/feishu/types';
export type { ClosingPeriod } from '../modules/closing/types';
export type { SystemAuditEvent } from '../modules/audit/types';
export type {
  CompanyArchiveCompany,
  CompanyArchiveAccount,
  CompanyArchiveView,
  BankConnectionTestResult,
  BankAccountCreatePayload,
  PushRowResult,
  PushJobResult,
  PushSubmitResult,
  AiCompanySuggestion,
  AiCompanySuggestionResponse,
  AiCompanyApplyRow,
  AiCompanyApplyResponse,
  KingdeeMappingRow,
  KingdeeMappingPreview,
  KingdeeMatchResult,
} from '../modules/bank-access/types';
export type {
  VoucherGroupRow,
  VoucherGroupFilter,
  VoucherProblemRow,
  VoucherProblemLinePayload,
  VoucherProblemEditDocPayload,
  VoucherProblemDraftLine,
  VoucherProblemDetail,
  VoucherProblemSubmitResult,
  VoucherRuleLine,
  VoucherRuleRow,
  VoucherRuleGroup,
  VoucherRuleUpsertPayload,
  VoucherRuleImportRow,
  VoucherRuleImportPreview,
  DimensionSlotRow,
  DimensionMappingRow,
  DimensionSlotUpsertPayload,
  DimensionMappingUpsertPayload,
} from '../modules/voucher/types';
