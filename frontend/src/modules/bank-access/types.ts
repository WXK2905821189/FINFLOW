import type { PageResponse } from '../shared/api';

export type BankAccount = {
  id: number;
  bankCode: string;
  accountName: string;
  maskedAccountNumber: string;
  currency: string;
  availableBalance: number | string;
  status: string;
  /** Per-account direct-connect status: DIRECT_CONNECTED | ONBOARDED | NOT_CONNECTED. */
  directStatus?: string;
  /** ISO timestamp of the latest successful real-adapter sync for this account. */
  lastRealSyncAt?: string;
};

export type ConnectionSummary = {
  connectionCode: string;
  displayName: string;
  providerType: string;
  enabled: boolean;
  status: string;
  lastCheckedAt?: string;
};

export type ConnectionOverview = {
  enabled: boolean;
  status: string;
  message: string;
  connections: ConnectionSummary[];
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

/** 运行日志行：/bank-data/sync-logs（bank_data_sync_log 真实同步作业事件流）。 */
export type BankSyncLogRow = {
  id: number;
  level: string;
  eventType: string;
  result: string;
  requestId?: string;
  bankRequestNo?: string;
  message?: string;
  createdAt: string;
};

export type DataQueryCapability = {
  capability: string;
  enabled: boolean;
  status: string;
  message: string;
};

export type BankSyncJobTrigger = {
  jobType: string;
  bankAccountId: number;
  connectionCode?: string;
  /** Optional adapter code (e.g. CMB for the real CMB adapter; blank falls back to the server default). */
  adapterCode?: string;
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

/**
 * 银行返回的流水字段（CMB trsQryByBreakPoint TRANSQUERYBYBREAKPOINT_Z2，30 字段直出）。
 *
 * 命名沿用银行自己的字段 ID，不做业务投影翻译——这样屏幕上每一行都能和银行导出的
 * 交易明细逐列对照。两处例外有意为之：
 *   - `signedAmount` 是银行带符号的 transAmount（D 借方为负、C 贷方为正），
 *     `amount` 仍是内部记账用的无符号金额；
 *   - `direction` 是由 loanCode 推导的 INCOME/EXPENSE，`loanCode` 本身才是银行的 C/D。
 */
export type BankDataStatementRow = {
  id: number;
  taskId?: number;
  rawMessageId?: number;
  contentSha256?: string;
  retentionUntil?: string;
  bankAccountId?: number;
  bankRequestNo?: string;
  statementNo?: string;
  transactionTime?: string;
  direction?: string;
  amount?: number | string;
  currency?: string;
  counterpartyName?: string;
  counterpartyAccountMasked?: string;
  summary?: string;
  validationStatus?: string;
  validationMessage?: string;
  createdAt?: string;
  /** 本方脱敏账号，仅投影查询返回。 */
  accountMasked?: string;
  bankAccountNo?: string;
  valueDate?: string;
  loanCode?: string;
  signedAmount?: number | string;
  textCode?: string;
  billNumber?: string;
  remarkTextClt?: string;
  reversalFlag?: string;
  acctOnlineBal?: number | string;
  extendedRemark?: string;
  ctpAcctNbr?: string;
  ctpBankName?: string;
  ctpBankAddress?: string;
  fatOrSonAccount?: string;
  fatOrSonCompanyName?: string;
  fatOrSonBankName?: string;
  fatOrSonBankAddress?: string;
  infoFlag?: string;
  businessName?: string;
  businessText?: string;
  requestNbr?: string;
  yurRef?: string;
  virtualNbr?: string;
  mchOrderNbr?: string;
  transCardNbr?: string;
  reserve?: string;
  /** 产出该行的同步任务号（血缘字段，仅投影查询填充）。 */
  taskNo?: string;
  taskRequestId?: string;
  taskStatus?: string;
  /** 行所属公司主体（跨公司权限用户可见；单公司用户恒为本公司）。 */
  companyName?: string;
};

/** 银行返回的余额字段（CMB NTQADINF ntqadinfz）。四余额口径不可互相替代，故全部直出。 */
export type BankDataBalanceRow = {
  id: number;
  taskId?: number;
  rawMessageId?: number;
  contentSha256?: string;
  retentionUntil?: string;
  bankAccountId?: number;
  accountMasked?: string;
  bankRequestNo?: string;
  availableBalance?: number | string;
  currency?: string;
  asOfTime?: string;
  /** 联机余额 onlblv：账户实际资金。 */
  onlineBalance?: number | string;
  /** 冻结余额 hldblv：司法冻结 + 银行冻结合计。 */
  frozenBalance?: number | string;
  /** 上日余额 accblv：联机余额 − 当日金融交易发生额。 */
  previousDayBalance?: number | string;
  vendorCurrencyCode?: string;
  branchCode?: string;
  bankAccountNo?: string;
  bankAccountName?: string;
  accountItem?: string;
  customerRelationNo?: string;
  /** 账户状态 stscod：A=活动 B=冻结 C=关户。 */
  accountStatus?: string;
  /** 开户日 opndat（银行口径 yyyyMMdd）。 */
  openDate?: string;
  /** 利率类型 inttyp：ZZZ=不计息等。 */
  interestType?: string;
  /** 存期 dpstxt。 */
  depositTerm?: string;
  /** 透支额度 lmtovr。 */
  overdraftLimit?: number | string;
  /** 利息码 intcod：S=子公司虚拟余额。 */
  interestCode?: string;
  /** 年利率 intrat。 */
  interestRate?: number | string;
  /** 到期日 mutdat（银行口径 yyyyMMdd）。 */
  maturityDate?: string;
  validationStatus?: string;
  validationMessage?: string;
  createdAt?: string;
  taskNo?: string;
  taskRequestId?: string;
  taskStatus?: string;
  /** 行所属公司主体（跨公司权限用户可见；单公司用户恒为本公司）。 */
  companyName?: string;
};

/** 银行数据查询页信封；records 直接是银行原始行，不再是统一业务投影。 */
export type BankDataProjectionPage<T> = PageResponse<T> & {
  enabled?: boolean;
  status?: string;
  message?: string;
  requestId?: string;
  sourceSystem?: string;
  lastSyncedAt?: string;
};

/** 一条已留存的银行原始响应（列表项，刻意不含报文体）。 */
export type BankRawMessage = {
  id: number;
  taskId?: number;
  taskNo?: string;
  bankAccountId?: number;
  adapterCode?: string;
  /** 银行侧请求号：与银行对账时定位该次调用。 */
  bankRequestNo?: string;
  /** 报文体摘要，用于留档比对与去重。 */
  contentSha256?: string;
  receivedAt?: string;
  retentionUntil?: string;
  /** 非空表示报文体已按保留策略清理，仅剩元数据。 */
  purgedAt?: string;
  /** 是否由 REAL 模式适配器产生——这是「是否真的连上了银行」的判据，模拟任务恒为 false。 */
  realDirect: boolean;
};

export type BankRawMessageDetail = BankRawMessage & {
  payload: string;
  payloadBytes: number;
  /** 银行解密后原始响应 verbatim（V23 起留存；旧报文或已清理为 null）。 */
  responsePayload?: string | null;
  responsePayloadBytes?: number;
  /** 请求要素 JSON：端点/功能码/加密前明文请求/耗时/HTTP 状态/附属交换（如首页余额快照）。 */
  requestEvidence?: string | null;
  /** 是否具备银行原文——重放接口的前置条件。 */
  hasBankRaw?: boolean;
};

/** 重放结果：用当前解析规则重跑银行原文，与当年入库视图逐路径对比。 */
export type BankRawReplayResult = {
  rawMessageId: number;
  adapterCode?: string;
  mappingVersion?: string;
  replayable: boolean;
  matches: boolean;
  storedEntryCount: number;
  replayedEntryCount: number;
  differences: string[];
  replayedPayload?: string | null;
  replayedAt?: string;
};

/** 任务级对账行：银行 Z1 口径合计 vs 平台入库口径。 */
export type BankTaskReconciliationRow = {
  taskId: number;
  taskNo?: string;
  adapterCode?: string;
  status?: string;
  windowStart?: string;
  windowEnd?: string;
  bankDebitNums?: number | null;
  bankDebitAmount?: number | null;
  bankCreditNums?: number | null;
  bankCreditAmount?: number | null;
  platformExpenseCount: number;
  platformIncomeCount: number;
  platformExpenseAmount: number;
  platformIncomeAmount: number;
  countConsistent?: boolean | null;
  amountConsistent?: boolean | null;
};

/** 公司主体下拉选项（/bank-data/company-options）。 */
export type CompanyOption = {
  id: number;
  name: string;
};
