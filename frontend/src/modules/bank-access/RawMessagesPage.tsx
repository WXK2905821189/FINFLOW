import { useCallback, useRef, useState } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Pagination, Space, Table, Tag, message, type TableColumnsType } from 'antd';
import { CopyOutlined, DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { bankPipelineApi } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime, displayValue } from '../shared/format';
import type { PageResponse } from '../shared/api';
import type { BankRawMessage, BankRawMessageDetail } from './types';

type RawFilters = {
  accountId: string;
  taskNo: string;
  adapterCode: string;
  from: string;
  to: string;
};

const emptyFilters: RawFilters = { accountId: '', taskNo: '', adapterCode: '', from: '', to: '' };

const emptyPage: PageResponse<BankRawMessage> = { page: 1, size: 20, total: 0, records: [] };

/** Pretty-print the bank payload; fall back to the raw text when it is not JSON. */
const prettyPayload = (payload: string) => {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
};

const shortDigest = (digest?: string) => (digest ? `${digest.slice(0, 12)}…` : '--');

/**
 * Raw bank response browser.
 *
 * <p>This module answers one question the rest of the pipeline deliberately cannot:
 * "did we actually reach the bank?". A digest proves a record was stored; only the
 * payload proves the bank answered. That is why this page sits behind its own
 * permission and shows the response body in full.
 */
