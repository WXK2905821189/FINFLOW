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
