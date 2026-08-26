import { http } from './http';
import type { TransferForm, User } from '../types';
export const authApi = { login: (username: string, password: string) => http.post('/auth/login', { username, password }), register: (data: Record<string, string>) => http.post('/auth/register', data), me: () => http.get('/auth/me') as Promise<User> };
export const bankApi = { supported: () => http.get('/banks'), transfer: (data: TransferForm) => http.post('/transfers', data) };
export const userApi = { list: () => http.get('/users') as Promise<User[]>, create: (data: Partial<User>) => http.post('/users', data), update: (id: number, data: Partial<User>) => http.put(`/users/${id}`, data), remove: (id: number) => http.delete(`/users/${id}`) };
export const transactionApi = { list: () => http.get('/transactions') };
