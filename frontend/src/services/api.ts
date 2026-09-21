import type { AxiosResponse } from 'axios';
import { http } from './http';
import type {
  AuthTokenResponse,
  ConnectionOverview,
  FeishuAppConfigView,
  FeishuAppConfigPayload,
  DataQueryCapability,
  OperationLog,
  BankAccount,
  BankSyncScheduleRow,
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
  StatementTransferResult,
  StatementImportRequest,
  StatementRecord,
  StatementReviewRequest,
  StatementBatchOpResult,
  ValidationRule,
  AccountingMapping,
  ClosingPeriod,
  SystemAuditEvent,
  User,
  CompanyArchiveView,
  CompanyArchiveCompany,
  CompanyArchiveAccount,
  BankConnectionTestResult,
  BankAccountCreatePayload,
  KingdeeMappingRow,
  KingdeeMappingPreview,
  KingdeeMatchResult,
  AiVoucherSubmitResult,
  AiVoucherJobResult,
  AiCompanySuggestionResponse,
  AiCompanyApplyResponse,
  AiVoucherSuggestion,
  VoucherDraftSavePayload,
  VoucherGroupRow,
  VoucherRuleGroup,
  VoucherRuleImportPreview,
  VoucherRuleRow,
  VoucherRuleUpsertPayload,
  AccountPreference,
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
  /** 档案管理：一次拉取全部公司档案与跨公司账户（bank:manage）。 */
  archive: () => http.get<never, CompanyArchiveView>('/bank-account-archive'),
  createArchiveCompany: (name: string) => http.post<never, CompanyArchiveCompany>('/bank-account-archive/companies', { name }),
  renameArchiveCompany: (id: number, name: string) => http.put<never, CompanyArchiveCompany>(`/bank-account-archive/companies/${id}`, { name }),
  /** 归类：账户改挂公司档案，历史流水/余额一并迁移。 */
  assignArchiveAccount: (id: number, companyId: number) => http.put<never, CompanyArchiveAccount>(`/bank-account-archive/accounts/${id}/company`, { companyId }),
  /** 取消归属：账户拖回「未归属」区（历史流水/余额口径对称置空）。 */
  unassignArchiveAccount: (id: number) => http.post<never, CompanyArchiveAccount>(`/bank-account-archive/accounts/${id}/unassign`),
  /** AI 归类建议（ai:use + bank:manage）：推断未归档账户的公司主体，只建议不执行。 */
  aiSuggestCompanies: () => http.post<never, AiCompanySuggestionResponse>('/bank-account-archive/ai-suggest-companies'),
  /** AI 归类建议批量应用（bank:manage）：仅应用勾选行，公司不存在则建档，历史归属一并迁移。 */
  aiApplyCompanies: (items: Array<{ accountId: number; companyName: string }>) =>
    http.post<never, AiCompanyApplyResponse>('/bank-account-archive/ai-apply-companies', { items }),
  /** 新增银行账户（bank:manage）；创建后归属当前用户公司，可在档案板拖拽归类。 */
  createAccount: (data: BankAccountCreatePayload) => http.post<never, BankAccount>('/bank-accounts', data),
  /** 连通性探测（bank:manage 或 bankdata:sync:trigger）：服务端对适配器发起一次只读调用，不落库。 */
  testConnection: (id: number) => http.post<never, BankConnectionTestResult>(`/bank-accounts/${id}/test-connection`),
  /** 档案移除（bank:manage，V32 软删除）：历史流水/余额保留，全链路自动隐藏。 */
  deleteAccount: (id: number) => http.delete<never, void>(`/bank-accounts/${id}`),
  /**
   * 金蝶账户映射预演（bank:manage，只读，V41）：列出每个账户与金蝶 CN_BANKACNT 档案的
   * 匹配判定（可自动匹配 / 跨组织多义 / 未命中虚拟账户 / 无需映射）。
   */
  kingdeeMapping: () => http.get<never, KingdeeMappingPreview>('/bank-accounts/kingdee-mapping'),
  /** 一键自动匹配（bank:manage）：只写回唯一命中，多义与未命中留给人工指定。 */
  autoMatchKingdee: () => http.post<never, KingdeeMatchResult>('/bank-accounts/kingdee-mapping/auto-match'),
  /** 人工指定某账户的金蝶银行账号档案编码；空白表示清除映射。 */
  setKingdeeMapping: (id: number, kingdeeAccountNumber: string) =>
    http.put<never, KingdeeMappingRow>(`/bank-accounts/${id}/kingdee-mapping`, { kingdeeAccountNumber }),
};

