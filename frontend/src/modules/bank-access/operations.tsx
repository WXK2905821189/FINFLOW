import { useCallback, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Skeleton,
  Space,
  Table,
  Timeline,
  message,
  type TableColumnsType,
} from 'antd';
import { PlayCircleOutlined, SearchOutlined } from '@ant-design/icons';
import { Link, useSearchParams } from 'react-router-dom';
import { bankPipelineApi, operationsApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag, PhaseOneNotice } from '../shared/components';
import { dateTime, displayValue, cleanText, statusColor } from '../shared/format';
import type { PageResponse, BankSyncJob, BankSyncJobDetail, BankSyncJobTrigger, OperationLog, ConnectionOverview } from '../../types';

export function SyncJobDrawer({ job, onClose }: { job?: BankSyncJob; onClose: () => void }) {
  const loader = useCallback(() => job ? bankPipelineApi.getJob(job.id) : Promise.resolve<BankSyncJobDetail | undefined>(undefined), [job]);
  const { data, loading, error, reload } = useRemote<BankSyncJobDetail | undefined>(loader, [loader]);
  const detail = data?.job;
  return <Drawer title={job ? `同步任务 · ${job.jobNo}` : '同步任务'} width={560} open={Boolean(job)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 7 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : detail ? <><Descriptions column={1} size="small" bordered><Descriptions.Item label="任务编号"><span className="mono">{detail.jobNo}</span></Descriptions.Item><Descriptions.Item label="任务类型">{detail.jobType}</Descriptions.Item><Descriptions.Item label="触发方式">{detail.triggerType}</Descriptions.Item><Descriptions.Item label="连接标识">{displayValue(detail.connectionCode)}</Descriptions.Item><Descriptions.Item label="请求编号"><span className="mono">{displayValue(detail.requestId)}</span></Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={detail.status} /></Descriptions.Item><Descriptions.Item label="创建时间">{dateTime(detail.createdAt)}</Descriptions.Item><Descriptions.Item label="开始 / 完成">{dateTime(detail.startedAt)} / {dateTime(detail.completedAt)}</Descriptions.Item><Descriptions.Item label="服务端摘要">{cleanText(detail.summary)}</Descriptions.Item></Descriptions>{detail.requestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detail.requestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}<h3 className="drawer-section-title">状态时间线</h3>{data.timeline?.length ? <Timeline items={data.timeline.map((event) => ({ color: statusColor(event.status), children: <div><StatusTag status={event.status} /><strong>{event.stage}</strong>{event.message && <div className="timeline-detail">{event.message}</div>}<div className="table-sub">{dateTime(event.occurredAt)} · 请求 <span className="mono">{event.requestId || detail.requestId || '--'}</span></div></div> }))} /> : <Empty description="服务端未返回状态时间线" />}</> : <Empty description="未找到同步任务详情" />}</Drawer>;
}

