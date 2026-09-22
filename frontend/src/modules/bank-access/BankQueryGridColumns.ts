import { copyChip, esc, money, type GridColumn, type GridRow } from './grid/kernel';
import { ACCOUNT_STATUS_TEXT, LOAN_CODE_TEXT, currencyText } from './bankQueryTexts';
import { resolveBankName } from './useBankNames';
import { cleanText, dateTime, displayValue, statusColor } from '../shared/format';
import { statusTagText } from '../shared/dict';
import type { BankDataBalanceRow, BankDataStatementRow } from '../../types';

/**
 * 真实银行行 → V35 内核列定义。
 *
 * 与 demo（docs/ui-v34-demo.html）的关系：列**口径**照搬 demo（默认列收紧、必需列、
 * filter 声明、emptyZero 借贷双轨、条件格式），字段换成真实行字段（demo 用的是造出来的
 * BALANCE_ROWS / STMT_ROWS）。demo 里的 mock 字段名（lastSyncAt / connectStatus / balance …）
 * 在真实数据里并不存在，对应关系见每列的注释。
 *
 * 两条硬约束：
 *  · cell 返回 HTML 字符串（内核 innerHTML 渲染），所有文本必须过 esc()；
 *  · k 不是真实行字段的列必须另给 text（纯文本取值），否则 TSV 复制 / 导出会出空白列。
 */

/* ---------------- 共用小工具 ---------------- */

const isNum = (v: unknown) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));

/** 金额条件格式（对应 demo 的 cf: 'balance'）：负额红字。
 *  W16-B3（2026-09-21）：移除 cf-big 分支（金额≥100万的加粗 + 金色竖条）——用户反馈删除。 */
const moneyCf = (v: unknown) => {
  if (!isNum(v)) return '';
  const n = Number(v);
  if (n < 0) return 'cf-neg';
  return '';
};

/** 状态标签：复用全站字典（含 VALID / INVALID 的中文），与 antd StatusTag 同一套取值。 */
const statusTag = (status?: string) => {
  if (!status) return '<span class="mono">--</span>';
  const color = statusColor(status);
  const cls = color === 'green' ? 'tag-ok'
    : color === 'red' ? 'tag-danger'
      : color === 'gold' ? 'tag-warn'
        : color === 'blue' ? 'tag-info' : 'tag-muted';
  return '<span class="tag ' + cls + '">' + esc(statusTagText(status)) + '</span>';
};

/** 银行中文名解析器：由页面把 useBankNames().resolve 透传进来（字典中心为唯一可维护源）。 */
type BankNameOf = (code?: string | null) => string;

/** 参数化列工厂：把 GridRow 收窄回真实行类型，列内部就能有类型提示。 */
const balance = (fn: (row: BankDataBalanceRow) => string) => (row: GridRow) => fn(row as BankDataBalanceRow);
const statement = <T extends BankDataStatementRow = BankDataStatementRow>(fn: (row: T) => string) =>
  (row: GridRow) => fn(row as T);

/** 跨公司权限用户才注入的公司主体列；未归属账户不能留空，否则看起来像「本公司」。 */
const companyColumn = (): GridColumn => ({
  k: 'companyName', t: '公司主体', w: 160, on: true, def: '跨公司', type: 'text', filter: 'value',
  cell: (row) => (row.companyName ? esc(row.companyName) : '<span class="tag tag-warn">未归属</span>'),
  text: (row) => String(row.companyName || '未归属'),
});

/** 详情入口：唯一的抽屉入口，设成必需列避免用户关掉后再也打不开。 */
const detailColumn = (): GridColumn => ({
  k: 'detail', t: '详情', w: 78, on: true, req: true, def: '必需，不可关闭', type: 'text', filter: null,
  cell: () => '<button class="btn btn-sm" data-row-action="detail">查看</button>',
  text: () => '',
});