export const userApi = {
  list: (params: { page?: number; size?: number }) => http.get<never, PageResponse<User>>('/users', { params }),
  create: (data: UserUpsertPayload) => http.post<never, User>('/users', data),
  update: (id: number, data: UserUpsertPayload) => http.put<never, User>(`/users/${id}`, data),
  /** V36-W5：物理删除（有业务/审计引用时后端 409，提示改用停用）。 */
  remove: (id: number) => http.delete<never, void>(`/users/${id}`),
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

export type DictTypeRow = {
  id: number;
  typeCode: string;
  name: string;
  description: string | null;
  status: string;
  itemCount: number;
  createdAt: string | null;
};

export type DictItemRow = {
  id: number;
  typeId: number;
  itemCode: string;
  label: string;
  extraJson: string | null;
  sortNo: number;
  status: string;
  remark: string | null;
};

export type DictTypePayload = {
  typeCode?: string;
  name: string;
  description?: string | null;
  status?: string;
};

export type DictItemPayload = {
  itemCode: string;
  label: string;
  extraJson?: string | null;
  sortNo?: number;
  status?: string;
  remark?: string | null;
};

export const dictApi = {
  listTypes: () => http.get<never, DictTypeRow[]>('/system/dicts/types'),
  createType: (data: DictTypePayload) => http.post<never, DictTypeRow>('/system/dicts/types', data),
  updateType: (id: number, data: DictTypePayload) => http.put<never, DictTypeRow>(`/system/dicts/types/${id}`, data),
  deleteType: (id: number, force = false) => http.delete<never, void>(`/system/dicts/types/${id}?force=${force}`),
  listItems: (typeId: number) => http.get<never, DictItemRow[]>(`/system/dicts/types/${typeId}/items`),
  createItem: (typeId: number, data: DictItemPayload) => http.post<never, DictItemRow>(`/system/dicts/types/${typeId}/items`, data),
  updateItem: (id: number, data: DictItemPayload) => http.put<never, DictItemRow>(`/system/dicts/items/${id}`, data),
  deleteItem: (id: number) => http.delete<never, void>(`/system/dicts/items/${id}`),
};

// ---- AI 能力地基（V27）：网关状态/自检/审计 ----

export type AiStatus = {
  enabled: boolean;
  provider: string;
  model: string;
  baseUrl: string;
  apiKeyConfigured: boolean;
  capabilities: Record<string, boolean>;
};

export type AiSelfTest = {
  reply: string;
  model: string;
  durationMillis: number;
  promptTokens: number | null;
  completionTokens: number | null;
};

export type AiCallLogRow = {
  id: number;
  capability: string;
  userId: number;
  companyId: number | null;
  provider: string;
  model: string;
  status: string;
  promptHash: string | null;
  promptSummary: string | null;
  responseHash: string | null;
  responseSummary: string | null;
  errorMessage: string | null;
  durationMs: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  createdAt: string | null;
};

export const aiApi = {
  status: () => http.get<never, AiStatus>('/ai/status'),
  selfTest: () => http.post<never, AiSelfTest>('/ai/self-test'),
  callLogs: (limit = 50) => http.get<never, AiCallLogRow[]>(`/ai/call-logs?limit=${limit}`),
  getConfig: () => http.get<never, AiConfigView>('/ai/config'),
  updateConfig: (data: AiConfigPayload) => http.put<never, AiConfigView>('/ai/config', data),
  testConfig: (data: AiConfigTestPayload) => http.post<never, AiSelfTest>('/ai/config/test', data),
  /** 用表单当前值（baseUrl+密钥）拉取供应商可用模型列表（OpenAI 兼容 GET /models） */
  listModels: (data: AiConfigTestPayload) => http.post<never, string[]>('/ai/config/models', data),
  accountingSuggestion: (statementId: number) =>
    http.post<never, AiAccountingSuggestion>('/ai/accounting-suggestion', { statementId }),
};

// ---- V38 AI 提示词配置（W9 需求 4；ai:config 仅超管）----

export type AiPromptCapability = 'accounting-suggestion' | 'company-classification' | 'rule-import';

export type AiPromptView = {
  capability: string;
  name: string;
  description: string;
  /** 当前生效的系统提示词（有覆盖 = 覆盖值，否则 = 默认值）。 */
  effectivePrompt: string;
  customized: boolean;
  /** 系统默认提示词（「恢复默认」的基准）。 */
  defaultPrompt: string;
  updatedBy: string | null;
  updatedAt: string | null;
};

export const aiPromptApi = {
  list: () => http.get<never, AiPromptView[]>('/ai/prompts'),
  save: (capability: AiPromptCapability, systemPrompt: string) =>
    http.put<never, AiPromptView>(`/ai/prompts/${capability}`, { systemPrompt }),
  reset: (capability: AiPromptCapability) => http.delete<never, void>(`/ai/prompts/${capability}`),
};

// ---- V28 AI 在线配置（设置页）----

export type AiConfigPayload = {
  enabled?: boolean;
  baseUrl?: string;
  /** null/undefined = 保持现有密钥不变；空字符串 = 清除 */
  apiKey?: string | null;
  model?: string;
  timeoutMillis?: number;
  maxRetries?: number;
  capabilities?: Record<string, boolean>;
};

export type AiConfigTestPayload = {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
};

export type AiConfigDbView = {
  enabled: boolean | null;
  baseUrl: string | null;
  model: string | null;
  apiKeyHint: string | null;
  apiKeyConfigured: boolean;
  timeoutMillis: number | null;
  maxRetries: number | null;
  capabilities: Record<string, boolean> | null;
  updatedAt: string | null;
  updatedBy: number | null;
};

export type AiConfigView = {
  db: AiConfigDbView | null;
  effective: AiStatus & { configSource: string };
};

// ---- A1 智能入账建议（AI 只建议，不执行）----

export type AiAccountingSuggestion = {
  statementId: number;
  businessCategory: string | null;
  suggestedSummary: string | null;
  counterpartyType: 'CUSTOMER' | 'SUPPLIER' | 'EMPLOYEE' | 'OTHER' | null;
  settlementMethod: string | null;
  suggestedSubject: string | null;
  riskNotes: string | null;
  confidence: number | null;
  rationale: string | null;
  model: string;
  durationMillis: number;
  /** W10（WP-5）：命中的入账规则（服务端规则引擎判定，非空表示建议受规则约束）。 */
  hitRules?: Array<{ ruleNo: number; businessType: string | null; category: string | null }>;
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
  pingKingdee: () => http.get<never, { connected: boolean; mode: string; message: string }>('/statements/kingdee/ping'),
  /** 批量通过/驳回草稿（BANKDATA 批次允许生成人自审）。 */
  batchReview: (data: { ids: number[]; action: 'APPROVE' | 'REJECT'; comment?: string }) =>
    http.post<never, StatementBatchOpResult>('/statements/batch-review', data),
  /** 批量推送已通过复核的草稿到金蝶。 */
  batchPush: (data: { ids: number[] }) =>
    http.post<never, StatementBatchOpResult>('/statements/batch-push', data),
  /** W8：重新打开已驳回的流水（REJECTED→PENDING，可重新制证；审计 REOPEN）。 */
  reopen: (id: number) => http.post<never, StatementRecord>(`/statements/${id}/reopen`),
  /** W10（V39）：撤回未推送金蝶的凭证（标记已撤回 + 流水回池可重新制证；审计 WITHDRAW）。 */
  withdraw: (id: number) => http.post<never, StatementRecord>(`/statements/${id}/withdraw`),
  /** 对 PENDING 草稿重新生成 AI 建议（覆盖复核意见，不改状态；ai:use 闸门在服务端）。 */
  refreshAiSuggestion: (id: number) =>
    http.post<never, AiAccountingSuggestion>(`/statements/${id}/ai-suggestion`),
  /** V33：保存人工修正后的凭证分录与主摘要（voucher:push 闸门；主摘要回写供金蝶单据备注）。 */
  saveVoucherDraft: (id: number, data: VoucherDraftSavePayload) =>
    http.put<never, AiVoucherSuggestion>(`/statements/${id}/voucher-draft`, data),
};

/** V34 ⑦ 凭证中心（voucher:push）：流水管线投影为凭证组语义的只读视图。 */
export const voucherGroupApi = {
  list: (params: { page?: number; size?: number; status?: string; keyword?: string }) =>
    http.get<never, PageResponse<VoucherGroupRow>>('/statements/voucher-groups', { params }),
};

/** W4 规则中心（voucher:push）：金蝶凭证规则 CRUD + 分组 + Excel 导入（AI 映射 + 人工审阅）。 */
export const kingdeeRuleApi = {
  list: (enabledOnly?: boolean, groupId?: number) =>
    http.get<never, VoucherRuleRow[]>('/kingdee/voucher-rules', {
      params: {
        ...(enabledOnly == null ? {} : { enabledOnly }),
        ...(groupId == null ? {} : { groupId }),
      },
    }),
  create: (data: VoucherRuleUpsertPayload) => http.post<never, VoucherRuleRow>('/kingdee/voucher-rules', data),
  update: (id: number, data: VoucherRuleUpsertPayload) =>
    http.put<never, VoucherRuleRow>(`/kingdee/voucher-rules/${id}`, data),
  remove: (id: number) => http.delete<never, void>(`/kingdee/voucher-rules/${id}`),
  groups: () => http.get<never, VoucherRuleGroup[]>('/kingdee/voucher-rule-groups'),
  createGroup: (data: { name: string; description?: string | null; sortNo?: number }) =>
    http.post<never, VoucherRuleGroup>('/kingdee/voucher-rule-groups', data),
  updateGroup: (id: number, data: { name: string; description?: string | null; sortNo?: number }) =>
    http.put<never, VoucherRuleGroup>(`/kingdee/voucher-rule-groups/${id}`, data),
  removeGroup: (id: number) => http.delete<never, void>(`/kingdee/voucher-rule-groups/${id}`),
  /** 上传 xlsx → 解析 + AI 映射 → 预览（不入库；AI 不可用降级 aiMapped=false）。 */
  importPreview: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return http.post<never, VoucherRuleImportPreview>('/kingdee/voucher-rules/import/preview', form);
  },
  /** 人工勾选/修正后确认入库（走 createRule 统一校验，rule_no 冲突整体 409）。 */
  importConfirm: (rows: VoucherRuleUpsertPayload[], defaultGroupId?: number | null) =>
    http.post<never, VoucherRuleRow[]>('/kingdee/voucher-rules/import/confirm', {
      rows,
      defaultGroupId: defaultGroupId ?? null,
    }),
  /** 模板下载（后端生成 xlsx，带鉴权头走 blob）。 */
  downloadTemplate: async (): Promise<void> => {
    const response = await http.get<never, AxiosResponse<Blob>>('/kingdee/voucher-rules/import-template', {
      responseType: 'blob',
    });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = '规则导入模板.xlsx';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },
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
  getAppConfig: () => http.get<never, FeishuAppConfigView>('/feishu/app-config'),
  updateAppConfig: (data: FeishuAppConfigPayload) => http.put<never, FeishuAppConfigView>('/feishu/app-config', data),
  verifyAppConfig: (data: { appId?: string; appSecret?: string }) =>
    http.post<never, FeishuAppConfigView>('/feishu/app-config/verify', data),
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
  unlock: (period: string) => http.post<never, ClosingPeriod>(`/closing/periods/${period}/unlock`),
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
  /** 多选账户：重复键序列化（accountIds=1&accountIds=2），Spring @RequestParam List 原生识别。 */
  accountIds?: number[];
  keyword?: string;
  from?: string;
  to?: string;
  sourceSystem?: string;
  syncJobNo?: string;
  requestId?: string;
  /** 跨公司权限用户可选定公司主体；不传=全部可见公司，无权限用户传值会被服务端 403。 */
  companyId?: number;
  /** W8（2026-09-20）顶栏多选主体：多个公司 id（与 companyId 同给时服务端以 companyIds 为准）。 */
  companyIds?: number[];
  /** WP-C Excel 式逐列筛选：账号后 4/6 位（bank_account_no LIKE '%suffix'）。 */
  accountNoSuffix?: string;
  /** 借贷方向 C/D（仅流水）。 */
  loanCode?: string;
  /** 收付方名称模糊（仅流水）。 */
  counterparty?: string;
  /** 银行流水号模糊（仅流水）。 */
  statementNo?: string;
  /** 带符号金额区间（借方为负；仅流水）。 */
  minAmount?: number;
  maxAmount?: number;
  /** 币种语义值：CNY 展开命中 {CNY,10,01}。 */
  currency?: string;
};

