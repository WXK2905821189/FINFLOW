export type ApiEnvelope<T> = {
  code: number;
  message: string;
  data: T;
  timestamp: string;
};

export type PageResponse<T> = {
  page: number;
  size: number;
  total: number;
  records: T[];
};