/** 账号单元格：本方账号明文 + 复制标签；银行侧账号与系统账号不同时副行显示。 */
const accountCell = (masked?: string, bankNo?: string) => {
  const main = displayValue(masked || bankNo);
  const raw = String(bankNo || masked || '');
  return '<span class="mono">' + esc(main) + '</span>'
    + (raw ? copyChip(raw, '复制账号') : '')
    + (bankNo && bankNo !== masked ? '<span class="row-2 mono">' + esc(bankNo) + '</span>' : '');
};

/* ==================================================================
   余额查询
   ================================================================== */

/**
 * W8（2026-09-20）：余额行派生字段——「银行」「账号」列的 k（bankName/account）与真实行
 * 字段（bankCode/accountMasked）错位，内核筛选/排序/值勾选都读 row[col.k]，读到 undefined
 * 导致银行列筛选候选全是「(空)」（用户报障：银行字段读取不了，无法筛选）。
 * 灌数据前补派生字段（不改列 k，避免破坏已保存的列偏好）。
 */
export function decorateBalanceRows(
  rows: BankDataBalanceRow[],
  bankNameOf: BankNameOf = resolveBankName,
): (BankDataBalanceRow & { bankName: string; account: string })[] {
  return rows.map((row) => ({
    ...row,
    bankName: bankNameOf(row.bankCode),
    account: String(row.accountMasked || row.bankAccountNo || ''),
  }));
}