/** axios 默认把数组序列化成 `key[]=1`（Spring 不识别）；数组改重复键拼 URL，其余走 axios params。 */
const splitArrayQuery = (params: BankDataQueryParams): { path: string; rest: Omit<BankDataQueryParams, 'accountIds' | 'companyIds'> } => {
  const rest = { ...params } as Record<string, unknown>;
  const accountIds = rest.accountIds as number[] | undefined;
  delete rest.accountIds;
  const companyIds = rest.companyIds as number[] | undefined;
  delete rest.companyIds;
  const search = new URLSearchParams();
  (accountIds || []).filter((id) => Number.isSafeInteger(id) && id > 0).forEach((id) => search.append('accountIds', String(id)));
  (companyIds || []).filter((id) => Number.isSafeInteger(id) && id > 0).forEach((id) => search.append('companyIds', String(id)));
  const query = search.toString();
  return { path: query ? `?${query}` : '', rest: rest as Omit<BankDataQueryParams, 'accountIds' | 'companyIds'> };
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
  queryProjection: <T>(resource: string, params: BankDataQueryParams) => {
    const { path, rest } = splitArrayQuery(params);
    return http.get<never, BankDataProjectionPage<T>>(`/bank-data/${resource}${path}`, { params: rest });
  },
  /** 银行流水一键转入标准流水（流水与入账）；服务端按银行流水行的公司归属落批次。 */
  transferFromBankdata: (data: { statementIds: number[] }) =>
    http.post<never, StatementTransferResult>('/statements/transfer-from-bankdata', data),
  /**
   * 一键 AI 制证（2026-09-16；2026-09-17 扩 DRAFT；2026-09-21 DRAFT 异步化）：
   * 转入 → AI 建议 → DRAFT=提交**后台任务**立即返回（进度/结果去凭证中心看）/
   * PUSH=复核内化后同步推送金蝶（返回同步结果）。
   */
  aiVoucher: (data: { statementIds: number[]; mode?: 'DRAFT' | 'PUSH' }) =>
    http.post<never, AiVoucherSubmitResult>('/bank-data/statements/ai-voucher', data),
  /** AI 制证后台任务：本公司最近一个任务（凭证中心轮询，含逐行结果与失败原因）。 */
  latestAiVoucherJob: () => http.get<never, AiVoucherJobResult | null>('/bank-data/ai-voucher-jobs/latest'),
  /** AI 制证后台任务详情（按任务号；跨公司需 bankdata:cross-company:view）。 */
  aiVoucherJob: (id: number) => http.get<never, AiVoucherJobResult>(`/bank-data/ai-voucher-jobs/${id}`),
  /** 定时同步计划（V25）：读取全部计划时刻（查看权限即可读）。 */
  listSchedules: () => http.get<never, BankSyncScheduleRow[]>('/bank-sync-schedules'),
  /** 新建计划时刻（HH:mm，禁整点/半点；bank:manage）。 */
  createSchedule: (executeHhmm: string) => http.post<never, BankSyncScheduleRow>('/bank-sync-schedules', { executeHhmm }),
  /** 启用/停用计划（bank:manage）。 */
  updateScheduleEnabled: (id: number, enabled: boolean) => http.put<never, void>(`/bank-sync-schedules/${id}/enabled/${enabled}`),
  /** 删除计划（bank:manage）。 */
  deleteSchedule: (id: number) => http.delete<never, void>(`/bank-sync-schedules/${id}`),
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
    const { path, rest } = splitArrayQuery(params as BankDataQueryParams);
    const response = await http.get<never, AxiosResponse<Blob>>(`/bank-data/${resource}/export${path}`, { params: rest, responseType: 'blob' });
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

/**
 * 账号级界面偏好（V35 表格内核口径③：跨设备一致，不是本机 localStorage）。
 *
 * `scope` 是调用方定义的命名空间（如 bankdata.balances），按账号 + scope 唯一。
 * payload 是前端自己的不透明 JSON 快照，服务端只做「合法 JSON + 长度」校验。
 */
export const preferenceApi = {
  get: (scope: string) => http.get<never, AccountPreference>(`/preferences/${encodeURIComponent(scope)}`),
  put: (scope: string, payload: string) =>
    http.put<never, AccountPreference>(`/preferences/${encodeURIComponent(scope)}`, { payload }),
};
