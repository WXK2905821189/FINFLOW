import { useState } from 'react';
import { Alert, Button, Collapse, Descriptions, Input, InputNumber, Segmented, Space, Tag, message, type TableColumnsType } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { StatusTag } from '../shared/components';
import { dateTime, displayValue, cleanText, money, dateOnly, isUnavailableStatus, isFailedStatus } from '../shared/format';
import type { BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow } from '../../types';
import { ACCOUNT_STATUS_TEXT, accountStatusColor, BANK_NAME_TEXT, INFO_FLAG_TEXT, INTEREST_TYPE_TEXT, LOAN_CODE_TEXT, REVERSAL_TEXT, currencyText } from './bankQueryTexts';

/** 投影行类型：流水与余额二选一（列定义与详情抽屉共用）。 */
export type BankQueryRow = BankDataStatementRow | BankDataBalanceRow;

/** WP-C 列筛选提交载荷：key → 字符串值（金额区间键 minAmount/maxAmount 亦为字符串，页面侧转数值）。 */
export type ColumnFilterPatch = Record<string, string>;

export function BankProjectionState({ data }: { data?: BankDataProjectionPage<BankQueryRow> }) {
  if (!data) return null;
  const status = data.status;
  if (data.enabled === false || isUnavailableStatus(status)) {
    return <Alert className="phase-one-notice" type="error" showIcon message="真实银行直联未连接" description={<span>{data.message || '服务端未启用真实银行适配器，无法获取银行数据。'}{status && <> 服务端状态：<StatusTag status={status} />。</>}</span>} />;
  }
  if (isFailedStatus(status)) {
    return <Alert className="phase-one-notice" type="error" showIcon message="银行查询失败" description={<span>{data.message || '银行接口未成功返回，请检查同步任务或稍后重试。'}{status && <> 服务端状态：<StatusTag status={status} />。</>}</span>} />;
  }
  return <Alert className="phase-one-notice" type="success" showIcon message="已连接真实银行直联" description={<span>{data.message || '以下为真实银行直联返回的余额/流水数据。'}</span>} />;
}

/** 跨公司视图的公司主体列：仅持有 bankdata:cross-company:view 权限的用户注入（见 columns useMemo）。
 *  行值来自账户当前归属（未归属账户为空 → 标注「未归属」）。 */
export const COMPANY_COLUMN = {
  key: 'companyName',
  title: '公司主体',
  dataIndex: 'companyName',
  width: 150,
  ellipsis: true,
  render: (value?: string) => (value
    ? <span>{value}</span>
    : <Tag color="orange">未归属</Tag>),
};

/* ===================== WP-C 表头筛选器（Excel 式，映射服务端参数） ===================== */

/** 文本筛选：收付方 / 流水号。 */
function TextColumnFilter({ initialValue, placeholder, onApply }: {
  initialValue?: string;
  placeholder: string;
  onApply: (value: string) => void;
}) {
  const [draft, setDraft] = useState(initialValue || '');
  return (
    <Space direction="vertical" size={6} style={{ padding: 8 }}>
      <Input
        autoFocus
        allowClear
        value={draft}
        placeholder={placeholder}
        style={{ width: 200 }}
        onChange={(event) => setDraft(event.target.value)}
        onPressEnter={() => onApply(draft.trim())}
      />
      <Space>
        <Button type="primary" size="small" onClick={() => onApply(draft.trim())}>筛选</Button>
        <Button size="small" onClick={() => { setDraft(''); onApply(''); }}>清除</Button>
      </Space>
    </Space>
  );
}

