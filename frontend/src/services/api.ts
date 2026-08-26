import { http } from './http';
import type {
  AuthTokenResponse,
  PageResponse,
  StatementAuditEvent,
  StatementDashboard,
  StatementDetail,
  StatementImportBatch,
  StatementImportRequest,
  StatementRecord,
  StatementReviewRequest,
  User,
} from '../types';

export const authApi = {
  login: (username: string, password: string) => http.post<never, AuthTokenResponse>('/auth/login', { username, password }),
  register: (data: { username: string; email: string; password: string }) => http.post<never, User>('/auth/register', data),
  me: () => http.get<never, User>('/auth/me'),
};

type StatementListParams = {
  page?: number;
  size?: number;
  batchId?: number;
  reviewStatus?: string;
  pushStatus?: string;
  validationStatus?: string;
};

type BatchListParams = { page?: number; size?: number; status?: string };

// These paths mirror the published statement DTOs and are intentionally isolated
// so a controller-path change does not leak into pages or shared finance APIs.
export const statementApi = {
  import: (data: StatementImportRequest) => http.post<never, StatementImportBatch>('/statement-imports', data),
  dashboard: () => http.get<never, StatementDashboard>('/reconciliation/dashboard'),
  listBatches: (params: BatchListParams) => http.get<never, PageResponse<StatementImportBatch>>('/statement-imports', { params }),
  list: (params: StatementListParams) => http.get<never, PageResponse<StatementRecord>>('/statements', { params }),
  get: (id: number) => http.get<never, StatementDetail>(`/statements/${id}`),
  review: (id: number, data: StatementReviewRequest) => http.post<never, StatementRecord>(`/statements/${id}/review`, data),
  pushVoucher: (id: number) => http.post<never, StatementRecord>(`/statements/${id}/voucher-push`),
};
