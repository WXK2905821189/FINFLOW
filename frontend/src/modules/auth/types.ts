export type User = {
  id: number;
  username: string;
  email: string;
  phone?: string;
  status: string;
  /** 所属公司主体名（/auth/me 返回；数据查询页用于展示当前主体）。 */
  companyName?: string;
  roles: string[];
  permissions: string[];
};

export type AuthTokenResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
};
