import axios from 'axios';
import type { ApiEnvelope } from '../types';

const ACCESS_TOKEN_KEY = 'finflow.access-token';

export class ApiRequestError extends Error {
  constructor(message: string, readonly status?: number, readonly code?: number) {
    super(message);
    this.name = 'ApiRequestError';
  }
}

export const getAccessToken = () => sessionStorage.getItem(ACCESS_TOKEN_KEY);
export const setAccessToken = (token: string) => sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
export const clearAccessToken = () => sessionStorage.removeItem(ACCESS_TOKEN_KEY);

export const http = axios.create({ baseURL: '/api', timeout: 30000 });

http.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiEnvelope<unknown>;
    if (body?.code !== 0) throw new ApiRequestError(body?.message || '请求未能完成', response.status, body?.code);
    return body.data as never;
  },
  (error) => {
    const status = error.response?.status as number | undefined;
    const body = error.response?.data as Partial<ApiEnvelope<unknown>> | undefined;
    const requestUrl = String(error.config?.url || '');
    if (status === 401 && !requestUrl.includes('/auth/login')) {
      clearAccessToken();
      if (!window.location.pathname.startsWith('/login')) window.location.assign('/login?reason=expired');
    }
    return Promise.reject(new ApiRequestError(body?.message || '请求未能完成，请稍后重试', status, body?.code));
  },
);
