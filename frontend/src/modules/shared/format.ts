// Shared value formatting and status classification helpers.
// Kept free of React and antd imports so they can be unit-tested standalone.
export const money = (value?: number | string) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '--';
};

export const dateTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '--');

/** Date-only fields such as 起息日 valueDate, serialized by Jackson as `yyyy-MM-dd`. */
export const dateOnly = (value?: string) => (value ? value.slice(0, 10) : '--');

export const displayValue = (value?: string | number | null) => (value === undefined || value === null || value === '' ? '--' : String(value));

export const hasAnyStatus = (status: string | undefined, patterns: RegExp[]) => Boolean(status && patterns.some((pattern) => pattern.test(status)));

export const isUnavailableStatus = (status?: string) => hasAnyStatus(status, [/DISABLED/i, /UNAVAILABLE/i, /NOT_ENABLED/i, /NOT_CONFIGURED/i, /未启用/, /不可用/]);

// isSimulatedStatus was removed (mock-clean 2026-09-04): production ships no simulated data path,
// so no caller ever needs to classify a "simulated" status anymore.

export const isFailedStatus = (status?: string) => hasAnyStatus(status, [/FAILED/i, /ERROR/i, /REJECTED/i, /INVALID/i, /失败/, /异常/]);

export const cleanText = (value?: string | number | null) => displayValue(value);

/**
 * Account-level direct-connect tri-state. DIRECT_CONNECTED requires both proofs server-side
 * (real adapter assembled AND at least one successful real sync for this account); ONBOARDED
 * means the bank is wired but this account has not been verified by a real sync yet - it is
 * deliberately NOT presented as connected.
 */
export const DIRECT_STATUS_TEXT: Record<string, string> = {
  DIRECT_CONNECTED: '账户已直联可查',
  ONBOARDED: '已开通，待首次真实同步',
  NOT_CONNECTED: '未接入直联',
};

export const directStatusText = (status?: string) =>
  (status && DIRECT_STATUS_TEXT[status]) || '直联状态未知';

export const isDirectConnected = (status?: string) => status === 'DIRECT_CONNECTED';

export const maskAccountDisplay = (value?: string | number | null) => {
  const text = displayValue(value);
  if (text === '--' || /\*/.test(text)) return text;
  return text.replace(/\d{8,}/g, (match) => `${match.slice(0, 4)}****${match.slice(-4)}`);
};

export const statusColor = (status?: string) => {
  if (!status) return 'default';
  // Account-level direct-connect tri-state (server-side truth, never a global flag).
  if (/DIRECT_CONNECTED/.test(status)) return 'green';
  if (/ONBOARDED/.test(status)) return 'gold';
  if (/NOT_CONNECTED/.test(status)) return 'red';
  if (/(APPROVED|PUSHED|COMPLETED|VALID|SUCCESS)/.test(status)) return 'green';
  if (/(PENDING|PROCESSING|NOT_PUSHED)/.test(status)) return 'gold';
  if (/(REJECTED|INVALID|FAILED|ERROR)/.test(status)) return 'red';
  return 'blue';
};
