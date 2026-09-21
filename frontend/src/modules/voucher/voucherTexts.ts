import type { VoucherGroupFilter, VoucherRuleLine } from './types';

/**
 * V34 凭证模块文案与纯函数工具（状态映射 / 金额大写 / 银行账号识别）。
 * 全部为无副作用纯函数，便于复用与测试。
 */

/** 凭证中心状态签（与后端 VoucherGroupService bucket 对齐）。 */
export function voucherStatusTag(row: { pushStatus: string | null; reviewStatus: string; voucherNo: string | null }):
  { color: string; text: string } {
  // W8：补 GL_FAILED（规则引擎推送失败）——此前落进「待推送」，失败行被当成可推送的新行，
  // 用户看不出它推送过且失败了（pushMessage 只在 tooltip 里）。
  // W10（V39）：已撤回 —— 终态，优先于推送/复核状态判定（撤回行可能保留 FAILED 的推送痕迹）
  if (row.reviewStatus === 'WITHDRAWN') return { color: 'default', text: '已撤回' };
  if (row.pushStatus === 'PUSHED' || row.pushStatus === 'GL_PUSHED') return { color: 'green', text: '已推送' };
  if (row.pushStatus === 'FAILED' || row.pushStatus === 'GL_FAILED') return { color: 'red', text: '推送失败' };
  if (row.reviewStatus === 'APPROVED') return { color: 'orange', text: '待推送' };
  if (row.reviewStatus === 'PENDING') return { color: 'blue', text: '待复核' };
  if (row.reviewStatus === 'REJECTED') return { color: 'red', text: '已驳回' };
  return { color: 'default', text: row.reviewStatus || '--' };
}
export const VOUCHER_GROUP_FILTERS: Array<{ key: VoucherGroupFilter; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'DRAFT', label: '待复核' },
  { key: 'PENDING', label: '待推送' },
  { key: 'PUSHED', label: '已推送' },
  { key: 'FAILED', label: '推送失败' },
  { key: 'WITHDRAWN', label: '已撤回' },
];

/** 大写金额（人民币），支持到分；零与空值显式处理。例：286400 → 贰拾捌万陆仟肆佰元整 */
export function toChineseAmount(value: number | string | null | undefined): string {
  const amount = typeof value === 'string' ? Number(value) : value;
  if (amount == null || !Number.isFinite(amount)) return '--';
  const negative = amount < 0;
  // 四舍五入到分，按「分」拆整数部分与角分。
  const cents = Math.round(Math.abs(amount) * 100);
  const upperDigits = '零壹贰叁肆伍陆柒捌玖';
  const units = ['', '拾', '佰', '仟'];
  const bigUnits = ['', '万', '亿', '兆'];

  if (cents === 0) return (negative ? '负' : '') + '零元整';

  const intPart = Math.floor(cents / 100);
  const jiao = Math.floor((cents % 100) / 10);
  const fen = cents % 10;

  let intText;
  if (intPart > 0) {
    const groups: number[] = [];
    let rest = intPart;
    while (rest > 0) {
      groups.push(rest % 10000);
      rest = Math.floor(rest / 10000);
    }
    const groupTexts = groups.map((group, groupIndex) => {
      let text = '';
      let zeroPending = false;
      let started = false;
      for (let i = 3; i >= 0; i--) {
        const digit = Math.floor(group / 10 ** i) % 10;
        if (digit === 0) {
          if (started) zeroPending = true;
        } else {
          if (zeroPending && text) text += '零';
          zeroPending = false;
          started = true;
          text += upperDigits[digit] + units[i];
        }
      }
      if (!text) return '';
      return text + bigUnits[groupIndex];
    });
    intText = groupTexts.reverse().join('').replace(/零+$/, '') + '元';
  } else {
    intText = '';
  }

  let decimalText = '';
  if (jiao === 0 && fen === 0) {
    decimalText = intText ? '整' : '零元整';
  } else {
    if (intText && jiao === 0 && fen > 0) decimalText += '零';
    if (jiao > 0) decimalText += upperDigits[jiao] + '角';
    if (fen > 0) decimalText += upperDigits[fen] + '分';
  }

  const text = (negative ? '负' : '') + intText + decimalText;
  return text || '零元整';
}

/**
 * 银行账号识别（V34 ⑧，启发式）：按现有账户数据的位数/前缀特征映射银行。
 * 识别不出时返回 null —— 前端强制手选，绝不猜测兜底。
 *   - 6222 前缀 + 16 位数字 → 中信银行（seed 基本户/一般户实证特征）
 *   - 7559 前缀 + 16 位数字 → 招商银行（招行对公常见段）
 */
export function detectBankCode(accountNumber: string): { code: string; bankName: string } | null {
  const digits = (accountNumber || '').trim();
  if (!/^\d{8,64}$/.test(digits)) return null;
  if (digits.length === 16 && digits.startsWith('6222')) return { code: 'CITIC', bankName: '中信银行' };
  if (digits.length === 16 && digits.startsWith('7559')) return { code: 'CMB', bankName: '招商银行' };
  return null;
}

/** 分录模板行的可读摘要（大类规则列表的默认借/贷列）。 */
export function lineSummary(line: VoucherRuleLine): string {
  if (!line || !line.account) return '--';
  const name = line.name ? ` ${line.name}` : '';
  return `${line.account}${name}`;
}

/** 分摊策略文案。 */
export function shareText(share: string | null | undefined): string {
  switch (share) {
    case 'FULL': return '全额';
    case 'EQUAL': return '均摊';
    case 'MANUAL': return '人工填金额';
    default: return share || '--';
  }
}

/** 维度来源文案。 */
export function dimensionText(dimension: string | null | undefined): string {
  switch (dimension) {
    case 'BANK_ACCOUNT': return '银行账户';
    case 'ORG': return '公司主体(组织)';
    case 'EMPLOYEE': return '员工';
    case 'SUPPLIER': return '供应商';
    case 'CUSTOMER': return '客户';
    case 'COUNTERPARTY': return '对手方';
    case 'FIXED': return '固定值';
    case 'BY_SUMMARY_BRANCH': return '按摘要分支';
    case 'NONE': return '无';
    default: return dimension || '--';
  }
}
