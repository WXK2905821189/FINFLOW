import { useCallback, useState, type Key } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Modal,
  Pagination,
  Row,
  Segmented,
  Skeleton,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  message,
  type TableColumnsType,
} from 'antd';
import { Link } from 'react-router-dom';
import { statementApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, money } from '../shared/format';
import { VoucherDraftDrawer } from './VoucherDraftDrawer';
import type {
  PageResponse,
  StatementAuditEvent,
  StatementBatchOpResult,
  StatementDetail,
  StatementDashboard,
  StatementRecord,
} from '../../types';

/**
 * 流水与入账页面组（2026-09-16 流程精简，2026-09-17 草稿工作台化）：
 *  - 导入流水 / 标准流水 / 人工复核三页已下线——制证链路收敛为：
 *    流水查询页「AI 制证为草稿」→ 本页（凭证草稿与制证）人工审核 → 点击推送金蝶；
 *  - 本文件保留：AuditDrawer（追溯）、VoucherStatements（草稿工作台：过滤/批量通过/驳回/推送/
 *    刷新 AI 建议/连接测试）、Reconciliation（三方对账汇总）。
 */

export function AuditDrawer({ statement, onClose }: { statement?: Pick<StatementRecord, 'id' | 'statementNo'> | null; onClose: () => void }) {
  const loader = useCallback(() => statement ? statementApi.get(statement.id) : Promise.resolve<StatementDetail | undefined>(undefined), [statement]);
  const { data, loading, error, reload } = useRemote<StatementDetail | undefined>(loader, [loader]);
  const auditTrail: StatementAuditEvent[] = data?.auditTrail || [];
  return <Drawer title={statement ? `追溯记录 · ${statement.statementNo}` : '追溯记录'} width={520} open={Boolean(statement)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : auditTrail.length ? <Timeline items={auditTrail.map((event) => ({ color: event.result === 'SUCCESS' ? 'green' : event.result === 'FAILED' ? 'red' : 'blue', children: <div><strong>{event.action}</strong><div className="table-sub">{event.previousStatus || '--'} → {event.currentStatus || '--'} · 操作人 {event.operatorId || '--'}</div>{event.detail && <div className="timeline-detail">{event.detail}</div>}<div className="table-sub">{dateTime(event.createdAt)}</div></div> }))} /> : <Empty description="该流水暂无追溯事件" />}</Drawer>;
}

type VoucherFilter = 'ALL' | 'PENDING' | 'APPROVED' | 'PUSHED' | 'REJECTED';

const batchOutcomeTag = (outcome: string) => (
  <Tag color={outcome === 'PUSHED' || outcome === 'APPROVED' ? 'green' : outcome === 'REJECTED' ? 'red' : outcome === 'ALREADY_PUSHED' || outcome === 'SKIPPED' ? 'blue' : 'orange'}>
    {outcome === 'PUSHED' ? '已推送' : outcome === 'APPROVED' ? '已通过' : outcome === 'REJECTED' ? '已驳回'
      : outcome === 'ALREADY_PUSHED' ? '此前已推送' : outcome === 'SKIPPED' ? '跳过' : '失败'}
  </Tag>
);

