export type FeishuConnectionItem = {
  id: number;
  connectionCode: string;
  displayName: string;
  tenantAlias?: string;
  mode: string;
  status: string;
};

export type FeishuDestinationItem = {
  id: number;
  connectionId: number;
  destinationType: string;
  destinationKey: string;
  displayName: string;
  enabled: boolean;
};

export type FeishuPolicyItem = {
  id: number;
  eventType: string;
  destinationId: number;
  enabled: boolean;
  templateVersion: string;
};

export type FeishuOverview = {
  enabled: boolean;
  status: string;
  message: string;
  connections: FeishuConnectionItem[];
  destinations: FeishuDestinationItem[];
  policies: FeishuPolicyItem[];
};

export type FeishuAppConfigView = {
  appId: string | null;
  secretConfigured: boolean;
  secretHint: string | null;
  verified: boolean;
  verifiedAt: string | null;
  verifiedTenant: string | null;
  updatedAt: string | null;
  updatedBy: number | null;
};

export type FeishuAppConfigPayload = {
  appId?: string;
  /** null=保持 / ''=清除 / 非空=换新（明文永不回显） */
  appSecret?: string;
};

export type NotificationDelivery = {
  eventId: string;
  eventType: string;
  referenceNo?: string;
  severity: string;
  status: string;
  attemptCount: number;
  providerMessageId?: string;
  requestId: string;
  createdAt: string;
  sentAt?: string;
  lastError?: string;
};