export function RawMessagesPage() {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [submitted, setSubmitted] = useState(false);
  const [filters, setFilters] = useState<RawFilters>(emptyFilters);
  const [draft, setDraft] = useState<RawFilters>(emptyFilters);
  const [selectedId, setSelectedId] = useState<number>();
  const focusRef = useRef<HTMLElement | null>(null);

  const loader = useCallback(() => submitted
    ? bankPipelineApi.listRawMessages({
      page,
      size,
      accountId: filters.accountId || undefined,
      taskNo: filters.taskNo || undefined,
      adapterCode: filters.adapterCode || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
    })
    : Promise.resolve(emptyPage), [page, size, filters, submitted]);
  const { data, loading, error, reload } = useRemote<PageResponse<BankRawMessage>>(loader, [loader]);

  const detailLoader = useCallback(() => selectedId === undefined
    ? Promise.resolve<BankRawMessageDetail | undefined>(undefined)
    : bankPipelineApi.getRawMessage(selectedId), [selectedId]);
  const { data: detail, loading: detailLoading } = useRemote<BankRawMessageDetail | undefined>(detailLoader, [detailLoader]);

  const query = () => { setPage(1); setFilters(draft); setSubmitted(true); };
  const reset = () => { setPage(1); setDraft(emptyFilters); setFilters(emptyFilters); setSubmitted(false); };
  const setDateFilter = (key: 'from' | 'to', value?: string) =>
    setDraft((current) => ({ ...current, [key]: value || '' }));

  const openDetail = (row: BankRawMessage) => {
    focusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelectedId(row.id);
  };
  const closeDetail = () => {
    setSelectedId(undefined);
    window.setTimeout(() => focusRef.current?.focus(), 0);
  };

  const copyPayload = async (payload: string) => {
    try {
      await navigator.clipboard.writeText(payload);
      message.success('报文已复制到剪贴板');
    } catch {
      message.error('浏览器拒绝了剪贴板访问，请手动选中复制');
    }
  };

  const downloadPayload = (row: BankRawMessageDetail) => {
    const blob = new Blob([row.payload], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `bank-raw-message-${row.id}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const columns: TableColumnsType<BankRawMessage> = [
    { title: '报文 ID', dataIndex: 'id', render: (value) => <span className="mono">{value}</span> },
    { title: '任务号', dataIndex: 'taskNo', render: (value) => <span className="mono">{displayValue(value)}</span> },
    { title: '适配器', dataIndex: 'adapterCode', render: (value) => <span className="mono">{displayValue(value)}</span> },
    { title: '银行请求号', dataIndex: 'bankRequestNo', render: (value) => <span className="mono">{displayValue(value)}</span> },
    { title: '报文摘要', dataIndex: 'contentSha256', render: (value) => <span className="mono">{shortDigest(value)}</span> },
    { title: '接收时间', dataIndex: 'receivedAt', render: (value) => dateTime(value) },
    { title: '保留到期', dataIndex: 'retentionUntil', render: (value) => dateTime(value) },
    {
      title: '连通标识',
      dataIndex: 'realDirect',
      render: (value, row) => value
        ? <Tag color="green">真实银行响应</Tag>
        : <Tag>{row.purgedAt ? '报文已清理' : '非真实直联'}</Tag>,
    },
    {
      title: '操作',
      fixed: 'right',
      render: (_, row) => <Button type="link" onClick={(event) => { focusRef.current = event.currentTarget; openDetail(row); }}>查看报文</Button>,
    },
  ];

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">银行接入 / 原始报文</span>
        <h2>原始报文</h2>
        <p className="muted">银行响应的原始留存档。摘要只能证明记录被保存，报文体才能证明银行真的回答了——这就是本模块存在的意义。</p>
      </div>
    </div>
    <Card className="filter-card">
      <div className="bank-query-grid">
        <Input value={draft.accountId} placeholder="账户标识" onChange={(event) => setDraft((current) => ({ ...current, accountId: event.target.value }))} />
        <Input value={draft.taskNo} placeholder="任务号" onChange={(event) => setDraft((current) => ({ ...current, taskNo: event.target.value }))} />
        <Input value={draft.adapterCode} placeholder="适配器代码（如 CMB）" onChange={(event) => setDraft((current) => ({ ...current, adapterCode: event.target.value }))} />
        <DatePicker showTime placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} />
        <DatePicker showTime placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} />
        <Space className="bank-query-actions">
          <Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button>
          <Button onClick={reset}>重置</Button>
        </Space>
      </div>
    </Card>
    <Card title="留存报文">
      {error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading
        ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" />
        : <>
          <Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false}
            locale={{ emptyText: <Empty description="当前筛选下没有留存的银行报文。若尚未对该账户发起过同步任务，请先到流水查询页创建任务；若任务执行过却没有报文，说明银行侧未返回响应体。" /> }}
            scroll={{ x: 1200 }} />
          {data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size} total={data.total}
            showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }} />}
        </>}
    </Card>
    <Drawer
      title={detail ? `银行原始报文 · ${detail.id}` : '银行原始报文'}
      width={720}
      open={selectedId !== undefined}
      onClose={closeDetail}
    >
      {detailLoading && <Empty description="正在加载报文…" />}
      {detail && <>
        <Alert
          className="phase-one-notice"
          type={detail.realDirect ? 'success' : 'warning'}
          showIcon
          message={detail.realDirect ? '该报文来自真实银行直联适配器' : '该报文不是真实银行直联产生'}
          description={detail.realDirect
            ? '银行侧已返回响应体，可作为连通性证据留档。'
            : '仅凭此报文不能证明银行已接通；请到银行账户页确认该账户的直联状态。'}
        />
        <Descriptions className="projection-detail" column={1} size="small" bordered>
          <Descriptions.Item label="任务号"><span className="mono">{displayValue(detail.taskNo)}</span></Descriptions.Item>
          <Descriptions.Item label="账户标识"><span className="mono">{displayValue(detail.bankAccountId)}</span></Descriptions.Item>
          <Descriptions.Item label="适配器">{displayValue(detail.adapterCode)}</Descriptions.Item>
          <Descriptions.Item label="银行请求号"><span className="mono">{displayValue(detail.bankRequestNo)}</span></Descriptions.Item>
          <Descriptions.Item label="报文摘要 SHA256"><span className="mono">{displayValue(detail.contentSha256)}</span></Descriptions.Item>
          <Descriptions.Item label="报文大小">{detail.payloadBytes} 字节</Descriptions.Item>
          <Descriptions.Item label="接收时间">{dateTime(detail.receivedAt)}</Descriptions.Item>
          <Descriptions.Item label="保留到期">{dateTime(detail.retentionUntil)}</Descriptions.Item>
          {detail.purgedAt && <Descriptions.Item label="清理时间">{dateTime(detail.purgedAt)}</Descriptions.Item>}
        </Descriptions>
        <div className="raw-payload-toolbar">
          <Space>
            <Button icon={<CopyOutlined />} onClick={() => void copyPayload(detail.payload)}>复制报文</Button>
            <Button icon={<DownloadOutlined />} onClick={() => downloadPayload(detail)}>下载 JSON</Button>
          </Space>
        </div>
        <pre className="raw-payload">{detail.payload ? prettyPayload(detail.payload) : '（该报文体已按保留策略清理，仅剩元数据。）'}</pre>
      </>}
    </Drawer>
  </>;
}
