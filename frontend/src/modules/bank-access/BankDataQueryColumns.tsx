import { Alert, Button, Descriptions, Space, Tag, type TableColumnsType } from 'antd';
import { StatusTag } from '../shared/components';
import { dateTime, displayValue, cleanText, money, dateOnly, maskAccountDisplay, isUnavailableStatus, isFailedStatus } from '../shared/format';
import type { BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow } from '../../types';
import { ACCOUNT_STATUS_TEXT, accountStatusColor, INFO_FLAG_TEXT, INTEREST_TYPE_TEXT, LOAN_CODE_TEXT, REVERSAL_TEXT } from './bankQueryTexts';

/** 投影行类型：流水与余额二选一（列定义与详情抽屉共用）。 */
export type BankQueryRow = BankDataStatementRow | BankDataBalanceRow;

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

/** 跨公司视图的公司主体列：仅持有 bankdata:cross-company:view 权限的用户注入（见 columns useMemo）。 */
export const COMPANY_COLUMN = {
  title: '公司主体',
  dataIndex: 'companyName',
  width: 150,
  ellipsis: true,
  render: (value?: string) => displayValue(value),
};

export const statementColumns = (openDetail: (row: BankDataStatementRow) => void): TableColumnsType<BankDataStatementRow> => [
  { title: '交易时间', dataIndex: 'transactionTime', width: 160, render: (value) => dateTime(value) },
  { title: '起息日', dataIndex: 'valueDate', width: 110, render: (value) => dateOnly(value) },
  {
    title: '借贷',
    dataIndex: 'loanCode',
    width: 110,
    render: (value?: string) => (value ? <Tag color={value === 'C' ? 'blue' : 'gold'}>{LOAN_CODE_TEXT[value] || value}</Tag> : '--'),
  },
  {
    title: '金额（带符号）',
    dataIndex: 'signedAmount',
    width: 140,
    align: 'right',
    render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>),
  },
  {
    title: '交易后余额',
    dataIndex: 'acctOnlineBal',
    width: 140,
    align: 'right',
    render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>),
  },
  { title: '流水号', dataIndex: 'statementNo', width: 170, render: (value) => (value ? <span className="mono">{value}</span> : '--') },
  { title: '交易类型', dataIndex: 'textCode', width: 100, render: (value) => displayValue(value) },
  {
    title: '收付方',
    width: 220,
    render: (_, row) => (
      <>
        <span>{displayValue(row.counterpartyName)}</span>
        {row.ctpAcctNbr && <span className="table-sub mono">{row.ctpAcctNbr}</span>}
      </>
    ),
  },
  {
    title: '摘要',
    width: 220,
    ellipsis: true,
    render: (_, row) => cleanText(row.businessText || row.remarkTextClt || row.summary || row.extendedRemark),
  },
  { title: '银行请求号', dataIndex: 'bankRequestNo', width: 170, render: (value) => (value ? <span className="mono">{value}</span> : '--') },
  {
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
  { title: '详情', fixed: 'right', width: 80, render: (_, row) => <Button type="link" onClick={(event) => { void event; openDetail(row); }}>查看</Button> },
];

