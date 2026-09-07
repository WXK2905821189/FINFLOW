import type { AxiosResponse } from 'axios';
import { http } from './http';
import type {
  AuthTokenResponse,
  ConnectionOverview,
  FeishuOverview,
  FeishuConnectionItem,
  FeishuDestinationItem,
  FeishuPolicyItem,
  NotificationDelivery,
  DataQueryCapability,
  OperationLog,
  BankAccount,
  BankDataProjectionPage,
  BankRawMessage,
  BankRawMessageDetail,
  BankRawReplayResult,
  BankTaskReconciliationRow,
  CompanyOption,
  BankSyncLogRow,
  BankSyncJob,
  BankSyncJobDetail,
  BankSyncJobTrigger,
  PageResponse,
  StatementDashboard,
  StatementDetail,
  StatementImportBatch,
  StatementImportRequest,
  StatementRecord,
  StatementReviewRequest,
  ValidationRule,
  AccountingMapping,
  ClosingPeriod,
  SystemAuditEvent,
  User,
} from '../types';
import type {
  SysPermission,
  SysRole,
  RoleCreatePayload,
  RoleUpdatePayload,
  UserUpsertPayload,
} from '../modules/admin/types';

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
  create: (data: UserUpsertPayload) => http.post<never, User>('/users', data),
  update: (id: number, data: UserUpsertPayload) => http.put<never, User>(`/users/${id}`, data),
};

/**
 * Role/permission management (user:manage + role:manage, ADMIN only). The permission list
 * doubles as the single source of truth for the role editor checkboxes; codes follow
 * docs/permission-catalog.md and are never invented client-side.
 */
export const rbacApi = {
  roles: () => http.get<never, SysRole[]>('/rbac/roles'),
  permissions: () => http.get<never, SysPermission[]>('/rbac/permissions'),
  createRole: (data: RoleCreatePayload) => http.post<never, SysRole>('/rbac/roles', data),
  updateRole: (id: number, data: RoleUpdatePayload) => http.put<never, SysRole>(`/rbac/roles/${id}`, data),
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
  connectionOverview: () => http.get<never, ConnectionOverview>('/operations/connections'),
  logs: (params: OperationListParams) => http.get<never, PageResponse<OperationLog>>('/operations/logs', { params }),
  dataCapability: (resource: string) => http.get<never, DataQueryCapability>(`/data/${resource}`),
};

export const feishuApi = {
  overview: () => http.get<never, FeishuOverview>('/feishu/overview'),
  createConnection: (data: { displayName: string; tenantAlias?: string }) => http.post<never, FeishuConnectionItem>('/feishu/connections', data),
  createDestination: (data: { connectionId: number; destinationType: string; destinationKey: string; displayName: string }) => http.post<never, FeishuDestinationItem>('/feishu/destinations', data),
  savePolicy: (data: { eventType: string; destinationId: number; enabled: boolean }) => http.post<never, FeishuPolicyItem>('/feishu/policies', data),
  notify: (data: { eventId?: string; eventType: string; referenceNo?: string; severity: string; summary: string; destinationId?: number }) => http.post<never, NotificationDelivery>('/feishu/notifications', data),
  deliveries: (params: { page?: number; size?: number; status?: string }) => http.get<never, PageResponse<NotificationDelivery>>('/feishu/deliveries', { params }),
  retry: (eventId: string) => http.post<never, NotificationDelivery>(`/feishu/notifications/${encodeURIComponent(eventId)}/retry`),
};

export const validationApi = {
  rules: (params: { page?: number; size?: number; status?: string }) => http.get<never, PageResponse<ValidationRule>>('/validation/rules', { params }),
  createRule: (data: { ruleCode: string; name: string; ruleType: string; expression: string; priority?: number }) => http.post<never, ValidationRule>('/validation/rules', data),
  activateRule: (id: number) => http.post<never, ValidationRule>(`/validation/rules/${id}/activate`),
  mappings: (params: { page?: number; size?: number; status?: string }) => http.get<never, PageResponse<AccountingMapping>>('/validation/mappings', { params }),
  createMapping: (data: { mappingCode: string; name: string; direction: string; counterpartyKeyword?: string; debitSubject: string; creditSubject: string; voucherTemplate: string }) => http.post<never, AccountingMapping>('/validation/mappings', data),
  activateMapping: (id: number) => http.post<never, AccountingMapping>(`/validation/mappings/${id}/activate`),
};

