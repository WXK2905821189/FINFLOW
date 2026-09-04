export type SystemAuditEvent = {
  id: number;
  actorId?: number;
  action: string;
  objectType: string;
  objectId?: string;
  requestId: string;
  result: string;
  detail?: string;
  createdAt: string;
};
