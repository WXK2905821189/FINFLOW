import { useCallback, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Pagination,
  Radio,
  Row,
  Skeleton,
  Space,
  Statistic,
  Table,
  Timeline,
  Upload,
  message,
  type TableColumnsType,
} from 'antd';
import { FileAddOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { statementApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, money } from '../shared/format';
import type {
  PageResponse,
  StatementAuditEvent,
  StatementDetail,
  StatementDashboard,
  StatementImportBatch,
  StatementRecord,
  StatementRecordInput,
  StatementReviewRequest,
} from '../../types';

type ImportFormValues = { sourceName?: string; payload?: string };

export function parseStatementPayload(text: string): { sourceName?: string; records: StatementRecordInput[] } {
  const parsed: unknown = JSON.parse(text);
  if (Array.isArray(parsed)) return { records: parsed as StatementRecordInput[] };
  if (parsed && typeof parsed === 'object' && Array.isArray((parsed as { records?: unknown }).records)) {
    const body = parsed as { sourceName?: unknown; records: StatementRecordInput[] };
    return { sourceName: typeof body.sourceName === 'string' ? body.sourceName : undefined, records: body.records };
  }
  throw new Error('JSON 必须是流水数组，或包含 records 数组的对象');
}

export function ImportStatements() {
  const [form] = Form.useForm<ImportFormValues>();
  const [loading, setLoading] = useState(false);
  const [batch, setBatch] = useState<StatementImportBatch>();
  const [error, setError] = useState<string>();
  const submit = async (values: ImportFormValues) => {
    if (loading) return;
    setLoading(true);
    setError(undefined);
    try {
      const payload = parseStatementPayload(values.payload || '');
      const result = await statementApi.import({ sourceName: values.sourceName || payload.sourceName, records: payload.records });
      setBatch(result);
      message.success('服务端已返回导入批次');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '导入请求未能完成');
    } finally {
      setLoading(false);
    }
  };
  const readJson = (file: File) => {
    void file.text().then((text) => form.setFieldValue('payload', text)).catch(() => setError('无法读取所选文件'));
    return Upload.LIST_IGNORE;
  };
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 入口</span><h2>导入流水</h2><p className="muted">JSON 内容由服务端校验并创建批次（模拟来源入口已下线，数据仅来自真实银行直联或受控导入）。</p></div></div><Row gutter={[16, 16]}><Col xs={24} xl={15}><Card title="新建导入批次"><Form form={form} layout="vertical" onFinish={submit}><Form.Item label="来源名称" name="sourceName" rules={[{ max: 128 }]}><Input placeholder="例如：2026-08-银行流水.json" /></Form.Item><Form.Item label="选择 JSON 文件"><Upload accept="application/json,.json" maxCount={1} beforeUpload={readJson}><Button>选择文件</Button></Upload></Form.Item><Form.Item label="JSON 内容" name="payload" rules={[{ required: true, message: '请提供 JSON 流水内容' }]} extra="支持流水数组，或包含 sourceName 和 records 数组的对象。"><Input.TextArea rows={12} placeholder="粘贴待导入的 JSON 内容" /></Form.Item> {error && <Alert className="form-error" type="error" showIcon message="导入未完成" description={error} />}<Button type="primary" htmlType="submit" loading={loading}>提交 JSON 导入</Button></Form></Card></Col><Col xs={24} xl={9}>{batch ? <Card title="服务端导入结果"><Descriptions column={1} size="small"><Descriptions.Item label="批次编号"><span className="mono">{batch.batchNo}</span></Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={batch.status} /></Descriptions.Item><Descriptions.Item label="来源">{batch.sourceType} {batch.sourceName ? `· ${batch.sourceName}` : ''}</Descriptions.Item><Descriptions.Item label="总记录数">{batch.totalCount}</Descriptions.Item><Descriptions.Item label="已导入">{batch.importedCount}</Descriptions.Item><Descriptions.Item label="重复">{batch.duplicateCount}</Descriptions.Item><Descriptions.Item label="无效">{batch.invalidCount}</Descriptions.Item>{batch.errorMessage && <Descriptions.Item label="服务端说明">{batch.errorMessage}</Descriptions.Item>}</Descriptions></Card> : <Card title="导入结果"><Empty description="提交后展示服务端返回的批次结果" /></Card>}</Col></Row></>;
}

