import type { PageResponse } from '../shared/api';

export type BankAccount = {
  id: number;
  bankCode: string;
  accountName: string;
  maskedAccountNumber: string;
  currency: string;
  availableBalance: number | string;
  status: string;
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

export type BankDataProjection = {
  id: string | number;
  sourceSystem?: string;
  sourceRecordId?: string;
  status?: string;
  occurredAt?: string;
  accountMasked?: string;
  accountName?: string;
  amount?: number | string;
  currency?: string;
  direction?: string;
  summary?: string;
  requestId?: string;
  jobNo?: string;
  syncJobNo?: string;
  lastSyncedAt?: string;
  updatedAt?: string;
  sourceMode?: string;
  channelMode?: string;
  simulated?: boolean;
};

export type BankDataProjectionPage = PageResponse<BankDataProjection> & {
  enabled?: boolean;
  status?: string;
  message?: string;
  requestId?: string;
  sourceSystem?: string;
  lastSyncedAt?: string;
  simulated?: boolean;
};
