import { useCallback, useRef, useState } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Modal, Pagination, Space, Table, message, type TableColumnsType } from 'antd';
import { PlayCircleOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, cleanText, money, maskAccountDisplay, isUnavailableStatus, isSimulatedStatus, isFailedStatus } from '../shared/format';
import type { BankDataProjection, BankDataProjectionPage } from '../../types';

export const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
  receipts: { title: '回单查询', permission: 'bankdata:receipt:view' },
  reconciliations: { title: '对账单查询', permission: 'bankdata:reconciliation:view' },
  payroll: { title: '代发查询', permission: 'bankdata:payroll:view' },
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

export function BankProjectionState({ data }: { data?: BankDataProjectionPage }) {
  if (!data) return null;
  const status = data.status;
  const type = data.enabled === false || isUnavailableStatus(status) ? 'warning' : isFailedStatus(status) ? 'error' : isSimulatedStatus(status) || data.simulated ? 'info' : 'success';
  const message = data.enabled === false || isUnavailableStatus(status) ? '真实银行直联未启用或该资源未获取' : isSimulatedStatus(status) || data.simulated ? '当前为服务端模拟/沙箱业务投影' : isFailedStatus(status) ? '服务端返回失败状态' : '服务端业务投影已返回';
  const description = data.message || '页面仅展示 FINFLOW 服务端返回的安全字段；未返回的同步信息会显示为未获取。';
  return <Alert className="phase-one-notice" type={type} showIcon message={message} description={<span>{description}{status && <> 服务端状态：<StatusTag status={status} /></>}</span>} />;
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
  const [selected, setSelected] = useState<BankDataProjection>();
  const focusRef = useRef<HTMLElement | null>(null);
  const loader = useCallback(() => submitted ? bankPipelineApi.queryProjection(resource, { page, size, keyword: filters.keyword || undefined, accountId: filters.accountId || undefined, status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined, sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined, requestId: filters.requestId || undefined }) : Promise.resolve<BankDataProjectionPage>({ page, size, total: 0, records: [] }), [resource, page, size, filters, submitted]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage>(loader, [loader]);
  const query = () => { setPage(1); setFilters(draft); setSubmitted(true); };
  const reset = () => { setPage(1); setDraft(emptyBankQueryFilters); setFilters(emptyBankQueryFilters); setSubmitted(false); };
  const setDateFilter = (key: 'from' | 'to', value?: string) => setDraft((current) => ({ ...current, [key]: value || '' }));
  const openDetail = (row: BankDataProjection) => {
    focusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelected(row);
  };
  const closeDetail = () => {
    setSelected(undefined);
    window.setTimeout(() => focusRef.current?.focus(), 0);
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
  const columns: TableColumnsType<BankDataProjection> = [{ title: '来源记录', dataIndex: 'sourceRecordId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '来源/通道', render: (_, row) => <><span>{displayValue(row.sourceSystem || row.sourceMode || row.channelMode)}</span>{(row.simulated || isSimulatedStatus(row.sourceSystem) || isSimulatedStatus(row.channelMode)) && <span className="table-sub">模拟</span>}</> }, { title: '账户', render: (_, row) => <><span>{maskAccountDisplay(row.accountMasked)}</span>{row.accountName && <span className="table-sub">{row.accountName}</span>}</> }, { title: '发生时间', dataIndex: 'occurredAt', render: (value) => dateTime(value) }, { title: '末次同步', render: (_, row) => dateTime(row.lastSyncedAt || row.updatedAt) }, { title: '任务/请求', render: (_, row) => <><span className="mono">{displayValue(row.syncJobNo || row.jobNo)}</span><span className="table-sub mono">{displayValue(row.requestId || data?.requestId)}</span></> }, { title: '方向', dataIndex: 'direction', render: (value) => displayValue(value) }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value, row) => value === undefined ? '--' : `${money(value)}${row.currency ? ` ${row.currency}` : ''}` }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value) => cleanText(value) }, { title: '详情', fixed: 'right', render: (_, row) => <Button type="link" onClick={(event) => { focusRef.current = event.currentTarget; openDetail(row); }}>查看</Button> }];
  const definition = bankDataResources[resource];
  const emptyDescription = data?.enabled === false || isUnavailableStatus(data?.status) ? '真实直联未启用或该资源尚未获取。' : isSimulatedStatus(data?.status) || data?.simulated ? '当前筛选没有服务端模拟业务投影。' : '服务端未返回符合条件的业务投影。';
  const detailRequestId = selected?.requestId || data?.requestId;
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 数据查询</span><h2>{definition.title}</h2><p className="muted">仅消费服务端企业与账户授权后的业务投影；不读取原始报文、同步日志、密钥或令牌。</p></div>{canTriggerSync && <Button icon={<PlayCircleOutlined />} loading={syncTriggering} onClick={triggerSyncFromFilters}>按筛选创建同步任务</Button>}</div><Card className="filter-card"><div className="bank-query-grid"><Input value={draft.keyword} placeholder="关键字/摘要/记录号" onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))} /><Input value={draft.accountId} placeholder="账户标识" onChange={(event) => setDraft((current) => ({ ...current, accountId: event.target.value }))} /><Input value={draft.status} placeholder="状态" onChange={(event) => setDraft((current) => ({ ...current, status: event.target.value }))} /><Input value={draft.sourceSystem} placeholder="来源/模拟通道" onChange={(event) => setDraft((current) => ({ ...current, sourceSystem: event.target.value }))} /><Input value={draft.syncJobNo} placeholder="任务号" onChange={(event) => setDraft((current) => ({ ...current, syncJobNo: event.target.value }))} /><Input value={draft.requestId} placeholder="请求编号" onChange={(event) => setDraft((current) => ({ ...current, requestId: event.target.value }))} /><DatePicker showTime placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} /><DatePicker showTime placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} /><Space className="bank-query-actions"><Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button><Button onClick={reset}>重置</Button></Space></div></Card><Card title="查询结果">{error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" /> : <><BankProjectionState data={data} />{data?.requestId && <div className="query-request-id">请求编号：<span className="mono">{data.requestId}</span><Link to={`/operations/logs?requestId=${encodeURIComponent(data.requestId)}`}>查看脱敏审计追溯</Link></div>}<Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description={emptyDescription} /> }} scroll={{ x: 1380 }} />{data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size} total={data.total} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }} />}</>}</Card><Drawer title={selected ? `业务投影追溯 · ${selected.sourceRecordId || selected.id}` : '业务投影追溯'} width={540} open={Boolean(selected)} onClose={closeDetail}>{selected && <><Alert type="info" showIcon message="仅显示业务投影安全字段" description="原始报文、同步日志、凭据和完整账号不会暴露在此页面；账号按脱敏值展示。" /><Descriptions className="projection-detail" column={1} size="small" bordered><Descriptions.Item label="来源记录">{displayValue(selected.sourceRecordId)}</Descriptions.Item><Descriptions.Item label="来源/通道">{displayValue(selected.sourceSystem || selected.sourceMode || selected.channelMode)}</Descriptions.Item><Descriptions.Item label="账户">{maskAccountDisplay(selected.accountMasked)}</Descriptions.Item><Descriptions.Item label="账户名称">{displayValue(selected.accountName)}</Descriptions.Item><Descriptions.Item label="发生时间">{dateTime(selected.occurredAt)}</Descriptions.Item><Descriptions.Item label="末次同步">{dateTime(selected.lastSyncedAt || selected.updatedAt)}</Descriptions.Item><Descriptions.Item label="任务号"><span className="mono">{displayValue(selected.syncJobNo || selected.jobNo)}</span></Descriptions.Item><Descriptions.Item label="请求编号"><span className="mono">{displayValue(detailRequestId)}</span></Descriptions.Item><Descriptions.Item label="方向 / 金额">{displayValue(selected.direction)} / {selected.amount === undefined ? '--' : money(selected.amount)}</Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={selected.status} /></Descriptions.Item><Descriptions.Item label="摘要">{cleanText(selected.summary)}</Descriptions.Item></Descriptions>{detailRequestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detailRequestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}</>}</Drawer></>;
}