export function VoucherStatements() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canReview = hasPermission('statement:review');
  const canPush = hasPermission('voucher:push');
  const canAi = hasPermission('ai:use');
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState<VoucherFilter>('ALL');
  const [trace, setTrace] = useState<StatementRecord>();
  const [voucherDetail, setVoucherDetail] = useState<StatementRecord>();
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [aiBusyId, setAiBusyId] = useState<number>();
  const [rejectIds, setRejectIds] = useState<number[]>();
  const [rejectComment, setRejectComment] = useState('');
  const [batchResult, setBatchResult] = useState<StatementBatchOpResult>();
  const [pinging, setPinging] = useState(false);
  const [ping, setPing] = useState<{ connected: boolean; mode: string; message: string }>();
  const loader = useCallback(() => {
    const reviewStatus = filter === 'PENDING' || filter === 'APPROVED' || filter === 'REJECTED' ? filter : undefined;
    const pushStatus = filter === 'PUSHED' ? 'PUSHED' : undefined;
    return statementApi.list({ page, size: 20, reviewStatus, pushStatus });
  }, [page, filter]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
  const runPing = async () => {
    if (pinging) return;
    setPinging(true);
    try {
      setPing(await statementApi.pingKingdee());
    } catch (reason) {
      setPing({ connected: false, mode: 'ERROR', message: reason instanceof Error ? reason.message : '连接测试请求未能完成' });
    } finally {
      setPinging(false);
    }
  };
  const runBatch = async (fn: () => Promise<StatementBatchOpResult>) => {
    if (busy) return;
    setBusy(true);
    try {
      const result = await fn();
      setBatchResult(result);
      setSelectedIds([]);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '批量操作未能完成');
    } finally {
      setBusy(false);
    }
  };
  const batchApprove = (ids: number[]) => {
    if (!ids.length) {
      message.warning('请先勾选待复核的草稿行');
      return;
    }
    Modal.confirm({
      title: `确认批量通过 ${ids.length} 条草稿`,
      content: '通过后即可推送金蝶（出纳收付款单，提交不审核）。',
      okText: '确认通过',
      cancelText: '取消',
      onOk: () => runBatch(() => statementApi.batchReview({ ids, action: 'APPROVE' })),
    });
  };
  const batchPush = (ids: number[]) => {
    if (!ids.length) {
      message.warning('请先勾选要推送的流水行');
      return;
    }
    Modal.confirm({
      title: `确认批量推送 ${ids.length} 条流水到金蝶`,
      content: '服务端只推送「已复核通过」的行，其余行自动跳过并回报原因；推送幂等，此前已推送的行不会重复推送。',
      okText: '确认推送',
      cancelText: '取消',
      onOk: () => runBatch(() => statementApi.batchPush({ ids })),
    });
  };
  const openReject = (ids: number[]) => {
    if (!ids.length) {
      message.warning('请先勾选待复核的草稿行');
      return;
    }
    setRejectComment('');
    setRejectIds(ids);
  };
  const refreshAi = async (row: StatementRecord) => {
    setAiBusyId(row.id);
    try {
      const suggestion = await statementApi.refreshAiSuggestion(row.id);
      // W10（WP-5）：规则引擎命中时提示「已按规则生成」，让用户知道 AI 建议受规则约束
      const hits = suggestion?.hitRules || [];
      message.success(hits.length
        ? `AI 建议已更新 · 按命中规则生成（${hits.map((hit) => `R${hit.ruleNo} ${hit.businessType}`).join('、')}）`
        : 'AI 建议已更新（无命中规则，AI 独立判断）');
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : 'AI 建议刷新失败');
    } finally {
      setAiBusyId(undefined);
    }
  };
  const rowSelection = (canReview || canPush) ? {
    selectedRowKeys: selectedIds,
    onChange: (keys: Key[]) => setSelectedIds(keys.map(Number)),
    getCheckboxProps: (row: StatementRecord) => ({
      disabled: !((canReview && row.reviewStatus === 'PENDING')
        || (canPush && row.reviewStatus === 'APPROVED' && row.pushStatus !== 'PUSHED')),
    }),
  } : undefined;
  const columns: TableColumnsType<StatementRecord> = [
    { title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> },
    { title: '交易时间', dataIndex: 'transactionTime', width: 150, render: (value) => dateTime(value) },
    { title: '对手方', dataIndex: 'counterpartyName', ellipsis: true, render: (value) => value || '--' },
    { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value) => value || '--' },
    { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => money(value) },
    { title: '复核状态', dataIndex: 'reviewStatus', render: (value) => <StatusTag status={value} /> },
    {
      title: 'AI 建议 / 复核意见', dataIndex: 'reviewComment', ellipsis: true,
      render: (value) => value ? <Tooltip title={value}><span>{value}</span></Tooltip> : '--',
    },
    { title: '制证状态', dataIndex: 'pushStatus', render: (value, row) => <><StatusTag status={value} />{row.pushMessage && <span className="table-sub">{row.pushMessage}</span>}</> },
    { title: '金蝶凭证号', dataIndex: 'voucherNo', render: (value) => value ? <span className="mono">{value}</span> : '--' },
    {
      title: '操作', fixed: 'right', width: 270,
      render: (_, row) => <Space size={0} wrap>
        <Button type="link" size="small" onClick={() => setVoucherDetail(row)}>凭证</Button>
        {canReview && row.reviewStatus === 'PENDING' && <Button type="link" size="small" disabled={busy} onClick={() => batchApprove([row.id])}>通过</Button>}
        {canReview && row.reviewStatus === 'PENDING' && <Button type="link" size="small" disabled={busy} onClick={() => openReject([row.id])}>驳回</Button>}
        {canAi && row.reviewStatus === 'PENDING' && <Button type="link" size="small" loading={aiBusyId === row.id} onClick={() => void refreshAi(row)}>刷新 AI 建议</Button>}
        {canPush && row.reviewStatus === 'APPROVED' && row.pushStatus !== 'PUSHED' && <Button type="link" size="small" disabled={busy} onClick={() => batchPush([row.id])}>推送金蝶</Button>}
        <Button type="link" size="small" onClick={() => setTrace(row)}>追溯</Button>
      </Space>,
    },
  ];
  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">流水与入账 / 制证工作台</span>
        <h2>凭证草稿与制证</h2>
        <p className="muted">AI 制证草稿在此人工审核：点击「凭证」查看金蝶式凭证单据（AI 预填科目与逐行置信度，人工复核+改）→ 通过（可批量）→ 推送金蝶（可批量、幂等）→ 金蝶侧完成最终审核。</p>
      </div>
      <Space wrap>
        {canReview && <Button type="primary" ghost disabled={busy || !selectedIds.length} onClick={() => batchApprove(selectedIds)}>批量通过{selectedIds.length ? `（${selectedIds.length}）` : ''}</Button>}
        {canReview && <Button danger ghost disabled={busy || !selectedIds.length} onClick={() => openReject(selectedIds)}>批量驳回{selectedIds.length ? `（${selectedIds.length}）` : ''}</Button>}
        {canPush && <Button type="primary" disabled={busy || !selectedIds.length} loading={busy} onClick={() => batchPush(selectedIds)}>批量推送金蝶{selectedIds.length ? `（${selectedIds.length}）` : ''}</Button>}
        <Button onClick={() => void runPing()} loading={pinging}>连接测试</Button>
      </Space>
    </div>
    {ping && <Alert style={{ marginBottom: 16 }} showIcon type={ping.connected ? 'success' : ping.mode === 'ERROR' ? 'error' : 'warning'} message={ping.connected ? `金蝶连接正常（${ping.mode}）` : `金蝶连接未建立（${ping.mode}）`} description={ping.message} />}
    <Segmented
      style={{ marginBottom: 12 }}
      value={filter}
      onChange={(value) => { setPage(1); setFilter(value as VoucherFilter); }}
      options={[
        { label: '全部', value: 'ALL' },
        { label: '待复核草稿', value: 'PENDING' },
        { label: '待推送', value: 'APPROVED' },
        { label: '已推送', value: 'PUSHED' },
        { label: '已驳回', value: 'REJECTED' },
      ]}
    />
    <Card>
      {error ? <ResourceFailure error={error} onRetry={reload} /> : <>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data?.records || []}
          pagination={false}
          rowSelection={rowSelection}
          locale={{ emptyText: <Empty description="暂无对应状态的流水" /> }}
          scroll={{ x: 1280 }}
        />
        {data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}
      </>}
    </Card>
    <Modal
      title={`驳回 ${rejectIds?.length ?? 0} 条流水草稿`}
      open={Boolean(rejectIds)}
      onCancel={() => setRejectIds(undefined)}
      onOk={() => {
        const ids = rejectIds || [];
        setRejectIds(undefined);
        void runBatch(() => statementApi.batchReview({ ids, action: 'REJECT', comment: rejectComment.trim() || '人工驳回' }));
      }}
      okText="确认驳回"
      cancelText="取消"
      okButtonProps={{ disabled: busy }}
    >
      <Input.TextArea
        value={rejectComment}
        onChange={(event) => setRejectComment(event.target.value)}
        placeholder="驳回原因（留空默认记为「人工驳回」）"
        rows={3}
      />
    </Modal>
    <Modal
      title={`批量操作结果 · 成功 ${batchResult?.successCount ?? 0} / 跳过 ${batchResult?.skippedCount ?? 0} / 失败 ${batchResult?.failedCount ?? 0}`}
      open={Boolean(batchResult)}
      onCancel={() => setBatchResult(undefined)}
      footer={<Button type="primary" onClick={() => setBatchResult(undefined)}>知道了</Button>}
      width={680}
    >
      {batchResult && <Table
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={batchResult.rows}
        columns={[
          { title: '流水号', dataIndex: 'statementNo', render: (value: string | null) => value ? <span className="mono">{value}</span> : '--' },
          { title: '结果', dataIndex: 'outcome', width: 100, render: (value: string) => batchOutcomeTag(value) },
          { title: '说明', dataIndex: 'message', ellipsis: true, render: (value: string | null) => value || '--' },
        ]}
      />}
    </Modal>
    <AuditDrawer statement={trace} onClose={() => setTrace(undefined)} />
    <VoucherDraftDrawer statement={voucherDetail} onClose={() => setVoucherDetail(undefined)} onChanged={() => void reload()} />
  </>;
}

