import { useCallback, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Pagination,
  Row,
  Skeleton,
  Space,
  Table,
  Timeline,
  message,
  type TableColumnsType,
} from 'antd';
import { Link } from 'react-router-dom';
import { statementApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, money } from '../shared/format';
import type {
  PageResponse,
  StatementAuditEvent,
  StatementDetail,
  StatementDashboard,
  StatementRecord,
} from '../../types';

/**
 * 流水与入账页面组（2026-09-16 流程精简后）：
 *  - 导入流水 / 标准流水 / 人工复核三页已下线——制证链路收敛为流水查询页
 *    「AI 制证推送」一键入口（转入 → AI 建议 → 推送金蝶），复核职责转移到金蝶侧人工审核；
 *  - 本文件保留：AuditDrawer（追溯）、VoucherStatements（金蝶制证/推送状态跟踪）、
 *    Reconciliation（三方对账汇总）。
 */

export function AuditDrawer({ statement, onClose }: { statement?: StatementRecord; onClose: () => void }) {
  const loader = useCallback(() => statement ? statementApi.get(statement.id) : Promise.resolve<StatementDetail | undefined>(undefined), [statement]);
  const { data, loading, error, reload } = useRemote<StatementDetail | undefined>(loader, [loader]);
  const auditTrail: StatementAuditEvent[] = data?.auditTrail || [];
  return <Drawer title={statement ? `追溯记录 · ${statement.statementNo}` : '追溯记录'} width={520} open={Boolean(statement)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : auditTrail.length ? <Timeline items={auditTrail.map((event) => ({ color: event.result === 'SUCCESS' ? 'green' : event.result === 'FAILED' ? 'red' : 'blue', children: <div><strong>{event.action}</strong><div className="table-sub">{event.previousStatus || '--'} → {event.currentStatus || '--'} · 操作人 {event.operatorId || '--'}</div>{event.detail && <div className="timeline-detail">{event.detail}</div>}<div className="table-sub">{dateTime(event.createdAt)}</div></div> }))} /> : <Empty description="该流水暂无追溯事件" />}</Drawer>;
}

export function VoucherStatements() {
  const [page, setPage] = useState(1);
  const [trace, setTrace] = useState<StatementRecord>();
  const [pushingId, setPushingId] = useState<number>();
  const [pinging, setPinging] = useState(false);
  const [ping, setPing] = useState<{ connected: boolean; mode: string; message: string }>();
  const loader = useCallback(() => statementApi.list({ page, size: 20 }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
  const runPing = async () => {
    if (pinging) return;
    setPinging(true);
    try {
      const result = await statementApi.pingKingdee();
      setPing(result);
    } catch (reason) {
      setPing({ connected: false, mode: 'ERROR', message: reason instanceof Error ? reason.message : '连接测试请求未能完成' });
    } finally {
      setPinging(false);
    }
  };
  const push = async (record: StatementRecord) => {
    if (pushingId !== undefined) return;
    setPushingId(record.id);
    try {
      await statementApi.pushVoucher(record.id);
      message.success('服务端已返回制证处理结果');
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '制证请求未能完成');
    } finally {
      setPushingId(undefined);
    }
  };
  const columns: TableColumnsType<StatementRecord> = [{ title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> }, { title: '复核状态', dataIndex: 'reviewStatus', render: (value) => <StatusTag status={value} /> }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => money(value) }, { title: '制证状态', dataIndex: 'pushStatus', render: (value, row) => <><StatusTag status={value} />{row.pushMessage && <span className="table-sub">{row.pushMessage}</span>}</> }, { title: '金蝶凭证号', dataIndex: 'voucherNo', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '操作', fixed: 'right', render: (_, row) => <Space>{row.reviewStatus === 'APPROVED' && row.pushStatus !== 'PUSHED' && <Button type="link" loading={pushingId === row.id} onClick={() => void push(row)}>请求制证</Button>}<Button type="link" onClick={() => setTrace(row)}>追溯</Button></Space> }];
  return <><div className="page-heading"><div><span className="section-kicker">流水与入账 / 外部结果</span><h2>金蝶制证</h2><p className="muted">流水查询页「AI 制证推送」的落点跟踪：推送状态、金蝶单号与审计追溯均以接口响应为准；审核在金蝶侧人工完成。</p></div><Space><Button onClick={() => void runPing()} loading={pinging}>连接测试</Button></Space></div>{ping && <Alert style={{ marginBottom: 16 }} showIcon type={ping.connected ? 'success' : 'warning'} message={ping.connected ? `金蝶连接正常（${ping.mode}）` : `金蝶连接未建立（${ping.mode}）`} description={ping.message} />}<Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="暂无可追溯的制证记录" /> }} scroll={{ x: 880 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
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
