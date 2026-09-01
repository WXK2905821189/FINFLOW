// Shared value formatting and status classification helpers.
// Kept free of React and antd imports so they can be unit-tested standalone.
export const money = (value?: number | string) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '--';
};

export const dateTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '--');

export const displayValue = (value?: string | number | null) => (value === undefined || value === null || value === '' ? '--' : String(value));

export const hasAnyStatus = (status: string | undefined, patterns: RegExp[]) => Boolean(status && patterns.some((pattern) => pattern.test(status)));

export const isUnavailableStatus = (status?: string) => hasAnyStatus(status, [/DISABLED/i, /UNAVAILABLE/i, /NOT_ENABLED/i, /NOT_CONFIGURED/i, /未启用/, /不可用/]);

export const isSimulatedStatus = (status?: string) => hasAnyStatus(status, [/SIMULATED/i, /MOCK/i, /SANDBOX/i, /模拟/]);

export const isFailedStatus = (status?: string) => hasAnyStatus(status, [/FAILED/i, /ERROR/i, /REJECTED/i, /INVALID/i, /失败/, /异常/]);

export const cleanText = (value?: string | number | null) => displayValue(value);

export const maskAccountDisplay = (value?: string | number | null) => {
  const text = displayValue(value);
  if (text === '--' || /\*/.test(text)) return text;
  return text.replace(/\d{8,}/g, (match) => `${match.slice(0, 4)}****${match.slice(-4)}`);
};

export const statusColor = (status?: string) => {
  if (!status) return 'default';
  if (/(APPROVED|PUSHED|COMPLETED|VALID|SUCCESS)/.test(status)) return 'green';
  if (/(PENDING|PROCESSING|NOT_PUSHED)/.test(status)) return 'gold';
  if (/(REJECTED|INVALID|FAILED|ERROR)/.test(status)) return 'red';
  return 'blue';
};
