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
