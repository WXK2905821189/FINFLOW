import { useCallback, useMemo, useState } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Modal, Pagination, Space, Table, Tag, message, type TableColumnsType } from 'antd';
import { DownloadOutlined, PlayCircleOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, cleanText, money, dateOnly, maskAccountDisplay, isUnavailableStatus, isFailedStatus } from '../shared/format';
import type { BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow } from '../../types';

export const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
} as const;

export type BankQueryFilters = {
  keyword: string;
  accountId: string;
  status: string;
  sourceSystem: string;
  syncJobNo: string;
  requestId: string;
  from: string;
  to: string;
};

export const emptyBankQueryFilters: BankQueryFilters = { keyword: '', accountId: '', status: '', sourceSystem: '', syncJobNo: '', requestId: '', from: '', to: '' };

type BankQueryRow = BankDataStatementRow | BankDataBalanceRow;

/** 借贷码是银行自己的口径，不做业务翻译——翻译过一次就对不上银行导出的明细了。 */
const LOAN_CODE_TEXT: Record<string, string> = { C: '贷方（收）', D: '借方（付）' };
const REVERSAL_TEXT: Record<string, string> = { '*': '冲账', X: '补账', N: '正常' };
const INFO_FLAG_TEXT: Record<string, string> = {
  '': '付方账号 / 子公司',
  '1': '收方账号 / 子公司',
  '2': '收方账号 / 母公司',
  '3': '原收方账号 / 子公司',
};

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

