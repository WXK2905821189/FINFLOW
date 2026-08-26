import { create } from 'zustand';
import type { User } from '../types';
const demoUser: User = { id: 1, username: 'admin', email: 'admin@finflow.local', phone: '138****0000', status: '启用', roles: ['系统管理员'], permissions: ['dashboard:view', 'user:manage', 'transfer:create', 'transaction:view'] };
type AuthStore = { user: User | null; hydrate: () => void; login: (user: User) => void; logout: () => void; hasPermission: (code: string) => boolean };
export const useAuthStore = create<AuthStore>((set, get) => ({ user: null, hydrate: () => { if (localStorage.getItem('finflow-token')) set({ user: demoUser }); }, login: (user) => { localStorage.setItem('finflow-token', 'demo-token'); set({ user }); }, logout: () => { localStorage.removeItem('finflow-token'); set({ user: null }); }, hasPermission: (code) => Boolean(get().user?.permissions.includes(code)) }));
