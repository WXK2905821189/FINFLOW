export type User = {
  id: number;
  username: string;
  email: string;
  phone?: string;
  status: string;
  roles: string[];
  permissions: string[];
};

export type AuthTokenResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
};
