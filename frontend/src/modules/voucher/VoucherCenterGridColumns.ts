import { esc, copyChip, type GridColumn, type GridRow } from '../bank-access/grid/kernel';
import { dateTime, money } from '../shared/format';
import type { VoucherGroupRow } from './types';

/**
 * W10（WP-6）：凭证中心 Excel 内核列定义。
 *
 * 口径与 antd Table 版保持一致（列顺序 / 中文标题 / 状态签语义），换成内核列后
 * 自动获得列宽拖拽、本页排序、列头筛选、TSV 复制、列显隐与 CSV 导出能力。
 *
 * 两条硬约束（同 BankQueryGridColumns）：
 *  · cell 返回 HTML 字符串（内核 innerHTML 渲染），所有文本必须过 esc()；
 *  · k 不是真实行字段的列必须另给 text（纯文本取值），否则 TSV 复制 / 导出会出空白列。
 */

const tag = (text: string, cls: string) => '<span class="tag ' + cls + '">' + esc(text) + '</span>';

/** 行内动作按钮：内核 NO_SELECT 已放行 `.btn`，点击不会误触发整行勾选（WP-1 新交互）。 */
const action = (name: string, label: string, danger = false) =>
  '<button class="btn btn-sm' + (danger ? ' btn-danger' : '') + '" data-row-action="' + name + '">'
  + esc(label) + '</button>';

const isPushed = (row: VoucherGroupRow) => row.pushStatus === 'PUSHED' || row.pushStatus === 'GL_PUSHED';

/** 状态签：与 voucherTexts.voucherStatusTag 同口径（内核侧是 HTML 字符串，这里重写一份）。 */
export const voucherStatusHtml = (row: VoucherGroupRow): string => {
  if (row.reviewStatus === 'WITHDRAWN') return tag('已撤回', 'tag-muted');
  if (isPushed(row)) return tag('已推送', 'tag-ok');
  if (row.pushStatus === 'FAILED' || row.pushStatus === 'GL_FAILED') return tag('推送失败', 'tag-danger');
  if (row.reviewStatus === 'APPROVED') return tag('待推送', 'tag-warn');
  if (row.reviewStatus === 'PENDING') return tag('待复核', 'tag-info');
  if (row.reviewStatus === 'REJECTED') return tag('已驳回', 'tag-danger');
  return tag(row.reviewStatus || '--', 'tag-muted');
};

export interface VoucherCenterColumnOptions {
  canReview: boolean;
  canPush: boolean;
}

/** 凭证中心的十个列（含操作列）；操作按钮的可见性规则与 antd 版逐条对齐。 */
export function voucherCenterColumns(opts: VoucherCenterColumnOptions): GridColumn[] {
  return [
    {
      k: 'statementNo', t: '来源流水', w: 210, on: true, req: true, def: '必需，不可关闭',
      type: 'text', filter: 'text',
      cell: (row) => '<span class="mono">' + esc(row.statementNo)
        + '</span> <span class="tag tag-info">' + esc(String(row.statementCount ?? 1)) + ' 笔 → 1 张单据</span>',
      text: (row) => String(row.statementNo || ''),
    },
    {
      k: 'businessDate', t: '业务日期', w: 130, on: true, type: 'text', filter: 'date',
      cell: (row) => esc(dateTime(row.businessDate)),
      text: (row) => String(row.businessDate || ''),
    },
    {
      k: 'companyName', t: '公司主体', w: 150, on: true, type: 'text', filter: 'value',
      cell: (row) => (row.companyName ? esc(row.companyName) : '<span class="tag tag-warn">未归属</span>'),
      text: (row) => String(row.companyName || '未归属'),
    },
    {
      k: 'bankAccount', t: '银行账户', w: 170, on: true, type: 'text', filter: 'text',
      cell: (row) => (row.bankAccount
        ? '<span class="mono">' + esc(row.bankAccount) + '</span>'
        : '<span class="mono">--</span>'),
      text: (row) => String(row.bankAccount || ''),
    },
    {
      k: 'direction', t: '方向', w: 76, on: true, type: 'text', filter: 'value',
      cell: (row) => (row.direction === 'INCOME' ? tag('收', 'tag-ok')
        : row.direction === 'EXPENSE' ? tag('付', 'tag-warn') : esc(row.direction || '--')),
      text: (row) => (row.direction === 'INCOME' ? '收' : row.direction === 'EXPENSE' ? '付' : String(row.direction || '')),
    },
    {
      k: 'amount', t: '金额', w: 130, on: true, type: 'money', align: 'num', filter: 'num',
      cell: (row) => '<span class="mono">' + esc(money(row.amount)) + '</span>',
      text: (row) => String(row.amount ?? ''),
    },
    {
      k: 'summary', t: '摘要', w: 220, on: true, type: 'text', filter: 'text',
      cell: (row) => esc(row.summary || '--'),
      text: (row) => String(row.summary || ''),
    },
    {
      k: 'voucherNo', t: '金蝶凭证号', w: 150, on: true, type: 'text', filter: 'text',
      cell: (row) => (row.voucherNo
        ? '<span class="mono">' + esc(row.voucherNo) + '</span>' + copyChip(String(row.voucherNo), '复制凭证号')
        : '<span class="mono">--</span>'),
      text: (row) => String(row.voucherNo || ''),
    },
    {
      k: 'status', t: '状态', w: 160, on: true, type: 'text', filter: 'value', filterPlaceholder: '如 待推送',
      cell: (row) => voucherStatusHtml(row as unknown as VoucherGroupRow)
        + (row.pushMessage ? '<span class="row-2">' + esc(row.pushMessage) + '</span>' : ''),
      text: (row) => (row.pushMessage ? row.pushMessage : ''),
    },
    {
      k: 'actions', t: '操作', w: 320, on: true, req: true, def: '必需，不可关闭',
      type: 'text',
      cell: (row) => {
        const r = row as unknown as VoucherGroupRow;
        const parts: string[] = [action('doc', '查看凭证')];
        if (opts.canReview && r.reviewStatus === 'PENDING') {
          parts.push(action('approve', '通过'), action('reject', '驳回'));
        }
        if (opts.canPush && r.reviewStatus === 'APPROVED' && !isPushed(r)) {
          parts.push(action('push', r.pushStatus === 'FAILED' || r.pushStatus === 'GL_FAILED' ? '重试推送' : '推送'));
        }
        if (opts.canPush && r.reviewStatus === 'REJECTED') parts.push(action('reopen', '重新打开'));
        // W10（V39）：未推送金蝶的凭证可撤回（标记已撤回，流水回池可重新制证）
        if (opts.canPush && r.reviewStatus !== 'WITHDRAWN' && !isPushed(r)) parts.push(action('withdraw', '撤回', true));
        parts.push(action('trace', '追溯'));
        return parts.join(' ');
      },
      text: () => '',
    },
  ];
}

/** 行数据：内核只读展示字段，金额/笔数保持原始类型（money() 自己格式化）。 */
export function toVoucherGridRows(rows: VoucherGroupRow[]): GridRow[] {
  return rows as unknown as GridRow[];
}