export function OperationTasks() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const [page, setPage] = useState(1);
  const [taskFilters, setTaskFilters] = useState({ status: '', jobType: '', connectionCode: '', requestId: '' });
  const [draftFilters, setDraftFilters] = useState(taskFilters);
  const [submitted, setSubmitted] = useState(false);
  const [selected, setSelected] = useState<BankSyncJob>();
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [form] = Form.useForm<BankSyncJobTrigger>();
  const overviewLoader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data: overview } = useRemote<ConnectionOverview>(overviewLoader, [overviewLoader]);
  const loader = useCallback(() => submitted ? bankPipelineApi.listJobs({ page, size: 20, status: taskFilters.status || undefined, jobType: taskFilters.jobType || undefined, connectionCode: taskFilters.connectionCode || undefined, requestId: taskFilters.requestId || undefined }) : Promise.resolve<PageResponse<BankSyncJob>>({ page, size: 20, total: 0, records: [] }), [page, taskFilters, submitted]);
  const { data, loading, error, reload } = useRemote<PageResponse<BankSyncJob>>(loader, [loader]);
  const trigger = async () => {
    if (triggering) return;
    const values = await form.validateFields();
    setTriggering(true);
    Modal.confirm({
      title: '确认创建同步任务',
      content: '浏览器只会向 FINFLOW 服务端提交任务请求，不会直接连接银行；任务创建与执行结果以服务端为准。',
      okText: '确认创建',
      cancelText: '取消',
      onOk: async () => {
        try {
          const job = await bankPipelineApi.triggerJob(values);
          message.success(`服务端已创建同步任务 ${job.jobNo}`);
          setTriggerOpen(false);
          form.resetFields();
          await reload();
          setSelected(job);
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '未能创建同步任务');
        } finally {
          setTriggering(false);
        }
      },
      onCancel: () => setTriggering(false),
    });
  };
  const updateTaskFilter = (key: keyof typeof taskFilters, value: string) => setDraftFilters((current) => ({ ...current, [key]: value }));
  const submitTaskFilter = () => { setPage(1); setTaskFilters(draftFilters); setSubmitted(true); };
  const resetTaskFilter = () => { setPage(1); setDraftFilters({ status: '', jobType: '', connectionCode: '', requestId: '' }); setTaskFilters({ status: '', jobType: '', connectionCode: '', requestId: '' }); setSubmitted(false); };
  const columns: TableColumnsType<BankSyncJob> = [{ title: '任务编号', dataIndex: 'jobNo', render: (value) => <span className="mono">{value}</span> }, { title: '任务类型', dataIndex: 'jobType' }, { title: '触发方式', dataIndex: 'triggerType' }, { title: '连接标识', dataIndex: 'connectionCode', render: (value) => displayValue(value) }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '创建时间', dataIndex: 'createdAt', render: (value) => dateTime(value) }, { title: '计划动作', render: () => <span className="muted-inline">只读</span> }, { title: '操作', fixed: 'right', render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>详情</Button> }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>任务执行</h2><p className="muted">任务由服务端持久化、幂等与审计；浏览器只提交受控触发请求并查看安全摘要。</p></div>{canTriggerSync && <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => setTriggerOpen(true)}>手动触发同步</Button>}</div>{!canTriggerSync && <Alert className="resource-alert" type="info" showIcon message="手动同步入口未显示" description="当前角色没有同步触发权限；任务列表仍可按已有查看权限只读展示。" />}<Card className="filter-card"><div className="filter-toolbar"><div className="filter-fields"><Input value={draftFilters.jobType} allowClear placeholder="任务类型" onChange={(event) => updateTaskFilter('jobType', event.target.value)} /><Input value={draftFilters.connectionCode} allowClear placeholder="连接标识" onChange={(event) => updateTaskFilter('connectionCode', event.target.value)} /><Input value={draftFilters.status} allowClear placeholder="服务端状态" onChange={(event) => updateTaskFilter('status', event.target.value)} /><Input value={draftFilters.requestId} allowClear placeholder="请求编号" onChange={(event) => updateTaskFilter('requestId', event.target.value)} /></div><Space><Button type="primary" icon={<SearchOutlined />} onClick={submitTaskFilter}>查询</Button><Button onClick={resetTaskFilter}>重置</Button></Space></div></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted ? <Empty description="设置筛选条件后点击查询；页面打开不会创建或查询同步任务。" /> : <><PhaseOneNotice status={overview?.status} message={overview?.message} /><Table rowKey={(row) => row.jobNo || String(row.id)} loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有同步任务" /> }} scroll={{ x: 1180 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><Modal title="手动触发同步" open={triggerOpen} onCancel={() => { setTriggerOpen(false); form.resetFields(); }} onOk={() => void trigger()} okText="创建同步任务" confirmLoading={triggering} destroyOnClose><Alert type="warning" showIcon message="由服务端调用银行直联" description="提交后由服务端创建可追溯任务并调用真实银行直联；直联未连接时任务会明确失败。" /><Form form={form} layout="vertical" initialValues={{ jobType: 'STATEMENT_PULL' }} className="sync-job-form"><Form.Item label="任务类型" name="jobType" rules={[{ required: true }]}><Input placeholder="例如：STATEMENT_PULL" /></Form.Item><Form.Item label="账户标识" name="bankAccountId" rules={[{ required: true, message: '请选择企业内授权账户' }]}><InputNumber min={1} precision={0} className="full-width-control" placeholder="请输入账户 ID" /></Form.Item><Form.Item label="连接标识" name="connectionCode"><Input placeholder="可选，由服务端校验授权范围" /></Form.Item><Form.Item label="适配器代码" name="adapterCode"><Input placeholder="可选；真实直联填 CMB，留空由服务端按连接配置选择" /></Form.Item><Form.Item label="开始时间" name="windowStart"><Input placeholder="可选 ISO-8601 时间" /></Form.Item><Form.Item label="结束时间" name="windowEnd"><Input placeholder="可选 ISO-8601 时间" /></Form.Item></Form></Modal><SyncJobDrawer job={selected} onClose={() => setSelected(undefined)} /></>;
}

