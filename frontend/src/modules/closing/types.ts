export type ClosingPeriod = {
  id: number;
  period: string;
  status: string;
  totalCount: number;
  pendingCount: number;
  exceptionCount: number;
  unpostedCount: number;
  confirmedBy?: number;
  confirmedAt?: string;
  requestId?: string;
  note?: string;
  updatedAt: string;
};