export function BatchList() {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<string>();
  const loader = useCallback(() => statementApi.listBatches({ page, size: 20, status }), [page, status]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementImportBatch>>(loader, [loader]);
  const columns: TableColumnsType<StatementImportBatch> = [{ title: '批次编号', dataIndex: 'batchNo', render: (value) => <span className="mono">{value}</span> }, { title: '来源', render: (_, row) => <>{row.sourceType}<span className="table-sub">{row.sourceName || '--'}</span></> }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '记录', render: (_, row) => <span>{row.importedCount} / {row.totalCount}</span> }, { title: '重复 / 无效', render: (_, row) => <span>{row.duplicateCount} / {row.invalidCount}</span> }, { title: '完成时间', dataIndex: 'completedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 批次</span><h2>流水批次</h2><p className="muted">批次数据仅来自服务端导入记录。</p></div><Link to="/statements/import"><Button type="primary" icon={<FileAddOutlined />}>导入流水</Button></Link></div><Card className="filter-card"><Space wrap><label>批次状态</label><Input value={status} onChange={(event) => { setPage(1); setStatus(event.target.value || undefined); }} placeholder="输入服务端状态" allowClear /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="暂无服务端流水批次" /> }} scroll={{ x: 780 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card></>;
}

export function AuditDrawer({ statement, onClose }: { statement?: StatementRecord; onClose: () => void }) {
  const loader = useCallback(() => statement ? statementApi.get(statement.id) : Promise.resolve<StatementDetail | undefined>(undefined), [statement]);
  const { data, loading, error, reload } = useRemote<StatementDetail | undefined>(loader, [loader]);
  const auditTrail: StatementAuditEvent[] = data?.auditTrail || [];
  return <Drawer title={statement ? `追溯记录 · ${statement.statementNo}` : '追溯记录'} width={520} open={Boolean(statement)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : auditTrail.length ? <Timeline items={auditTrail.map((event) => ({ color: event.result === 'SUCCESS' ? 'green' : event.result === 'FAILED' ? 'red' : 'blue', children: <div><strong>{event.action}</strong><div className="table-sub">{event.previousStatus || '--'} → {event.currentStatus || '--'} · 操作人 {event.operatorId || '--'}</div>{event.detail && <div className="timeline-detail">{event.detail}</div>}<div className="table-sub">{dateTime(event.createdAt)}</div></div> }))} /> : <Empty description="该流水暂无追溯事件" />}</Drawer>;
}

export function ReviewStatements() {
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<StatementRecord>();
  const [trace, setTrace] = useState<StatementRecord>();
  const [form] = Form.useForm<StatementReviewRequest>();
  const [submitting, setSubmitting] = useState(false);
  const loader = useCallback(() => statementApi.list({ page, size: 20, reviewStatus: 'PENDING' }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
  const review = async () => {
    if (submitting) return;
    const values = await form.validateFields();
    if (!selected) return;
    setSubmitting(true);
    try {
      await statementApi.review(selected.id, values);
      message.success('服务端已更新复核状态');
      setSelected(undefined);
      form.resetFields();
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '复核未能完成');
    } finally {
      setSubmitting(false);
    }
  };
  const columns: TableColumnsType<StatementRecord> = [{ title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> }, { title: '交易时间', dataIndex: 'transactionTime', render: (value) => dateTime(value) }, { title: '对方', render: (_, row) => <>{row.counterpartyName || '--'}<span className="table-sub">{row.maskedCounterpartyAccount || '--'}</span></> }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => <strong>{money(value)}</strong> }, { title: '校验', dataIndex: 'validationStatus', render: (value, row) => <><StatusTag status={value} />{row.validationMessage && <span className="table-sub">{row.validationMessage}</span>}</> }, { title: '操作', fixed: 'right', render: (_, row) => <Space><Button type="link" onClick={() => setSelected(row)}>复核</Button><Button type="link" onClick={() => setTrace(row)}>追溯</Button></Space> }];
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 控制点</span><h2>人工复核</h2><p className="muted">仅展示服务端标记为待复核的流水，不在前端预判校验或复核结果。</p></div></div><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前没有待复核流水" /> }} scroll={{ x: 900 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><Modal title={selected ? `复核流水 ${selected.statementNo}` : '复核流水'} open={Boolean(selected)} onCancel={() => { setSelected(undefined); form.resetFields(); }} onOk={() => void review()} okText="提交复核" confirmLoading={submitting} destroyOnClose><Form form={form} layout="vertical" initialValues={{ action: 'APPROVE' }}><Form.Item label="复核结论" name="action" rules={[{ required: true }]}><Radio.Group options={[{ value: 'APPROVE', label: '通过' }, { value: 'REJECT', label: '驳回' }]} /></Form.Item><Form.Item noStyle shouldUpdate={(previous, current) => previous.action !== current.action}>{({ getFieldValue }) => <Form.Item label="复核说明" name="comment" rules={getFieldValue('action') === 'REJECT' ? [{ required: true, message: '驳回时必须填写说明' }] : []}><Input.TextArea rows={4} maxLength={500} placeholder="填写服务端可审计的复核说明" /></Form.Item>}</Form.Item></Form></Modal><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
}

export function VoucherStatements() {
  const [page, setPage] = useState(1);
  const [trace, setTrace] = useState<StatementRecord>();
  const [pushingId, setPushingId] = useState<number>();
  const loader = useCallback(() => statementApi.list({ page, size: 20 }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
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
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 外部结果</span><h2>金蝶制证</h2><p className="muted">制证状态、凭证号和服务端说明均以接口响应为准。</p></div></div><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="暂无可追溯的制证记录" /> }} scroll={{ x: 880 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
}

export function Reconciliation() {
  const dashboardLoader = useCallback(() => statementApi.dashboard(), []);
  const statementsLoader = useCallback(() => statementApi.list({ page: 1, size: 10 }), []);
  const dashboard = useRemote<StatementDashboard>(dashboardLoader, [dashboardLoader]);
  const statements = useRemote<PageResponse<StatementRecord>>(statementsLoader, [statementsLoader]);
  const [trace, setTrace] = useState<StatementRecord>();
  const metrics = dashboard.data;
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 对账</span><h2>对账与追溯</h2><p className="muted">汇总与流水追溯分开加载；任一接口失败不会被显示为零值。</p></div></div>{dashboard.error ? <ResourceFailure error={dashboard.error} onRetry={dashboard.reload} /> : <Row gutter={[16, 16]}>{[['导入流水', metrics?.totalCount], ['待复核', metrics?.pendingReviewCount], ['已复核通过', metrics?.approvedCount], ['已制证', metrics?.pushedCount]].map(([title, value]) => <Col xs={24} sm={12} xl={6} key={String(title)}><Card className="metric-card"><Statistic title={title as string} value={dashboard.loading ? undefined : value as number | undefined} suffix="笔" valueStyle={{ color: '#16262b' }} /></Card></Col>)}</Row>}<Row gutter={[16, 16]} className="dashboard-grid"><Col xs={24} xl={10}><Card title="金额汇总">{dashboard.loading ? <Skeleton active paragraph={{ rows: 4 }} /> : dashboard.error ? null : <Descriptions column={1} size="small"><Descriptions.Item label="导入金额">{money(metrics?.totalAmount)}</Descriptions.Item><Descriptions.Item label="已复核金额">{money(metrics?.approvedAmount)}</Descriptions.Item><Descriptions.Item label="已制证金额">{money(metrics?.pushedAmount)}</Descriptions.Item><Descriptions.Item label="无效流水">{metrics?.invalidCount ?? '--'} 笔</Descriptions.Item><Descriptions.Item label="已驳回">{metrics?.rejectedCount ?? '--'} 笔</Descriptions.Item></Descriptions>}</Card></Col><Col xs={24} xl={14}><Card title="最近流水" extra={<Link to="/statements/batches">查看批次</Link>}>{statements.error ? <ResourceFailure error={statements.error} onRetry={statements.reload} /> : <Table rowKey="id" loading={statements.loading} pagination={false} dataSource={statements.data?.records || []} locale={{ emptyText: <Empty description="暂无可追溯流水" /> }} columns={[{ title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => money(value) }, { title: '复核', dataIndex: 'reviewStatus', render: (value) => <StatusTag status={value} /> }, { title: '制证', dataIndex: 'pushStatus', render: (value) => <StatusTag status={value} /> }, { title: '追溯', render: (_, row) => <Button type="link" onClick={() => setTrace(row)}>查看</Button> }]} scroll={{ x: 700 }} />}</Card></Col></Row><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
}
