import { create } from 'zustand';
import { authApi } from '../services/api';
import { clearAccessToken, getAccessToken, setAccessToken } from '../services/http';
import type { User } from '../types';

type AuthStatus = 'restoring' | 'authenticated' | 'anonymous';

type AuthStore = {
  user: User | null;
  status: AuthStatus;
  hydrate: () => Promise<void>;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  hasPermission: (code: string) => boolean;
};

export const useAuthStore = create<AuthStore>((set, get) => ({
  user: null,
  status: 'restoring',
  hydrate: async () => {
    if (!getAccessToken()) {
      set({ user: null, status: 'anonymous' });
      return;
    }
    try {
      const user = await authApi.me();
      set({ user, status: 'authenticated' });
    } catch {
      clearAccessToken();
      set({ user: null, status: 'anonymous' });
    }
  },
  login: async (username, password) => {
    const response = await authApi.login(username, password);
    setAccessToken(response.accessToken);
    set({ user: response.user, status: 'authenticated' });
  },
  logout: () => {
    const token = getAccessToken();
    if (token) void authApi.logout(token).catch(() => undefined);
    clearAccessToken();
    set({ user: null, status: 'anonymous' });
  },
  hasPermission: (code) => Boolean(get().user?.permissions.includes(code)),
}));
