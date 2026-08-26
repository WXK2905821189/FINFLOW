export type User = { id: number; username: string; email: string; phone?: string; status: '启用' | '停用'; roles: string[]; permissions: string[] };
export type Transaction = { id: string; date: string; bank: string; counterparty: string; summary: string; amount: number; type: '收入' | '支出'; status: '已入账' | '处理中' | '失败' };
export type TransferForm = { bankCode: string; payeeName: string; payeeAccount: string; payeeBank: string; amount: number; remark: string };