export const balanceGridColumns = ({ canCrossCompany, bankNameOf = resolveBankName }: { canCrossCompany: boolean; bankNameOf?: BankNameOf }): GridColumn[] => {  const cols: GridColumn[] = [
    {
      k: 'bankName', t: '银行', w: 110, on: true, def: '默认', type: 'text', filter: 'value',
      cell: balance((r) => esc(displayValue(bankNameOf(r.bankCode)))),
      text: balance((r) => bankNameOf(r.bankCode)),
    },
    {
      // V36 筛选服务端化：账号文本 → accountNoSuffix（bank_account_no LIKE '%后缀'）。
      // 「后缀」而非「包含」——占位文案写清，避免用户输前缀后误判为无数据。
      k: 'account', t: '账号', w: 220, on: true, req: true, def: '必需，不可关闭', type: 'text', filter: 'text', filterServer: true,
      filterPlaceholder: '账号后缀，如后 4 / 6 位',
      // 藏掉账号会让「没采集到账号」的账户看起来像正常数据，故设为必需列（同 demo 的直连状态）。
      cell: balance((r) => accountCell(r.accountMasked, r.bankAccountNo)),
      text: balance((r) => String(r.accountMasked || r.bankAccountNo || '')),
    },
    {
      k: 'asOfTime', t: '截止时间', w: 160, on: true, def: '默认', type: 'text', filter: 'date',
      cell: balance((r) => esc(dateTime(r.asOfTime))),
      text: balance((r) => dateTime(r.asOfTime)),
    },
    {
      // 币种列**有意**保持「仅本页」口径（不设 filterServer）：服务端 currency=CNY 会展开命中
      // {CNY,10,01}（银行码与 ISO 并存），而值勾选集合是原始代码的精确匹配——两边口径对不上，
      // 服务端化后会出现「全量合计 ≠ 本页行数」。等后端支持多代码集合参数后再迁。
      k: 'currency', t: '币种', w: 96, on: true, def: '默认', type: 'text', filter: 'value',
      cell: balance((r) => esc(currencyText(r.vendorCurrencyCode, r.currency))),
      text: balance((r) => currencyText(r.vendorCurrencyCode, r.currency)),
    },
    {
      // W8：余额旁「复制」小标签——复制纯数值（不带千分位），方便粘到对账单/Excel。
      k: 'availableBalance', t: '可用余额', w: 170, on: true, def: '默认', align: 'num', type: 'money', filter: 'num',
      cell: balance((r) => money(r.availableBalance)
        + (isNum(r.availableBalance) ? copyChip(String(r.availableBalance), '复制余额数值') : '')),
      cf: balance((r) => moneyCf(r.availableBalance)),
    },
    {
      k: 'onlineBalance', t: '联机余额', w: 150, on: false, align: 'num', type: 'money', filter: 'num',
      cell: balance((r) => money(r.onlineBalance)
        + (isNum(r.onlineBalance) ? copyChip(String(r.onlineBalance), '复制余额数值') : '')),
      cf: balance((r) => moneyCf(r.onlineBalance)),
    },
    {
      k: 'frozenBalance', t: '冻结余额', w: 150, on: false, align: 'num', type: 'money', filter: 'num',
      cell: balance((r) => money(r.frozenBalance)),
      cf: balance((r) => moneyCf(r.frozenBalance)),
    },
    {
      k: 'previousDayBalance', t: '上日余额', w: 150, on: false, align: 'num', type: 'money', filter: 'num',
      cell: balance((r) => money(r.previousDayBalance)),
      cf: balance((r) => moneyCf(r.previousDayBalance)),
    },
    {
      k: 'bankAccountName', t: '户名', w: 230, on: false, type: 'text', filter: 'text',
      cell: balance((r) => esc(displayValue(r.bankAccountName))),
      text: balance((r) => String(r.bankAccountName || '')),
    },
    {
      k: 'accountStatus', t: '账户状态', w: 104, on: false, type: 'text', filter: 'value',
      cell: balance((r) => (r.accountStatus
        ? '<span class="tag tag-muted">' + esc(ACCOUNT_STATUS_TEXT[r.accountStatus] || r.accountStatus) + '</span>'
        : '<span class="mono">--</span>')),
      text: balance((r) => (r.accountStatus ? (ACCOUNT_STATUS_TEXT[r.accountStatus] || r.accountStatus) : '')),
    },
    {
      k: 'validationStatus', t: '校验状态', w: 130, on: false, type: 'text', filter: 'value',
      cell: balance((r) => statusTag(r.validationStatus)
        + (r.taskStatus === 'UNKNOWN' ? ' <span class="tag tag-warn">待核验</span>' : '')),
      text: balance((r) => statusTagText(r.validationStatus) + (r.taskStatus === 'UNKNOWN' ? ' 待核验' : '')),
      cf: balance((r) => (r.validationStatus && r.validationStatus !== 'VALID' ? 'cf-late' : '')),
    },
    {
      k: 'taskNo', t: '同步任务号', w: 170, on: false, type: 'text', filter: 'text',
      cell: balance((r) => (r.taskNo ? '<span class="mono">' + esc(r.taskNo) + '</span>' : '<span class="mono">--</span>')),
      text: balance((r) => String(r.taskNo || '')),
    },
  ];
  if (canCrossCompany) cols.push(companyColumn());
  cols.push(detailColumn());
  return cols;
};

/* ==================================================================
   流水查询
   ================================================================== */

/** 流水行 + 借贷双轨派生字段（decorateStatementRows 灌入）。 */
export type StatementGridRow = BankDataStatementRow & { debitAmount: number; creditAmount: number };

/**
 * 借贷双轨列（借方发生额 / 贷方发生额）需要行上真的有这两个字段：内核排序、区间筛选、
 * 值勾选、TSV 复制都直接读 row[col.k]，所以必须在**灌数据前**把派生字段写进行对象。
 * 0 表示「这一侧没有发生额」，靠列的 emptyZero 归入空值口径（排序恒沉底、区间筛不命中）。
 * W8：补灌 accountLabel（本方账户列 k 与真实字段错位，同余额页 bankName/account）。
 */