/** 金额区间筛选：带符号金额（借方为负）。 */
function AmountRangeFilter({ initialValue, onApply }: {
  initialValue?: { min?: string; max?: string };
  onApply: (patch: ColumnFilterPatch) => void;
}) {
  const [minDraft, setMinDraft] = useState<string>(initialValue?.min || '');
  const [maxDraft, setMaxDraft] = useState<string>(initialValue?.max || '');
  const apply = (min: string, max: string) => onApply({ minAmount: min, maxAmount: max });
  return (
    <Space direction="vertical" size={6} style={{ padding: 8 }}>
      <Space size={4}>
        <InputNumber
          autoFocus
          placeholder="最小金额"
          value={minDraft === '' ? undefined : Number(minDraft)}
          style={{ width: 120 }}
          onChange={(value) => setMinDraft(value === null ? '' : String(value))}
        />
        <span>~</span>
        <InputNumber
          placeholder="最大金额"
          value={maxDraft === '' ? undefined : Number(maxDraft)}
          style={{ width: 120 }}
          onChange={(value) => setMaxDraft(value === null ? '' : String(value))}
        />
      </Space>
      <Space>
        <Button type="primary" size="small" onClick={() => apply(minDraft, maxDraft)}>筛选</Button>
        <Button size="small" onClick={() => { setMinDraft(''); setMaxDraft(''); apply('', ''); }}>清除</Button>
      </Space>
      <span className="muted" style={{ fontSize: 12 }}>带符号金额：收款为正、付款为负</span>
    </Space>
  );
}

/** 借贷筛选：C=贷方（收）/ D=借方（付）。 */
function LoanCodeFilter({ initialValue, onApply }: {
  initialValue?: string;
  onApply: (value: string) => void;
}) {
  return (
    <div style={{ padding: 8 }}>
      <Segmented
        value={initialValue || 'ALL'}
        options={[
          { label: '全部', value: 'ALL' },
          { label: '收（贷）', value: 'C' },
          { label: '付（借）', value: 'D' },
        ]}
        onChange={(value) => onApply(value === 'ALL' ? '' : String(value))}
      />
    </div>
  );
}

/** 可用余额复制标签：复制原始数值（无千分位），clipboard API 失败时退回 execCommand。 */
function CopyMoneyButton({ value }: { value: number | string }) {
  const text = String(value);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
    } catch {
      try {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        textarea.remove();
        message.success('已复制');
      } catch {
        message.error('复制失败，请手动选择复制');
      }
    }
  };
  return (
    <Button
      type="text"
      size="small"
      icon={<CopyOutlined />}
      aria-label="复制可用余额"
      style={{ marginLeft: 2, padding: 0, height: 'auto' }}
      onClick={(event) => { event.stopPropagation(); void copy(); }}
    />
  );
}

/* ===================== 流水列（WP-C：表头筛选 + 币种列） ===================== */

export type StatementColumnOptions = {
  openDetail: (row: BankDataStatementRow) => void;
  /** 当前筛选值（用于 filterDropdown 回显与 filteredIcon 高亮）。 */
  activeFilters: ColumnFilterPatch;
  onColumnFilter: (patch: ColumnFilterPatch) => void;
};

export const statementColumns = ({ openDetail, activeFilters, onColumnFilter }: StatementColumnOptions): TableColumnsType<BankDataStatementRow> => [
  { key: 'transactionTime', title: '交易时间', dataIndex: 'transactionTime', width: 160, render: (value) => dateTime(value) },
  {
    key: 'loanCode',
    title: '借贷',
    dataIndex: 'loanCode',
    width: 96,
    filtered: Boolean(activeFilters.loanCode),
    filterDropdown: (
      <LoanCodeFilter
        initialValue={activeFilters.loanCode}
        onApply={(value) => onColumnFilter({ loanCode: value })}
      />
    ),
    render: (value?: string) => (value ? <Tag color={value === 'C' ? 'blue' : 'gold'}>{LOAN_CODE_TEXT[value] || value}</Tag> : '--'),
  },
  {
    key: 'signedAmount',
    title: '金额（带符号）',
    dataIndex: 'signedAmount',
    width: 168,
    align: 'right',
    filtered: Boolean(activeFilters.minAmount || activeFilters.maxAmount),
    filterDropdown: (
      <AmountRangeFilter
        initialValue={{ min: activeFilters.minAmount, max: activeFilters.maxAmount }}
        onApply={(patch) => onColumnFilter(patch)}
      />
    ),
    render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>),
  },
  {
    key: 'acctOnlineBal',
    title: '交易后余额',
    dataIndex: 'acctOnlineBal',
    width: 140,
    align: 'right',
    render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>),
  },
  {
    key: 'statementNo',
    title: '流水号',
    dataIndex: 'statementNo',
    width: 170,
    filtered: Boolean(activeFilters.statementNo),
    filterDropdown: (
      <TextColumnFilter
        initialValue={activeFilters.statementNo}
        placeholder="流水号关键字"
        onApply={(value) => onColumnFilter({ statementNo: value })}
      />
    ),
    render: (value) => (value ? <span className="mono">{value}</span> : '--'),
  },
  {
    key: 'counterparty',
    title: '收付方',
    width: 220,
    ellipsis: true,
    filtered: Boolean(activeFilters.counterparty),
    filterDropdown: (
      <TextColumnFilter
        initialValue={activeFilters.counterparty}
        placeholder="收付方名称关键字"
        onApply={(value) => onColumnFilter({ counterparty: value })}
      />
    ),
    render: (_, row) => (
      <>
        <span>{displayValue(row.counterpartyName)}</span>
        {row.ctpAcctNbr && <span className="table-sub mono">{row.ctpAcctNbr}</span>}
      </>
    ),
  },
  {
    key: 'summary',
    title: '摘要',
    width: 220,
    ellipsis: true,
    render: (_, row) => cleanText(row.businessText || row.remarkTextClt || row.summary || row.extendedRemark),
  },
  {
    key: 'currency',
    title: '币种',
    width: 90,
    render: (_, row) => currencyText(row.vendorCurrencyCode ?? row.currency, row.currency),
  },
  {
    key: 'validationStatus',
    title: '状态',
    dataIndex: 'validationStatus',
    width: 150,
    render: (value, row) => (
      <Space size={4} wrap>
        <StatusTag status={value} />
        {row.taskStatus === 'UNKNOWN' && <StatusTag status="待核验" />}
      </Space>
    ),
  },
  { key: 'detail', title: '详情', fixed: 'right', width: 80, render: (_, row) => <Button type="link" onClick={(event) => { void event; openDetail(row); }}>查看</Button> },
];