export const closingApi = {
  periods: (params: { page?: number; size?: number; status?: string }) => http.get<never, PageResponse<ClosingPeriod>>('/closing/periods', { params }),
  check: (period: string) => http.post<never, ClosingPeriod>(`/closing/periods/${period}/check`),
  close: (period: string) => http.post<never, ClosingPeriod>(`/closing/periods/${period}/close`),
};

export const auditApi = {
  events: (params: { page?: number; size?: number; action?: string; objectType?: string; requestId?: string }) => http.get<never, PageResponse<SystemAuditEvent>>('/audit/events', { params }),
};

type BankJobListParams = { page?: number; size?: number; status?: string; jobType?: string; connectionCode?: string; requestId?: string };

type BankRawMessageListParams = {
  page?: number;
  size?: number;
  accountId?: string;
  taskNo?: string;
  adapterCode?: string;
  from?: string;
  to?: string;
};

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
  /** 跨公司权限用户可选定公司主体；不传=全部可见公司，无权限用户传值会被服务端 403。 */
  companyId?: number;
};

// v0.2 exposed only internal job resources and business projections, and the client
// never requested raw payloads, credentials, or sync logs. That boundary was relaxed
// deliberately for one surface: the raw message module (bankdata:raw:view) exists to
// evidence that the bank was actually reached, which is exactly what a digest-only
// view cannot prove. Everything else still stays server-side.
export const bankPipelineApi = {
  triggerJob: (data: BankSyncJobTrigger) => http.post<never, BankSyncJob>('/bank-sync-jobs', data),
  listJobs: (params: BankJobListParams) => http.get<never, PageResponse<BankSyncJob>>('/bank-sync-jobs', { params }),
  getJob: (id: number) => http.get<never, BankSyncJobDetail>(`/bank-sync-jobs/${id}`),
  /**
   * Returns the bank's own row shape for the resource (BankDataStatementRow for statements,
   * BankDataBalanceRow for balances) rather than a generic business projection.
   */
  queryProjection: <T>(resource: string, params: BankDataQueryParams) => http.get<never, BankDataProjectionPage<T>>(`/bank-data/${resource}`, { params }),
  /** 公司主体下拉数据源：跨公司权限者返回全部 ACTIVE 公司，否则仅本公司。 */
  companyOptions: () => http.get<never, CompanyOption[]>('/bank-data/company-options'),
  /** 运行日志：真实同步作业事件流（bank_data_sync_log），替代空的 connection_operation_log。 */
  syncLogs: (params: { page?: number; size?: number; status?: string; level?: string; requestId?: string }) =>
    http.get<never, PageResponse<BankSyncLogRow>>('/bank-data/sync-logs', { params }),
  listRawMessages: (params: BankRawMessageListParams) => http.get<never, PageResponse<BankRawMessage>>('/bank-data-raw-messages', { params }),
  getRawMessage: (id: number) => http.get<never, BankRawMessageDetail>(`/bank-data-raw-messages/${id}`),
  /** 用当前解析规则重放银行原文，与入库视图对比；仅读不写。 */
  replayRawMessage: (id: number) => http.post<never, BankRawReplayResult>(`/bank-data-raw-messages/${id}/replay`),
  /** 任务级对账：银行 Z1 合计 vs 平台入库。 */
  taskReconciliation: (params: { page?: number; size?: number }) =>
    http.get<never, PageResponse<BankTaskReconciliationRow>>('/bank-data/task-reconciliation', { params }),
  /**
   * CSV export in the bank's own column layout. The backend renders the file and
   * names it (filename* RFC5987 for the Chinese name); the client only carries
   * the auth header and hands the blob to the user. Export reads the same
   * authorized data as queryProjection in another shape, so it grants no new access.
   */
  exportCsv: async (resource: 'balances' | 'statements', params: Omit<BankDataQueryParams, 'page' | 'size'>): Promise<void> => {
    const response = await http.get<never, AxiosResponse<Blob>>(`/bank-data/${resource}/export`, { params, responseType: 'blob' });
    const disposition = String(response.headers?.['content-disposition'] ?? '');
    const utf8Name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
    const asciiName = /filename="([^"]+)"/i.exec(disposition)?.[1];
    const filename = utf8Name ? decodeURIComponent(utf8Name) : asciiName || `${resource === 'balances' ? '银行余额' : '银行流水'}导出.csv`;
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },
};