export function Reconciliation() {
  const dashboardLoader = useCallback(() => statementApi.dashboard(), []);
  const statementsLoader = useCallback(() => statementApi.list({ page: 1, size: 10 }), []);
  const dashboard = useRemote<StatementDashboard>(dashboardLoader, [dashboardLoader]);
  const statements = useRemote<PageResponse<StatementRecord>>(statementsLoader, [statementsLoader]);
  const [trace, setTrace] = useState<StatementRecord>();
  const metrics = dashboard.data;
  return <><div className="page-heading"><div><span className="section-kicker">流水与入账 / 对账</span><h2>对账与追溯</h2><p className="muted">汇总与流水追溯分开加载；任一接口失败不会被显示为零值。</p></div></div>{dashboard.error ? <ResourceFailure error={dashboard.error} onRetry={dashboard.reload} /> : <Row gutter={[16, 16]}>{[['制证流水', metrics?.totalCount], ['待复核', metrics?.pendingReviewCount], ['已复核通过', metrics?.approvedCount], ['已制证', metrics?.pushedCount]].map(([title, value]) => <Col xs={24} sm={12} xl={6} key={String(title)}><Card className="metric-card"><span className="metric-label">{title as string}</span><div className="metric-value">{dashboard.loading ? '—' : (value as number | undefined) ?? 0} 笔</div></Card></Col>)}</Row>}<Row gutter={[16, 16]} className="dashboard-grid"><Col xs={24} xl={10}><Card title="金额汇总">{dashboard.loading ? <Skeleton active paragraph={{ rows: 4 }} /> : dashboard.error ? null : <Descriptions column={1} size="small"><Descriptions.Item label="制证金额">{money(metrics?.totalAmount)}</Descriptions.Item><Descriptions.Item label="已复核金额">{money(metrics?.approvedAmount)}</Descriptions.Item><Descriptions.Item label="已制证金额">{money(metrics?.pushedAmount)}</Descriptions.Item><Descriptions.Item label="无效流水">{metrics?.invalidCount ?? '--'} 笔</Descriptions.Item><Descriptions.Item label="已驳回">{metrics?.rejectedCount ?? '--'} 笔</Descriptions.Item></Descriptions>}</Card></Col><Col xs={24} xl={14}><Card title="最近流水" extra={<Link to="/statements/vouchers">查看制证记录</Link>}>{statements.error ? <ResourceFailure error={statements.error} onRetry={statements.reload} /> : <Table rowKey="id" loading={statements.loading} pagination={false} dataSource={statements.data?.records || []} locale={{ emptyText: <Empty description="暂无可追溯流水" /> }} columns={[{ title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => money(value) }, { title: '复核', dataIndex: 'reviewStatus', render: (value) => <StatusTag status={value} /> }, { title: '制证', dataIndex: 'pushStatus', render: (value) => <StatusTag status={value} /> }, { title: '操作', render: (_, row) => <Button type="link" onClick={() => setTrace(row)}>追溯</Button> }] satisfies TableColumnsType<StatementRecord>} />}</Card></Col></Row><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
}