export function OperationLogs() {
  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(1);
  const initialFilters = { requestId: searchParams.get('requestId') || '', connectionCode: '', status: '' };
  const [filters, setFilters] = useState(initialFilters);
  const [draftFilters, setDraftFilters] = useState(initialFilters);
  const [submitted, setSubmitted] = useState(Boolean(initialFilters.requestId));
  const loader = useCallback(() => submitted ? operationsApi.logs({ page, size: 20, requestId: filters.requestId || undefined, connectionCode: filters.connectionCode || undefined, status: filters.status || undefined }) : Promise.resolve<PageResponse<OperationLog>>({ page, size: 20, total: 0, records: [] }), [page, filters, submitted]);
  const { data, loading, error, reload } = useRemote<PageResponse<OperationLog>>(loader, [loader]);
  const updateFilter = (key: keyof typeof filters, value: string) => {
    setDraftFilters((current) => ({ ...current, [key]: value }));
  };
  const submitLogFilter = () => { setPage(1); setFilters(draftFilters); setSubmitted(true); };
  const resetLogFilter = () => { setPage(1); setDraftFilters({ requestId: '', connectionCode: '', status: '' }); setFilters({ requestId: '', connectionCode: '', status: '' }); setSubmitted(false); };
  const columns: TableColumnsType<OperationLog> = [{ title: '时间', dataIndex: 'occurredAt', render: (value) => dateTime(value) }, { title: '级别', dataIndex: 'level', render: (value) => <StatusTag status={value} /> }, { title: '事件', dataIndex: 'eventType' }, { title: '结果', dataIndex: 'result', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '安全摘要', dataIndex: 'message', ellipsis: true, render: (value) => value || '--' }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>日志查询</h2><p className="muted">默认检索最近 24 小时的作业日志；仅显示服务端脱敏摘要，不回显密钥、令牌、私钥、完整账号或堆栈。</p></div></div><Card className="filter-card"><div className="filter-toolbar"><div className="filter-fields"><Input value={draftFilters.requestId} allowClear placeholder="请求编号" onChange={(event) => updateFilter('requestId', event.target.value)} /><Input value={draftFilters.connectionCode} allowClear placeholder="连接标识" onChange={(event) => updateFilter('connectionCode', event.target.value)} /><Input value={draftFilters.status} allowClear placeholder="状态/结果" onChange={(event) => updateFilter('status', event.target.value)} /></div><Space><Button type="primary" icon={<SearchOutlined />} onClick={submitLogFilter}>查询</Button><Button onClick={resetLogFilter}>重置</Button></Space></div></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted ? <Empty description="默认范围为最近 24 小时；点击查询后加载服务端日志。" /> : <><PhaseOneNotice /><Table rowKey={(row) => `${row.taskId || '--'}-${row.occurredAt}-${row.eventType}-${row.requestId || '--'}`} loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有日志" /> }} scroll={{ x: 920 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card></>;
}