export function decorateStatementRows<T extends BankDataStatementRow>(rows: T[]): (T & { debitAmount: number; creditAmount: number; accountLabel: string })[] {
  return rows.map((row) => {
    const magnitude = isNum(row.amount) ? Number(row.amount) : Math.abs(Number(row.signedAmount) || 0);
    return {
      ...row,
      debitAmount: row.loanCode === 'D' ? magnitude : 0,
      creditAmount: row.loanCode === 'C' ? magnitude : 0,
      accountLabel: String(row.accountMasked || row.bankAccountNo || ''),
    };
  });
}

export const statementGridColumns = ({ canCrossCompany }: { canCrossCompany: boolean }): GridColumn[] => {
  const cols: GridColumn[] = [
    {
      // W16-B5 排序服务端化：交易时间升降序由服务端对全量流水执行（用户反馈：排序要对
      // 所有流水生效，本页排序对财务对账无意义）。内核收到 sortServer 标记后不再本地重排。
      k: 'transactionTime', t: '交易时间', w: 160, on: true, def: '默认', type: 'text', filter: 'date', sortServer: true,
      cell: statement((r) => esc(dateTime(r.transactionTime))),
      text: statement((r) => dateTime(r.transactionTime)),
    },
    {
      // V36 筛选服务端化：借贷值勾选 → 请求参数 loanCode（C/D 互斥，服务端语义完全一致）。
      k: 'loanCode', t: '借贷', w: 96, on: true, def: '默认', type: 'text', filter: 'value', filterServer: true,
      cell: statement((r) => (r.loanCode
        ? '<span class="dir-tag ' + (r.loanCode === 'D' ? 'dir-debit' : 'dir-credit') + '">'
          + esc(LOAN_CODE_TEXT[r.loanCode] || r.loanCode) + '</span>'
        : '<span class="mono">--</span>')),
      text: statement((r) => (r.loanCode ? (LOAN_CODE_TEXT[r.loanCode] || r.loanCode) : '')),
    },
    {
      k: 'debitAmount', t: '借方发生额', w: 150, on: true, def: '默认', align: 'num', type: 'money', filter: 'num', emptyZero: true,
      cell: statement<StatementGridRow>((r) => (r.debitAmount ? money(r.debitAmount) : '<span class="cf-mute">--</span>')),
      // text 必须显式给：emptyZero 语义下 0 = 这一侧没有发生额，导出应与界面一致留空，
      // 而不是让 rawCell 按数值列回落成 "0.00"（那是另一个口径的数）。
      text: statement<StatementGridRow>((r) => (r.debitAmount ? String(r.debitAmount) : '')),
      cf: statement<StatementGridRow>((r) => moneyCf(r.debitAmount)),
    },
    {
      k: 'creditAmount', t: '贷方发生额', w: 150, on: true, def: '默认', align: 'num', type: 'money', filter: 'num', emptyZero: true,
      cell: statement<StatementGridRow>((r) => (r.creditAmount ? money(r.creditAmount) : '<span class="cf-mute">--</span>')),
      text: statement<StatementGridRow>((r) => (r.creditAmount ? String(r.creditAmount) : '')),
      cf: statement<StatementGridRow>((r) => moneyCf(r.creditAmount)),
    },
    {
      k: 'acctOnlineBal', t: '交易后余额', w: 150, on: true, def: '默认', align: 'num', type: 'money', filter: 'num',
      cell: statement((r) => money(r.acctOnlineBal)),
      cf: statement((r) => moneyCf(r.acctOnlineBal)),
    },
    {
      // V36 筛选服务端化：流水号文本 → statementNo（服务端模糊匹配，包含语义一致）。
      k: 'statementNo', t: '流水号', w: 180, on: true, def: '默认', type: 'text', filter: 'text', filterServer: true,
      cell: statement((r) => (r.statementNo ? '<span class="mono">' + esc(r.statementNo) + '</span>' : '<span class="mono">--</span>')),
      text: statement((r) => String(r.statementNo || '')),
    },
    {
      // V36 筛选服务端化：收付方文本 → counterparty（服务端模糊匹配，包含语义一致）。
      k: 'counterparty', t: '收付方', w: 230, on: true, def: '默认', type: 'text', filter: 'text', filterServer: true,
      cell: statement((r) => esc(displayValue(r.counterpartyName))
        + (r.ctpAcctNbr ? '<span class="row-2 mono">' + esc(r.ctpAcctNbr) + '</span>' : '')),
      text: statement((r) => String(r.counterpartyName || '')),
    },
    {
      k: 'summary', t: '摘要', w: 230, on: true, def: '默认', type: 'text', filter: 'text',
      cell: statement((r) => esc(cleanText(r.businessText || r.remarkTextClt || r.summary || r.extendedRemark))),
      text: statement((r) => cleanText(r.businessText || r.remarkTextClt || r.summary || r.extendedRemark)),
    },
    {
      // 币种列**有意**保持「仅本页」口径：同余额页注释（服务端 CNY 展开 {CNY,10,01} 与值勾选精确匹配口径不一致）。
      k: 'currency', t: '币种', w: 90, on: true, def: '默认', type: 'text', filter: 'value',
      cell: statement((r) => esc(currencyText(r.vendorCurrencyCode ?? r.currency, r.currency))),
      text: statement((r) => currencyText(r.vendorCurrencyCode ?? r.currency, r.currency)),
    },
    {
      k: 'validationStatus', t: '状态', w: 140, on: true, req: true, def: '必需，不可关闭', type: 'text', filter: 'value',
      // 必需列：藏起来会让「校验未通过 / 待核验」的行和正常行长得一样，直接影响制证判断。
      cell: statement((r) => statusTag(r.validationStatus)
        + (r.taskStatus === 'UNKNOWN' ? ' <span class="tag tag-warn">待核验</span>' : '')),
      text: statement((r) => statusTagText(r.validationStatus) + (r.taskStatus === 'UNKNOWN' ? ' 待核验' : '')),
      cf: statement((r) => (r.validationStatus && r.validationStatus !== 'VALID' ? 'cf-late' : '')),
    },
    {
      // V36 筛选服务端化：带符号金额区间 → minAmount / maxAmount（服务端同口径：收款为正付款为负）。
      k: 'signedAmount', t: '金额（带符号）', w: 160, on: false, align: 'num', type: 'money', filter: 'num', filterServer: true,
      // 银行原文带符号金额，与银行导出的交易明细逐列比对的锚点，默认收起但保留。
      cell: statement((r) => money(r.signedAmount)),
    },
    {
      // V36 筛选服务端化：本方账户文本 → accountNoSuffix（bank_account_no LIKE '%后缀'）。
      // 注意是「后缀」不是「包含」——输入框占位文案必须写清，防止用户输前缀后误判为无数据。
      k: 'accountLabel', t: '本方账户', w: 210, on: false, type: 'text', filter: 'text', filterServer: true,
      filterPlaceholder: '账号后缀，如后 4 / 6 位',
      cell: statement((r) => accountCell(r.accountMasked, r.bankAccountNo)),
      text: statement((r) => String(r.accountMasked || r.bankAccountNo || '')),
    },
    {
      k: 'ctpAcctNbr', t: '对手方账号', w: 170, on: false, type: 'text', filter: 'text',
      cell: statement((r) => (r.ctpAcctNbr
        ? '<span class="mono">****' + esc(String(r.ctpAcctNbr).slice(-4)) + '</span>'
        : '<span class="mono">--</span>')),
      text: statement((r) => String(r.ctpAcctNbr || '')),
    },
    {
      k: 'taskNo', t: '同步任务号', w: 170, on: false, type: 'text', filter: 'text',
      cell: statement((r) => (r.taskNo ? '<span class="mono">' + esc(r.taskNo) + '</span>' : '<span class="mono">--</span>')),
      text: statement((r) => String(r.taskNo || '')),
    },
  ];
  if (canCrossCompany) cols.push(companyColumn());
  cols.push(detailColumn());
  return cols;
};
