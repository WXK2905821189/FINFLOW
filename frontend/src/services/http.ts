import axios from 'axios';
import { message } from 'antd';

export const http = axios.create({ baseURL: '/api', timeout: 30000 });
http.interceptors.request.use((config) => { const token = localStorage.getItem('finflow-token'); if (token) config.headers.Authorization = `Bearer ${token}`; return config; });
http.interceptors.response.use((response) => response.data, (error) => { if (error.response?.status === 401) { localStorage.removeItem('finflow-token'); window.location.assign('/login'); } message.error(error.response?.data?.message ?? '请求未能完成，请稍后重试'); return Promise.reject(error); });
