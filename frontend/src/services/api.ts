import { http } from './http';
import type {
  AuthTokenResponse,
  ConnectionConfiguration,
  ConnectionOverview,
  DataQueryCapability,
  OperationLog,
  OperationTask,
  BankAccount,
  BankDataProjection,
  BankDataProjectionPage,
  BankSyncJob,
  BankSyncJobDetail,
  BankSyncJobTrigger,
  PageResponse,
  StatementAuditEvent,
  StatementDashboard,
  StatementDetail,
  StatementImportBatch,
  StatementImportRequest,
  StatementRecord,
  StatementReviewRequest,
  User,
} from '../types';

export const authApi = {
  login: (username: string, password: string) => http.post<never, AuthTokenResponse>('/auth/login', { username, password }),
  register: (data: { username: string; email: string; password: string }) => http.post<never, User>('/auth/register', data),
  me: () => http.get<never, User>('/auth/me'),
  logout: (token?: string) => http.post<never, void>('/auth/logout', undefined, token ? {
    headers: { Authorization: `Bearer ${token}` },
  } : undefined),
};

export const bankApi = {
  accounts: () => http.get<never, BankAccount[]>('/bank-accounts'),
};

export const userApi = {
  list: (params: { page?: number; size?: number }) => http.get<never, PageResponse<User>>('/users', { params }),
};

type StatementListParams = {
  page?: number;
  size?: number;
  batchId?: number;
  reviewStatus?: string;
  pushStatus?: string;
  validationStatus?: string;
};

type BatchListParams = { page?: number; size?: number; status?: string };

// These paths mirror the published statement DTOs and are intentionally isolated
// so a controller-path change does not leak into pages or shared finance APIs.
export const statementApi = {
  import: (data: StatementImportRequest) => http.post<never, StatementImportBatch>('/statement-imports', data),
  dashboard: () => http.get<never, StatementDashboard>('/reconciliation/dashboard'),
  listBatches: (params: BatchListParams) => http.get<never, PageResponse<StatementImportBatch>>('/statement-imports', { params }),
  list: (params: StatementListParams) => http.get<never, PageResponse<StatementRecord>>('/statements', { params }),
  get: (id: number) => http.get<never, StatementDetail>(`/statements/${id}`),
  review: (id: number, data: StatementReviewRequest) => http.post<never, StatementRecord>(`/statements/${id}/review`, data),
  pushVoucher: (id: number) => http.post<never, StatementRecord>(`/statements/${id}/voucher-push`),
};

type OperationListParams = { page?: number; size?: number; connectionCode?: string; status?: string; requestId?: string };

// Operations APIs are deliberately read-only in phase one. They expose FINFLOW
// facts and simulated/unavailable state only; no browser action can establish a bank connection.
export const operationsApi = {
  configuration: (section: 'applications' | 'contracts' | 'preferences') => http.get<never, ConnectionConfiguration>('/connections/configuration', { params: { section } }),
  connectionOverview: () => http.get<never, ConnectionOverview>('/operations/connections'),
  tasks: (params: OperationListParams) => http.get<never, PageResponse<OperationTask>>('/operations/tasks', { params }),
  logs: (params: OperationListParams) => http.get<never, PageResponse<OperationLog>>('/operations/logs', { params }),
  dataCapability: (resource: string) => http.get<never, DataQueryCapability>(`/data/${resource}`),
};

type BankJobListParams = { page?: number; size?: number; status?: string; jobType?: string; connectionCode?: string; requestId?: string };

type BankDataQueryParams = {
  page?: number;
  size?: number;
  status?: string;
  accountId?: string;
  keyword?: string;
  from?: string;
  to?: string;
  sourceSystem?: string;
  syncJobNo?: string;
  requestId?: string;
};

// v0.2 exposes only internal job resources and business projections. The client
// never requests bank SDK payloads, raw messages, credentials, or sync logs.
export const bankPipelineApi = {
  triggerJob: (data: BankSyncJobTrigger) => http.post<never, BankSyncJob>('/bank-sync-jobs', data),
  listJobs: (params: BankJobListParams) => http.get<never, PageResponse<BankSyncJob>>('/bank-sync-jobs', { params }),
  getJob: (id: number) => http.get<never, BankSyncJobDetail>(`/bank-sync-jobs/${id}`),
  queryProjection: (resource: string, params: BankDataQueryParams) => http.get<never, BankDataProjectionPage>(`/bank-data/${resource}`, { params }),
};