export const balanceColumns = (openDetail: (row: BankDataBalanceRow) => void): TableColumnsType<BankDataBalanceRow> => [
  { title: '快照时间', dataIndex: 'asOfTime', width: 160, render: (value) => dateTime(value) },
  {
    title: '账号',
    width: 180,
    render: (_, row) => (
      <>
        <span>{maskAccountDisplay(row.accountMasked)}</span>
        {row.bankAccountNo && <span className="table-sub mono">{row.bankAccountNo}</span>}
      </>
    ),
  },
  { title: '户名', dataIndex: 'bankAccountName', width: 220, render: (value) => displayValue(value) },
  { title: '可用余额', dataIndex: 'availableBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  { title: '联机余额', dataIndex: 'onlineBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  { title: '冻结余额', dataIndex: 'frozenBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  { title: '上日余额', dataIndex: 'previousDayBalance', width: 140, align: 'right', render: (value) => (value === undefined || value === null ? '--' : <span className="mono">{money(value)}</span>) },
  { title: '币种', dataIndex: 'vendorCurrencyCode', width: 90, render: (value, row) => displayValue(value || row.currency) },
  { title: '科目 / 分行', width: 140, render: (_, row) => <span className="mono">{displayValue(row.accountItem)} / {displayValue(row.branchCode)}</span> },
  {
    title: '账户状态',
    dataIndex: 'accountStatus',
    width: 90,
    render: (value?: string) => (value ? <Tag color={accountStatusColor(value)}>{ACCOUNT_STATUS_TEXT[value] || value}</Tag> : '--'),
  },
  { title: '银行请求号', dataIndex: 'bankRequestNo', width: 170, render: (value) => (value ? <span className="mono">{value}</span> : '--') },
  {
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
  { title: '详情', fixed: 'right', width: 80, render: (_, row) => <Button type="link" onClick={() => openDetail(row)}>查看</Button> },
];

export function StatementDetail({ row }: { row: BankDataStatementRow }) {
  return (
    <Descriptions className="projection-detail" column={1} size="small" bordered>
      <Descriptions.Item label="交易时间">{dateTime(row.transactionTime)}</Descriptions.Item>
      <Descriptions.Item label="起息日">{dateOnly(row.valueDate)}</Descriptions.Item>
      <Descriptions.Item label="借贷码">{row.loanCode ? `${LOAN_CODE_TEXT[row.loanCode] || row.loanCode}（${row.loanCode}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="记账方向">{displayValue(row.direction)}</Descriptions.Item>
      <Descriptions.Item label="金额（带符号 / 银行口径）">{row.signedAmount === undefined ? '--' : <span className="mono">{money(row.signedAmount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="金额（记账口径）">{row.amount === undefined ? '--' : <span className="mono">{money(row.amount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="交易后余额">{row.acctOnlineBal === undefined ? '--' : <span className="mono">{money(row.acctOnlineBal)}</span>}</Descriptions.Item>
      <Descriptions.Item label="流水号"><span className="mono">{displayValue(row.statementNo)}</span></Descriptions.Item>
      <Descriptions.Item label="交易类型">{displayValue(row.textCode)}</Descriptions.Item>
      <Descriptions.Item label="票据号">{displayValue(row.billNumber)}</Descriptions.Item>
      <Descriptions.Item label="冲账标志">{row.reversalFlag ? `${REVERSAL_TEXT[row.reversalFlag] || row.reversalFlag}（${row.reversalFlag}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="信息标志">{row.infoFlag === undefined ? '--' : (INFO_FLAG_TEXT[row.infoFlag] || row.infoFlag)}</Descriptions.Item>
      <Descriptions.Item label="本方账号">{maskAccountDisplay(row.accountMasked)}</Descriptions.Item>
      <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方名称">{displayValue(row.counterpartyName)}</Descriptions.Item>
      <Descriptions.Item label="收付方账号"><span className="mono">{displayValue(row.ctpAcctNbr)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方开户行">{displayValue(row.ctpBankName)}</Descriptions.Item>
      <Descriptions.Item label="收付方开户行地址">{displayValue(row.ctpBankAddress)}</Descriptions.Item>
      <Descriptions.Item label="母子公司账号"><span className="mono">{displayValue(row.fatOrSonAccount)}</span></Descriptions.Item>
      <Descriptions.Item label="母子公司名称">{displayValue(row.fatOrSonCompanyName)}</Descriptions.Item>
      <Descriptions.Item label="母子公司开户行">{displayValue(row.fatOrSonBankName)}</Descriptions.Item>
      <Descriptions.Item label="母子公司开户行地址">{displayValue(row.fatOrSonBankAddress)}</Descriptions.Item>
      <Descriptions.Item label="你方摘要">{cleanText(row.remarkTextClt)}</Descriptions.Item>
      <Descriptions.Item label="网银业务摘要">{cleanText(row.businessText)}</Descriptions.Item>
      <Descriptions.Item label="扩展摘要">{displayValue(row.extendedRemark)}</Descriptions.Item>
      <Descriptions.Item label="业务名称">{displayValue(row.businessName)}</Descriptions.Item>
      <Descriptions.Item label="网银流程实例号"><span className="mono">{displayValue(row.requestNbr)}</span></Descriptions.Item>
      <Descriptions.Item label="网银业务参考号"><span className="mono">{displayValue(row.yurRef)}</span></Descriptions.Item>
      <Descriptions.Item label="虚拟户编号">{displayValue(row.virtualNbr)}</Descriptions.Item>
      <Descriptions.Item label="商务支付订单号">{displayValue(row.mchOrderNbr)}</Descriptions.Item>
      <Descriptions.Item label="记账卡号">{displayValue(row.transCardNbr)}</Descriptions.Item>
      <Descriptions.Item label="保留字">{displayValue(row.reserve)}</Descriptions.Item>
    </Descriptions>
  );
}

export function BalanceDetail({ row }: { row: BankDataBalanceRow }) {
  return (
    <Descriptions className="projection-detail" column={1} size="small" bordered>
      <Descriptions.Item label="快照时间">{dateTime(row.asOfTime)}</Descriptions.Item>
      <Descriptions.Item label="账号">{maskAccountDisplay(row.accountMasked)}</Descriptions.Item>
      <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="户名">{displayValue(row.bankAccountName)}</Descriptions.Item>
      <Descriptions.Item label="可用余额（avlblv）">{row.availableBalance === undefined ? '--' : <span className="mono">{money(row.availableBalance)}</span>}</Descriptions.Item>
      <Descriptions.Item label="联机余额（onlblv）">{row.onlineBalance === undefined ? '--' : <span className="mono">{money(row.onlineBalance)}</span>}</Descriptions.Item>
      <Descriptions.Item label="冻结余额（hldblv）">{row.frozenBalance === undefined ? '--' : <span className="mono">{money(row.frozenBalance)}</span>}</Descriptions.Item>
      <Descriptions.Item label="上日余额（accblv）">{row.previousDayBalance === undefined ? '--' : <span className="mono">{money(row.previousDayBalance)}</span>}</Descriptions.Item>
      <Descriptions.Item label="币种">{displayValue(row.vendorCurrencyCode || row.currency)}</Descriptions.Item>
      <Descriptions.Item label="科目">{displayValue(row.accountItem)}</Descriptions.Item>
      <Descriptions.Item label="分行号">{displayValue(row.branchCode)}</Descriptions.Item>
      <Descriptions.Item label="客户关系号">{displayValue(row.customerRelationNo)}</Descriptions.Item>
      <Descriptions.Item label="账户状态（stscod）">{row.accountStatus ? <Tag color={accountStatusColor(row.accountStatus)}>{ACCOUNT_STATUS_TEXT[row.accountStatus] || row.accountStatus}（{row.accountStatus}）</Tag> : '--'}</Descriptions.Item>
      <Descriptions.Item label="开户日（opndat）">{row.openDate ? <span className="mono">{row.openDate}</span> : '--'}</Descriptions.Item>
      <Descriptions.Item label="利率类型（inttyp）">{row.interestType ? `${INTEREST_TYPE_TEXT[row.interestType] || row.interestType}（${row.interestType}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="存期（dpstxt）">{displayValue(row.depositTerm)}</Descriptions.Item>
      <Descriptions.Item label="透支额度（lmtovr）">{row.overdraftLimit === undefined || row.overdraftLimit === null ? '--' : <span className="mono">{money(row.overdraftLimit)}</span>}</Descriptions.Item>
      <Descriptions.Item label="利息码（intcod）">{displayValue(row.interestCode)}{row.interestCode === 'S' ? '（子公司虚拟余额）' : ''}</Descriptions.Item>
      <Descriptions.Item label="年利率（intrat）">{displayValue(row.interestRate)}</Descriptions.Item>
      <Descriptions.Item label="到期日（mutdat）">{row.maturityDate && row.maturityDate !== '00000000' ? <span className="mono">{row.maturityDate}</span> : '--'}</Descriptions.Item>
    </Descriptions>
  );
}