const statementColumns = (openDetail: (row: BankDataStatementRow) => void): TableColumnsType<BankDataStatementRow> => [
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

const balanceColumns = (openDetail: (row: BankDataBalanceRow) => void): TableColumnsType<BankDataBalanceRow> => [
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

function StatementDetail({ row }: { row: BankDataStatementRow }) {
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

function BalanceDetail({ row }: { row: BankDataBalanceRow }) {
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
    </Descriptions>
  );
}

export function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [submitted, setSubmitted] = useState(false);
  const [syncTriggering, setSyncTriggering] = useState(false);
  const [filters, setFilters] = useState<BankQueryFilters>(emptyBankQueryFilters);
  const [draft, setDraft] = useState<BankQueryFilters>(emptyBankQueryFilters);
  const [selected, setSelected] = useState<BankQueryRow>();
  // Where to return keyboard focus when the detail drawer closes. Kept in state rather
  // than a ref: writing a ref from a handler that flows through render-created column
  // callbacks trips the react-compiler refs rule, and state does the same job here.
  const [focusReturn, setFocusReturn] = useState<HTMLElement | null>(null);
  const isStatement = resource === 'statements';
  const loader = useCallback(() => submitted ? bankPipelineApi.queryProjection<BankQueryRow>(resource, { page, size, keyword: filters.keyword || undefined, accountId: filters.accountId || undefined, status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined, sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined, requestId: filters.requestId || undefined }) : Promise.resolve<BankDataProjectionPage<BankQueryRow>>({ page, size, total: 0, records: [] }), [resource, page, size, filters, submitted]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage<BankQueryRow>>(loader, [loader]);
  const query = () => { setPage(1); setFilters(draft); setSubmitted(true); };
  const reset = () => { setPage(1); setDraft(emptyBankQueryFilters); setFilters(emptyBankQueryFilters); setSubmitted(false); };
  const setDateFilter = (key: 'from' | 'to', value?: string) => setDraft((current) => ({ ...current, [key]: value || '' }));
  const openDetail = useCallback((row: BankQueryRow) => {
    setFocusReturn(document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setSelected(row);
  }, []);
  const closeDetail = () => {
    setSelected(undefined);
    window.setTimeout(() => focusReturn?.focus(), 0);
  };
  const [exporting, setExporting] = useState(false);
  const exportCsv = async () => {
    setExporting(true);
    try {
      await bankPipelineApi.exportCsv(resource, {
        keyword: filters.keyword || undefined, accountId: filters.accountId || undefined,
        status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined,
        sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined,
        requestId: filters.requestId || undefined,
      });
      message.success('导出已生成');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
  };
  const triggerSyncFromFilters = () => {
    const accountId = Number(draft.accountId || filters.accountId);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      message.warning('请先填写企业内授权账户 ID，再创建同步任务');
      return;
    }
    Modal.confirm({
      title: '确认创建同步任务',
      content: '将按当前账户与时间范围向 FINFLOW 服务端创建同步任务；浏览器不会直接连接银行，任务结果以服务端幂等状态为准。',
      okText: '确认创建',
      cancelText: '取消',
      onOk: async () => {
        setSyncTriggering(true);
        try {
          const job = await bankPipelineApi.triggerJob({
            jobType: 'STATEMENT_PULL',
            bankAccountId: accountId,
            connectionCode: draft.sourceSystem || filters.sourceSystem || undefined,
            windowStart: draft.from || filters.from || undefined,
            windowEnd: draft.to || filters.to || undefined,
          });
          message.success(`同步任务已创建：${job.jobNo}`);
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '未能创建同步任务');
        } finally {
          setSyncTriggering(false);
        }
      },
    });
  };
  const columns: TableColumnsType<BankQueryRow> = useMemo(() => (isStatement
    ? statementColumns((row) => openDetail(row)) as TableColumnsType<BankQueryRow>
    : balanceColumns((row) => openDetail(row)) as TableColumnsType<BankQueryRow>), [isStatement, openDetail]);
  const definition = bankDataResources[resource];
  const emptyDescription = data?.enabled === false || isUnavailableStatus(data?.status) ? '真实银行直联未连接，无法获取数据。' : isFailedStatus(data?.status) ? '银行查询失败，请检查同步任务。' : '当前筛选没有匹配的真实银行数据。';
  const detailRequestId = isStatement
    ? (selected as BankDataStatementRow | undefined)?.taskRequestId
    : (selected as BankDataBalanceRow | undefined)?.taskRequestId;
  const detailTitle = selected
    ? (isStatement ? `银行流水字段 · ${(selected as BankDataStatementRow).statementNo || selected.id}` : `银行余额字段 · ${selected.id}`)
    : '银行字段明细';
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">银行接入 / 数据查询</span>
          <h2>{definition.title}</h2>
          <p className="muted">直出银行返回的原始字段（招行 trsQryByBreakPoint / NTQADINF），不做业务投影翻译；本方账号脱敏，完整报文体在「原始报文」模块查看。</p>
        </div>
        {submitted && <Button icon={<DownloadOutlined />} loading={exporting} onClick={exportCsv}>导出 CSV</Button>}
        {canTriggerSync && <Button icon={<PlayCircleOutlined />} loading={syncTriggering} onClick={triggerSyncFromFilters}>按筛选创建同步任务</Button>}
      </div>
      <Card className="filter-card">
        <div className="bank-query-grid">
          <Input value={draft.keyword} placeholder="关键字：流水号/摘要/收付方/参考号" onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))} />
          <Input value={draft.accountId} placeholder="账户标识" onChange={(event) => setDraft((current) => ({ ...current, accountId: event.target.value }))} />
          <Input value={draft.status} placeholder="状态" onChange={(event) => setDraft((current) => ({ ...current, status: event.target.value }))} />
          <Input value={draft.sourceSystem} placeholder="来源（真实数据为 BANKDATA）" onChange={(event) => setDraft((current) => ({ ...current, sourceSystem: event.target.value }))} />
          <Input value={draft.syncJobNo} placeholder="任务号" onChange={(event) => setDraft((current) => ({ ...current, syncJobNo: event.target.value }))} />
          <Input value={draft.requestId} placeholder="请求编号" onChange={(event) => setDraft((current) => ({ ...current, requestId: event.target.value }))} />
          <DatePicker showTime placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} />
          <DatePicker showTime placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} />
          <Space className="bank-query-actions">
            <Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button>
            <Button onClick={reset}>重置</Button>
          </Space>
        </div>
      </Card>
      <Card title="查询结果">
        {error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" /> : (
          <>
            <BankProjectionState data={data} />
            {data?.requestId && <div className="query-request-id">请求编号：<span className="mono">{data.requestId}</span><Link to={`/operations/logs?requestId=${encodeURIComponent(data.requestId)}`}>查看脱敏审计追溯</Link></div>}
            <Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description={emptyDescription} /> }} scroll={{ x: isStatement ? 1900 : 1800 }} />
            {data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size} total={data.total} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }} />}
          </>
        )}
      </Card>
      <Drawer title={detailTitle} width={560} open={Boolean(selected)} onClose={closeDetail}>
        {selected && (
          <>
            <Alert
              type="info"
              showIcon
              message="银行原始字段"
              description="字段 ID 与银行接口一致，可与银行导出的交易明细逐列比对；借贷、冲账、信息标志均未做业务翻译。完整响应报文请在「原始报文」模块查看。"
            />
            {isStatement ? <StatementDetail row={selected as BankDataStatementRow} /> : <BalanceDetail row={selected as BankDataBalanceRow} />}
            <Descriptions className="projection-detail" column={1} size="small" bordered>
              <Descriptions.Item label="银行请求号"><span className="mono">{displayValue(selected.bankRequestNo)}</span></Descriptions.Item>
              <Descriptions.Item label="同步任务号"><span className="mono">{displayValue(selected.taskNo)}</span></Descriptions.Item>
              <Descriptions.Item label="请求编号"><span className="mono">{displayValue(detailRequestId)}</span></Descriptions.Item>
              <Descriptions.Item label="同步任务状态">
                {selected.taskStatus === 'UNKNOWN'
                  ? <Space size={4} wrap><StatusTag status="UNKNOWN" /><span>银行响应状态未知，该行数据待人工核验</span></Space>
                  : displayValue(selected.taskStatus)}
              </Descriptions.Item>
              <Descriptions.Item label="校验状态"><StatusTag status={selected.validationStatus} /></Descriptions.Item>
              <Descriptions.Item label="报文摘要"><span className="mono">{displayValue(selected.contentSha256)}</span></Descriptions.Item>
              <Descriptions.Item label="入库时间">{dateTime(selected.createdAt)}</Descriptions.Item>
            </Descriptions>
            {detailRequestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detailRequestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}
          </>
        )}
      </Drawer>
    </>
  );
}