/* ===================== 余额列（WP-C：默认 5 列 + 复制标签，其余进列设置） ===================== */

export type BalanceColumnOptions = {
  openDetail: (row: BankDataBalanceRow) => void;
};

export const balanceColumns = ({ openDetail }: BalanceColumnOptions): TableColumnsType<BankDataBalanceRow> => [
  {
    key: 'bankName',
    title: '银行',
    width: 110,
    render: (_, row) => displayValue(row.bankCode ? (BANK_NAME_TEXT[row.bankCode] || row.bankCode) : undefined),
  },
  {
    key: 'account',
    title: '账号',
    width: 180,
    render: (_, row) => (
      <>
        {/* 2026-09-17 用户要求：本方账号明文展示（后端已返回全号）；银行侧账号仅在与系统账号不同时副行显示。 */}
        <span className="mono">{displayValue(row.accountMasked || row.bankAccountNo)}</span>
        {row.bankAccountNo && row.bankAccountNo !== row.accountMasked && <span className="table-sub mono">{row.bankAccountNo}</span>}
      </>
    ),
  },
  { key: 'asOfTime', title: '截止时间', dataIndex: 'asOfTime', width: 160, render: (value) => dateTime(value) },
  {
    key: 'currency',
    title: '币种',
    width: 90,
    render: (_, row) => currencyText(row.vendorCurrencyCode, row.currency),
  },
  {
    key: 'availableBalance',
    title: '可用余额',
    dataIndex: 'availableBalance',
    width: 160,
    align: 'right',
    render: (value) => (value === undefined || value === null
      ? '--'
      : <Space size={0}><span className="mono">{money(value)}</span><CopyMoneyButton value={value} /></Space>),
  },
  { key: 'bankAccountName', title: '户名', dataIndex: 'bankAccountName', width: 220, render: (value) => displayValue(value) },
  { key: 'onlineBalance', title: '联机余额', dataIndex: 'onlineBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  { key: 'frozenBalance', title: '冻结余额', dataIndex: 'frozenBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  {
    key: 'accountStatus',
    title: '账户状态',
    dataIndex: 'accountStatus',
    width: 90,
    render: (value?: string) => (value ? <Tag color={accountStatusColor(value)}>{ACCOUNT_STATUS_TEXT[value] || value}</Tag> : '--'),
  },
  {
    key: 'validationStatus',
    title: '状态',
    dataIndex: 'validationStatus',
    width: 150,
    render: (value, row) => (
      <Space size={4} wrap>
        <StatusTag status={value} />
        {row.taskStatus === 'UNKNOWN' && <StatusTag status="待核验" />}
      </Space>
    ),
  },
  { key: 'detail', title: '详情', fixed: 'right', width: 80, render: (_, row) => <Button type="link" onClick={() => openDetail(row)}>查看</Button> },
];

/** 余额查询「列设置」可选项（默认 5 列 + 详情之外均为可隐藏列）。 */
export const BALANCE_COLUMN_OPTIONS = [
  { key: 'bankAccountName', label: '户名' },
  { key: 'onlineBalance', label: '联机余额' },
  { key: 'frozenBalance', label: '冻结余额' },
  { key: 'accountStatus', label: '账户状态' },
  { key: 'validationStatus', label: '校验状态' },
];

/** WP-C 余额默认隐藏列（银行/账号/截止时间/币种/可用余额/详情 之外）。 */
export const BALANCE_DEFAULT_HIDDEN = ['bankAccountName', 'onlineBalance', 'frozenBalance', 'accountStatus', 'validationStatus'];

export function StatementDetail({ row }: { row: BankDataStatementRow }) {
  return (
    <>
      <Descriptions className="projection-detail" column={1} size="small" bordered>
      <Descriptions.Item label="交易时间">{dateTime(row.transactionTime)}</Descriptions.Item>
      <Descriptions.Item label="借贷码">{row.loanCode ? `${LOAN_CODE_TEXT[row.loanCode] || row.loanCode}（${row.loanCode}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="金额（带符号 / 银行口径）">{row.signedAmount === undefined ? '--' : <span className="mono">{money(row.signedAmount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="金额（记账口径）">{row.amount === undefined ? '--' : <span className="mono">{money(row.amount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="交易后余额">{row.acctOnlineBal === undefined ? '--' : <span className="mono">{money(row.acctOnlineBal)}</span>}</Descriptions.Item>
      <Descriptions.Item label="流水号"><span className="mono">{displayValue(row.statementNo)}</span></Descriptions.Item>
      <Descriptions.Item label="本方账号"><span className="mono">{displayValue(row.accountMasked || row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方名称">{displayValue(row.counterpartyName)}</Descriptions.Item>
      <Descriptions.Item label="收付方账号"><span className="mono">{displayValue(row.ctpAcctNbr)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方开户行">{displayValue(row.ctpBankName)}</Descriptions.Item>
      <Descriptions.Item label="币种">{currencyText(row.vendorCurrencyCode ?? row.currency, row.currency)}</Descriptions.Item>
      <Descriptions.Item label="你方摘要">{cleanText(row.remarkTextClt)}</Descriptions.Item>
      <Descriptions.Item label="网银业务摘要">{cleanText(row.businessText)}</Descriptions.Item>
      <Descriptions.Item label="扩展摘要">{displayValue(row.extendedRemark)}</Descriptions.Item>
      <Descriptions.Item label="业务名称">{displayValue(row.businessName)}</Descriptions.Item>
      <Descriptions.Item label="票据号">{displayValue(row.billNumber)}</Descriptions.Item>
      <Descriptions.Item label="冲账标志">{row.reversalFlag ? `${REVERSAL_TEXT[row.reversalFlag] || row.reversalFlag}（${row.reversalFlag}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="信息标志">{row.infoFlag === undefined ? '--' : (INFO_FLAG_TEXT[row.infoFlag] || row.infoFlag)}</Descriptions.Item>
    </Descriptions>
    <Collapse
      ghost
      size="small"
      items={[{
        key: 'raw',
        label: '银行原始字段（技术明细，日常对账一般不用）',
        children: (
          <Descriptions className="projection-detail" column={1} size="small" bordered>
            <Descriptions.Item label="起息日">{dateOnly(row.valueDate)}</Descriptions.Item>
            <Descriptions.Item label="记账方向">{displayValue(row.direction)}</Descriptions.Item>
            <Descriptions.Item label="收付方开户行地址">{displayValue(row.ctpBankAddress)}</Descriptions.Item>
            <Descriptions.Item label="母子公司账号"><span className="mono">{displayValue(row.fatOrSonAccount)}</span></Descriptions.Item>
            <Descriptions.Item label="母子公司名称">{displayValue(row.fatOrSonCompanyName)}</Descriptions.Item>
            <Descriptions.Item label="母子公司开户行">{displayValue(row.fatOrSonBankName)}</Descriptions.Item>
            <Descriptions.Item label="母子公司开户行地址">{displayValue(row.fatOrSonBankAddress)}</Descriptions.Item>
            <Descriptions.Item label="网银流程实例号"><span className="mono">{displayValue(row.requestNbr)}</span></Descriptions.Item>
            <Descriptions.Item label="网银业务参考号"><span className="mono">{displayValue(row.yurRef)}</span></Descriptions.Item>
            <Descriptions.Item label="虚拟户编号">{displayValue(row.virtualNbr)}</Descriptions.Item>
            <Descriptions.Item label="商务支付订单号">{displayValue(row.mchOrderNbr)}</Descriptions.Item>
            <Descriptions.Item label="记账卡号">{displayValue(row.transCardNbr)}</Descriptions.Item>
            <Descriptions.Item label="保留字">{displayValue(row.reserve)}</Descriptions.Item>
          </Descriptions>
        ),
      }]}
    />
    </>
  );
}

export function BalanceDetail({ row }: { row: BankDataBalanceRow }) {
  return (
    <>
      <Descriptions className="projection-detail" column={1} size="small" bordered>
        <Descriptions.Item label="截止时间">{dateTime(row.asOfTime)}</Descriptions.Item>
        <Descriptions.Item label="银行">{displayValue(row.bankCode ? (BANK_NAME_TEXT[row.bankCode] || row.bankCode) : undefined)}</Descriptions.Item>
        <Descriptions.Item label="账号"><span className="mono">{displayValue(row.accountMasked || row.bankAccountNo)}</span></Descriptions.Item>
        <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
        <Descriptions.Item label="户名">{displayValue(row.bankAccountName)}</Descriptions.Item>
        <Descriptions.Item label="可用余额">{row.availableBalance === undefined ? '--' : <span className="mono">{money(row.availableBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="联机余额">{row.onlineBalance === undefined ? '--' : <span className="mono">{money(row.onlineBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="冻结余额">{row.frozenBalance === undefined ? '--' : <span className="mono">{money(row.frozenBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="上日余额">{row.previousDayBalance === undefined ? '--' : <span className="mono">{money(row.previousDayBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="币种">{currencyText(row.vendorCurrencyCode, row.currency)}</Descriptions.Item>
        <Descriptions.Item label="账户状态">{row.accountStatus ? <Tag color={accountStatusColor(row.accountStatus)}>{ACCOUNT_STATUS_TEXT[row.accountStatus] || row.accountStatus}</Tag> : '--'}</Descriptions.Item>
      </Descriptions>
      <Collapse
        ghost
        size="small"
        items={[{
          key: 'raw',
          label: '银行原始字段（技术明细，日常对账一般不用）',
          children: (
            <Descriptions className="projection-detail" column={1} size="small" bordered>
              <Descriptions.Item label="科目">{displayValue(row.accountItem)}</Descriptions.Item>
              <Descriptions.Item label="分行号">{displayValue(row.branchCode)}</Descriptions.Item>
              <Descriptions.Item label="客户关系号">{displayValue(row.customerRelationNo)}</Descriptions.Item>
              <Descriptions.Item label="开户日（opndat）">{row.openDate ? <span className="mono">{row.openDate}</span> : '--'}</Descriptions.Item>
              <Descriptions.Item label="利率类型（inttyp）">{row.interestType ? `${INTEREST_TYPE_TEXT[row.interestType] || row.interestType}（${row.interestType}）` : '--'}</Descriptions.Item>
              <Descriptions.Item label="存期（dpstxt）">{displayValue(row.depositTerm)}</Descriptions.Item>
              <Descriptions.Item label="透支额度（lmtovr）">{row.overdraftLimit === undefined || row.overdraftLimit === null ? '--' : <span className="mono">{money(row.overdraftLimit)}</span>}</Descriptions.Item>
              <Descriptions.Item label="利息码（intcod）">{displayValue(row.interestCode)}{row.interestCode === 'S' ? '（子公司虚拟余额）' : ''}</Descriptions.Item>
              <Descriptions.Item label="年利率（intrat）">{displayValue(row.interestRate)}</Descriptions.Item>
              <Descriptions.Item label="到期日（mutdat）">{row.maturityDate && row.maturityDate !== '00000000' ? <span className="mono">{row.maturityDate}</span> : '--'}</Descriptions.Item>
            </Descriptions>
          ),
        }]}
      />
    </>
  );
}
