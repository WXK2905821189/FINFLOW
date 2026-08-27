import { useCallback, useEffect, useMemo, useState, type DependencyList } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Layout,
  Menu,
  Modal,
  Pagination,
  Radio,
  Result,
  Row,
  Skeleton,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  Upload,
  message,
  type MenuProps,
  type TableColumnsType,
} from 'antd';
import {
  ApartmentOutlined,
  AuditOutlined,
  BankOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FileAddOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  LogoutOutlined,
  PlayCircleOutlined,
  RadarChartOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  TeamOutlined,
  TransactionOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { statementApi, authApi, bankPipelineApi, operationsApi } from './services/api';
import { ApiRequestError } from './services/http';
import { useAuthStore } from './store/auth';
import type {
  PageResponse,
  ConnectionConfiguration,
  ConnectionOverview,
  BankDataProjection,
  BankDataProjectionPage,
  BankSyncJob,
  BankSyncJobDetail,
  BankSyncJobTrigger,
  DataQueryCapability,
  OperationLog,
  OperationTask,
  StatementAuditEvent,
  StatementDetail,
  StatementDashboard,
  StatementImportBatch,
  StatementRecord,
  StatementRecordInput,
  StatementReviewRequest,
} from './types';
import './styles.css';

const { Header, Sider, Content } = Layout;

const pageTitles: Record<string, string> = {
  '/dashboard': '财务工作台',
  '/statements/import': '导入流水',
  '/statements/batches': '流水批次',
  '/statements/review': '人工复核',
  '/statements/vouchers': '金蝶制证',
  '/statements/reconciliation': '对账与追溯',
  '/transfer': '发起转账',
  '/transactions': '交易记录',
  '/users': '用户管理',
  '/connections/apps': '应用管理',
  '/connections/agreements': '签约管理',
  '/connections/preferences': '个性化设置',
  '/operations/connectivity': '直联状态监控',
  '/operations/tasks': '任务执行',
  '/operations/logs': '日志查询',
  '/bank-data/balances': '余额查询',
  '/bank-data/statements': '流水查询',
  '/bank-data/receipts': '回单查询',
  '/bank-data/reconciliations': '对账单查询',
  '/bank-data/payments': '支付记录查询',
  '/bank-data/payroll': '代发查询',
};

const money = (value?: number | string) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '--';
};

const dateTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '--');

const statusColor = (status?: string) => {
  if (!status) return 'default';
  if (/(APPROVED|PUSHED|COMPLETED|VALID|SUCCESS)/.test(status)) return 'green';
  if (/(PENDING|PROCESSING|NOT_PUSHED)/.test(status)) return 'gold';
  if (/(REJECTED|INVALID|FAILED|ERROR)/.test(status)) return 'red';
  return 'blue';
};

function StatusTag({ status }: { status?: string }) {
  return <Tag color={statusColor(status)}>{status || '--'}</Tag>;
}

function ResourceFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const description = error instanceof Error ? error.message : '请求未能完成，请稍后重试';
  return <Alert className="resource-alert" type="error" showIcon message="数据暂不可用" description={description} action={<Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>} />;
}

function useRemote<T>(loader: () => Promise<T>, dependencies: DependencyList) {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const reload = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setData(await loader());
    } catch (reason) {
      setError(reason);
    } finally {
      setLoading(false);
    }
  }, dependencies);

  useEffect(() => { void reload(); }, [reload]);
  return { data, loading, error, reload };
}

function AuthGuard() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();
  if (status === 'restoring') return <PageLoading />;
  return status === 'authenticated' ? <Outlet /> : <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
}

function PermissionGuard({ permissions }: { permissions: string[] }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  return permissions.some(hasPermission) ? <Outlet /> : <Navigate to="/403" replace />;
}

function PageLoading() {
  return <div className="page-loading"><Skeleton active paragraph={{ rows: 5 }} /></div>;
}

function Shell() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, hasPermission } = useAuthStore();
  const canViewConnection = hasPermission('connection:view') || hasPermission('connection:manage');
  const items: MenuProps['items'] = [
    ...(hasPermission('dashboard:view') ? [{ key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">仪表盘</Link> }] : []),
    {
      type: 'group',
      label: '资金管理',
      children: [
        ...(hasPermission('transfer:create') ? [{ key: '/transfer', icon: <BankOutlined />, label: <Link to="/transfer">发起转账</Link> }] : []),
        ...(hasPermission('transaction:view') ? [{ key: '/transactions', icon: <TransactionOutlined />, label: <Link to="/transactions">交易记录</Link> }] : []),
      ],
    },
    {
      type: 'group',
      label: '自动入账',
      children: [
        ...(hasPermission('statement:import') ? [{ key: '/statements/import', icon: <FileAddOutlined />, label: <Link to="/statements/import">导入流水</Link> }] : []),
        ...(hasPermission('statement:view') ? [{ key: '/statements/batches', icon: <DatabaseOutlined />, label: <Link to="/statements/batches">流水批次</Link> }] : []),
        ...(hasPermission('statement:review') ? [{ key: '/statements/review', icon: <FileSearchOutlined />, label: <Link to="/statements/review">人工复核</Link> }] : []),
        ...(hasPermission('voucher:push') ? [{ key: '/statements/vouchers', icon: <SendOutlined />, label: <Link to="/statements/vouchers">金蝶制证</Link> }] : []),
        ...(hasPermission('reconciliation:view') ? [{ key: '/statements/reconciliation', icon: <AuditOutlined />, label: <Link to="/statements/reconciliation">对账与追溯</Link> }] : []),
      ],
    },
    ...(hasPermission('user:manage') ? [{ type: 'group' as const, label: '系统管理', children: [{ key: '/users', icon: <TeamOutlined />, label: <Link to="/users">用户管理</Link> }] }] : []),
    {
      key: 'connections-and-operations',
      icon: <ApartmentOutlined />,
      label: '连接与运营',
      children: [
        ...(canViewConnection ? [{ key: 'connection-management', icon: <SettingOutlined />, label: '直联管理', children: [{ key: '/connections/apps', label: <Link to="/connections/apps">应用管理</Link> }, { key: '/connections/agreements', label: <Link to="/connections/agreements">签约管理</Link> }, { key: '/connections/preferences', label: <Link to="/connections/preferences">个性化设置</Link> }] }] : []),
        ...((hasPermission('operation:monitor') || hasPermission('operation:log:view')) ? [{ key: 'monitoring-and-logs', icon: <RadarChartOutlined />, label: '监控与日志', children: [...(hasPermission('operation:monitor') ? [{ key: '/operations/connectivity', label: <Link to="/operations/connectivity">直联状态监控</Link> }, { key: '/operations/tasks', label: <Link to="/operations/tasks">任务执行</Link> }] : []), ...(hasPermission('operation:log:view') ? [{ key: '/operations/logs', label: <Link to="/operations/logs">日志查询</Link> }] : [])] }] : []),
        ...((['bankdata:balance:view', 'bankdata:statement:view', 'bankdata:receipt:view', 'bankdata:reconciliation:view', 'bankdata:payment:view', 'bankdata:payroll:view'] as const).some(hasPermission) ? [{ key: 'data-query', icon: <SearchOutlined />, label: '数据查询', children: [...(hasPermission('bankdata:balance:view') ? [{ key: '/bank-data/balances', label: <Link to="/bank-data/balances">余额</Link> }] : []), ...(hasPermission('bankdata:statement:view') ? [{ key: '/bank-data/statements', label: <Link to="/bank-data/statements">流水</Link> }] : []), ...(hasPermission('bankdata:receipt:view') ? [{ key: '/bank-data/receipts', label: <Link to="/bank-data/receipts">回单</Link> }] : []), ...(hasPermission('bankdata:reconciliation:view') ? [{ key: '/bank-data/reconciliations', label: <Link to="/bank-data/reconciliations">对账单</Link> }] : []), ...(hasPermission('bankdata:payment:view') ? [{ key: '/bank-data/payments', label: <Link to="/bank-data/payments">支付记录</Link> }] : []), ...(hasPermission('bankdata:payroll:view') ? [{ key: '/bank-data/payroll', label: <Link to="/bank-data/payroll">代发</Link> }] : [])] }] : []),
      ],
    },
  ];
  const menuItems = items.filter((item) => {
    if (!item) return false;
    if ('children' in item && item.type === 'group' && !item.children?.length) return false;
    return !('children' in item && item.key === 'connections-and-operations' && !item.children?.length);
  });
  const logoutAndRedirect = () => {
    logout();
    navigate('/login');
  };
  return <Layout className="app-shell"><Sider breakpoint="lg" collapsedWidth="64" collapsible><div className="brand"><div className="brand-mark">F</div><span>FINFLOW</span></div><div className="workspace-label">企业财务工作台</div><Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} defaultOpenKeys={['connections-and-operations']} items={menuItems} /></Sider><Layout><Header className="topbar"><div><div className="eyebrow">FINFLOW / 企业财务工作台</div><h1>{pageTitles[location.pathname] || '财务工作台'}</h1></div><Dropdown menu={{ items: [{ key: 'profile', label: user?.email || '个人资料', icon: <UserOutlined /> }, { type: 'divider' }, { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, onClick: logoutAndRedirect }] }}><Button type="text" className="profile-button"><Avatar size={32} icon={<UserOutlined />} /><span>{user?.username}</span></Button></Dropdown></Header><Content className="page-content"><Outlet /></Content></Layout></Layout>;
}

function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const next = (location.state as { from?: string } | null)?.from;
  const target = next?.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login') ? next : '/dashboard';
  if (status === 'authenticated') return <Navigate to={target} replace />;
  const submit = async (values: { username: string; password: string }) => {
    setLoading(true);
    setError(undefined);
    try {
      await login(values.username, values.password);
      navigate(target, { replace: true });
    } catch (reason) {
      setError(reason instanceof ApiRequestError && reason.status === 401 ? '账号或密码错误' : reason instanceof Error ? reason.message : '登录未能完成，请稍后重试');
    } finally {
      setLoading(false);
    }
  };
  return <div className="auth-page"><div className="auth-panel"><div className="brand brand-light"><div className="brand-mark">F</div><span>FINFLOW</span></div><div className="auth-copy"><div className="eyebrow">企业级资金管理平台</div><h1>让每一笔资金，<br /><em>清晰且可追溯。</em></h1><p>从流水导入到人工复核与制证追溯，在一个受控工作台完成协作。</p></div><div className="auth-note"><SafetyCertificateOutlined /> 数据访问受角色权限保护</div></div><div className="auth-form-wrap"><div className="auth-form"><span className="section-kicker">欢迎回来</span><h2>登录财务工作台</h2><p className="muted">使用您的企业账号继续</p>{location.search.includes('reason=expired') && <Alert className="login-alert" type="warning" showIcon message="登录已失效，请重新登录" />}{error && <Alert className="login-alert" type="error" showIcon message={error} />}<Form layout="vertical" onFinish={submit}><Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}><Input size="large" autoFocus placeholder="用户名或邮箱" /></Form.Item><Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}><Input.Password size="large" placeholder="请输入密码" /></Form.Item><div className="form-meta"><span>登录即表示同意平台安全政策</span><Link to="/register">注册账号</Link></div><Button loading={loading} htmlType="submit" type="primary" size="large" block>进入工作台</Button></Form></div></div></div>;
}

function Register() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<'success' | 'error'>();
  const [error, setError] = useState<string>();
  const submit = async (values: { username: string; email: string; password: string }) => {
    setLoading(true);
    setError(undefined);
    try {
      await authApi.register(values);
      setResult('success');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '注册未能完成，请稍后重试');
      setResult('error');
    } finally {
      setLoading(false);
    }
  };
  if (result === 'success') return <div className="auth-page auth-page-simple"><div className="auth-form-wrap"><Result status="success" title="注册已提交" subTitle="账号需由管理员激活后才能访问企业数据。" extra={<Button type="primary" onClick={() => navigate('/login')}>返回登录</Button>} /></div></div>;
  return <div className="auth-page auth-page-simple"><div className="auth-form-wrap"><div className="auth-form"><Link to="/login" className="back-link">返回登录</Link><span className="section-kicker">创建成员账号</span><h2>注册企业账号</h2><p className="muted">注册后由管理员分配企业权限</p>{result === 'error' && <Alert className="login-alert" type="error" showIcon message={error} />}<Form layout="vertical" onFinish={submit}><Form.Item label="用户名" name="username" rules={[{ required: true }, { min: 3, max: 64 }]}><Input size="large" /></Form.Item><Form.Item label="工作邮箱" name="email" rules={[{ required: true, type: 'email' }]}><Input size="large" /></Form.Item><Form.Item label="密码" name="password" rules={[{ required: true, min: 8 }, { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '密码需同时包含字母和数字' }]}><Input.Password size="large" /></Form.Item><Form.Item label="确认密码" name="confirm" dependencies={['password']} rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value) => !value || getFieldValue('password') === value ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) })]}><Input.Password size="large" /></Form.Item><Button loading={loading} htmlType="submit" type="primary" size="large" block>提交注册</Button></Form></div></div></div>;
}

function Dashboard() {
  return <><div className="page-heading"><div><span className="section-kicker">财务总览</span><h2>工作台</h2><p className="muted">资金概览接口接入后将在此展示服务端汇总，不使用本地演示金额。</p></div></div><Card><Empty description="资金概览数据源尚未接入" /></Card></>;
}

type ImportFormValues = { sourceName?: string; sourceMode: 'JSON' | 'MOCK'; payload?: string };

function parseStatementPayload(text: string): { sourceName?: string; records: StatementRecordInput[] } {
  const parsed: unknown = JSON.parse(text);
  if (Array.isArray(parsed)) return { records: parsed as StatementRecordInput[] };
  if (parsed && typeof parsed === 'object' && Array.isArray((parsed as { records?: unknown }).records)) {
    const body = parsed as { sourceName?: unknown; records: StatementRecordInput[] };
    return { sourceName: typeof body.sourceName === 'string' ? body.sourceName : undefined, records: body.records };
  }
  throw new Error('JSON 必须是流水数组，或包含 records 数组的对象');
}

function ImportStatements() {
  const [form] = Form.useForm<ImportFormValues>();
  const [loading, setLoading] = useState(false);
  const [batch, setBatch] = useState<StatementImportBatch>();
  const [error, setError] = useState<string>();
  const mode = Form.useWatch('sourceMode', form) || 'JSON';
  const submit = async (values: ImportFormValues) => {
    setLoading(true);
    setError(undefined);
    try {
      const payload = values.sourceMode === 'JSON' ? parseStatementPayload(values.payload || '') : { records: [] as StatementRecordInput[] };
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
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 入口</span><h2>导入流水</h2><p className="muted">JSON 内容由服务端校验并创建批次；模拟来源仅请求服务端当前配置的采集器。</p></div></div><Row gutter={[16, 16]}><Col xs={24} xl={15}><Card title="新建导入批次"><Form form={form} layout="vertical" initialValues={{ sourceMode: 'JSON' }} onFinish={submit}><Form.Item label="导入来源" name="sourceMode"><Radio.Group options={[{ value: 'JSON', label: 'JSON 文件或内容' }, { value: 'MOCK', label: '模拟来源（服务端采集器）' }]} /></Form.Item><Form.Item label="来源名称" name="sourceName" rules={[{ max: 128 }]}><Input placeholder={mode === 'JSON' ? '例如：2026-08-银行流水.json' : '例如：本地模拟采集'} /></Form.Item>{mode === 'JSON' ? <><Form.Item label="选择 JSON 文件"><Upload accept="application/json,.json" maxCount={1} beforeUpload={readJson}><Button>选择文件</Button></Upload></Form.Item><Form.Item label="JSON 内容" name="payload" rules={[{ required: true, message: '请提供 JSON 流水内容' }]} extra="支持流水数组，或包含 sourceName 和 records 数组的对象。"><Input.TextArea rows={12} placeholder="粘贴待导入的 JSON 内容" /></Form.Item></> : <Alert type="info" showIcon message="不会在浏览器生成模拟流水" description="该请求只触发服务端已配置的模拟采集器；若服务端未启用该采集器，页面会显示其返回的错误。" />} {error && <Alert className="form-error" type="error" showIcon message="导入未完成" description={error} />}<Button type="primary" htmlType="submit" loading={loading}>{mode === 'JSON' ? '提交 JSON 导入' : '请求模拟来源导入'}</Button></Form></Card></Col><Col xs={24} xl={9}>{batch ? <Card title="服务端导入结果"><Descriptions column={1} size="small"><Descriptions.Item label="批次编号"><span className="mono">{batch.batchNo}</span></Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={batch.status} /></Descriptions.Item><Descriptions.Item label="来源">{batch.sourceType} {batch.sourceName ? `· ${batch.sourceName}` : ''}</Descriptions.Item><Descriptions.Item label="总记录数">{batch.totalCount}</Descriptions.Item><Descriptions.Item label="已导入">{batch.importedCount}</Descriptions.Item><Descriptions.Item label="重复">{batch.duplicateCount}</Descriptions.Item><Descriptions.Item label="无效">{batch.invalidCount}</Descriptions.Item>{batch.errorMessage && <Descriptions.Item label="服务端说明">{batch.errorMessage}</Descriptions.Item>}</Descriptions></Card> : <Card title="导入结果"><Empty description="提交后展示服务端返回的批次结果" /></Card>}</Col></Row></>;
}

function BatchList() {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<string>();
  const loader = useCallback(() => statementApi.listBatches({ page, size: 20, status }), [page, status]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementImportBatch>>(loader, [loader]);
  const columns: TableColumnsType<StatementImportBatch> = [{ title: '批次编号', dataIndex: 'batchNo', render: (value) => <span className="mono">{value}</span> }, { title: '来源', render: (_, row) => <>{row.sourceType}<span className="table-sub">{row.sourceName || '--'}</span></> }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '记录', render: (_, row) => <span>{row.importedCount} / {row.totalCount}</span> }, { title: '重复 / 无效', render: (_, row) => <span>{row.duplicateCount} / {row.invalidCount}</span> }, { title: '完成时间', dataIndex: 'completedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 批次</span><h2>流水批次</h2><p className="muted">批次数据仅来自服务端导入记录。</p></div><Link to="/statements/import"><Button type="primary" icon={<FileAddOutlined />}>导入流水</Button></Link></div><Card className="filter-card"><Space wrap><label>批次状态</label><Input value={status} onChange={(event) => { setPage(1); setStatus(event.target.value || undefined); }} placeholder="输入服务端状态" allowClear /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="暂无服务端流水批次" /> }} scroll={{ x: 780 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card></>;
}

function AuditDrawer({ statement, onClose }: { statement?: StatementRecord; onClose: () => void }) {
  const loader = useCallback(() => statement ? statementApi.get(statement.id) : Promise.resolve<StatementDetail | undefined>(undefined), [statement]);
  const { data, loading, error, reload } = useRemote<StatementDetail | undefined>(loader, [loader]);
  const auditTrail: StatementAuditEvent[] = data?.auditTrail || [];
  return <Drawer title={statement ? `追溯记录 · ${statement.statementNo}` : '追溯记录'} width={520} open={Boolean(statement)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : auditTrail.length ? <Timeline items={auditTrail.map((event) => ({ color: event.result === 'SUCCESS' ? 'green' : event.result === 'FAILED' ? 'red' : 'blue', children: <div><strong>{event.action}</strong><div className="table-sub">{event.previousStatus || '--'} → {event.currentStatus || '--'} · 操作人 {event.operatorId || '--'}</div>{event.detail && <div className="timeline-detail">{event.detail}</div>}<div className="table-sub">{dateTime(event.createdAt)}</div></div> }))} /> : <Empty description="该流水暂无追溯事件" />}</Drawer>;
}

function ReviewStatements() {
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<StatementRecord>();
  const [trace, setTrace] = useState<StatementRecord>();
  const [form] = Form.useForm<StatementReviewRequest>();
  const [submitting, setSubmitting] = useState(false);
  const loader = useCallback(() => statementApi.list({ page, size: 20, reviewStatus: 'PENDING' }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
  const review = async () => {
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

function VoucherStatements() {
  const [page, setPage] = useState(1);
  const [trace, setTrace] = useState<StatementRecord>();
  const [pushingId, setPushingId] = useState<number>();
  const loader = useCallback(() => statementApi.list({ page, size: 20 }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<StatementRecord>>(loader, [loader]);
  const push = async (record: StatementRecord) => {
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

function Reconciliation() {
  const dashboardLoader = useCallback(() => statementApi.dashboard(), []);
  const statementsLoader = useCallback(() => statementApi.list({ page: 1, size: 10 }), []);
  const dashboard = useRemote<StatementDashboard>(dashboardLoader, [dashboardLoader]);
  const statements = useRemote<PageResponse<StatementRecord>>(statementsLoader, [statementsLoader]);
  const [trace, setTrace] = useState<StatementRecord>();
  const metrics = dashboard.data;
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 对账</span><h2>对账与追溯</h2><p className="muted">汇总与流水追溯分开加载；任一接口失败不会被显示为零值。</p></div></div>{dashboard.error ? <ResourceFailure error={dashboard.error} onRetry={dashboard.reload} /> : <Row gutter={[16, 16]}>{[['导入流水', metrics?.totalCount], ['待复核', metrics?.pendingReviewCount], ['已复核通过', metrics?.approvedCount], ['已制证', metrics?.pushedCount]].map(([title, value]) => <Col xs={24} sm={12} xl={6} key={String(title)}><Card className="metric-card"><Statistic title={title as string} value={dashboard.loading ? undefined : value as number | undefined} suffix="笔" valueStyle={{ color: '#16262b' }} /></Card></Col>)}</Row>}<Row gutter={[16, 16]} className="dashboard-grid"><Col xs={24} xl={10}><Card title="金额汇总">{dashboard.loading ? <Skeleton active paragraph={{ rows: 4 }} /> : dashboard.error ? null : <Descriptions column={1} size="small"><Descriptions.Item label="导入金额">{money(metrics?.totalAmount)}</Descriptions.Item><Descriptions.Item label="已复核金额">{money(metrics?.approvedAmount)}</Descriptions.Item><Descriptions.Item label="已制证金额">{money(metrics?.pushedAmount)}</Descriptions.Item><Descriptions.Item label="无效流水">{metrics?.invalidCount ?? '--'} 笔</Descriptions.Item><Descriptions.Item label="已驳回">{metrics?.rejectedCount ?? '--'} 笔</Descriptions.Item></Descriptions>}</Card></Col><Col xs={24} xl={14}><Card title="最近流水" extra={<Link to="/statements/batches">查看批次</Link>}>{statements.error ? <ResourceFailure error={statements.error} onRetry={statements.reload} /> : <Table rowKey="id" loading={statements.loading} pagination={false} dataSource={statements.data?.records || []} locale={{ emptyText: <Empty description="暂无可追溯流水" /> }} columns={[{ title: '流水号', dataIndex: 'statementNo', render: (value) => <span className="mono">{value}</span> }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value) => money(value) }, { title: '复核', dataIndex: 'reviewStatus', render: (value) => <StatusTag status={value} /> }, { title: '制证', dataIndex: 'pushStatus', render: (value) => <StatusTag status={value} /> }, { title: '追溯', render: (_, row) => <Button type="link" onClick={() => setTrace(row)}>查看</Button> }]} scroll={{ x: 700 }} />}</Card></Col></Row><AuditDrawer statement={trace} onClose={() => setTrace(undefined)} /></>;
}

function PhaseOneNotice({ status, message }: { status?: string; message?: string }) {
  return <Alert className="phase-one-notice" type="warning" showIcon message="一期未启用真实银行直联" description={<span>本区域仅展示服务端授权返回的未启用或模拟元数据，不调用银行 SDK/API，也不会生成余额、流水、回单或支付结果。{status && <> 当前服务端状态：<StatusTag status={status} />。</>}{message && <> {message}</>}</span>} />;
}

function PreservedFinancePage({ title, description }: { title: string; description: string }) {
  return <><div className="page-heading"><div><span className="section-kicker">资金管理</span><h2>{title}</h2><p className="muted">{description}</p></div></div><Card><Alert type="info" showIcon message="入口与路由已保留" description="本次连接与运营功能不修改支付、调拨、交易或用户管理的业务状态机。未接入的页面数据不会使用浏览器演示数据代替。" /></Card></>;
}

function ConnectionConfigurationPage({ section, title, description }: { section: 'applications' | 'contracts' | 'preferences'; title: string; description: string }) {
  const loader = useCallback(() => operationsApi.configuration(section), [section]);
  const { data, loading, error, reload } = useRemote<ConnectionConfiguration>(loader, [loader]);
  const columns: TableColumnsType<ConnectionConfiguration['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '显示名称', dataIndex: 'displayName' }, { title: '提供方类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近检查', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">连接与运营 / 直联管理</span><h2>{title}</h2><p className="muted">{description}</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="服务端连接元数据">{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="尚未配置直联应用" /> }} scroll={{ x: 780 }} />{data?.supportedProviderTypes?.length ? <div className="capability-meta">支持的模拟提供方：{data.supportedProviderTypes.join('、')}</div> : null}</>}</Card>}</>;
}

function ConnectionMonitoring() {
  const loader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data, loading, error, reload } = useRemote<ConnectionOverview>(loader, [loader]);
  const columns: TableColumnsType<ConnectionOverview['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '名称', dataIndex: 'displayName' }, { title: '环境 / 类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近心跳', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">连接与运营 / 监控与日志</span><h2>直联状态监控</h2><p className="muted">仅呈现服务端的受控状态摘要，不将模拟或未启用状态描述为银行已连接。</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="连接状态"><>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="没有可监控的直联对象" /> }} scroll={{ x: 760 }} /></>}</></Card>}</>;
}

function SyncJobDrawer({ job, onClose }: { job?: BankSyncJob; onClose: () => void }) {
  const loader = useCallback(() => job ? bankPipelineApi.getJob(job.id) : Promise.resolve<BankSyncJobDetail | undefined>(undefined), [job]);
  const { data, loading, error, reload } = useRemote<BankSyncJobDetail | undefined>(loader, [loader]);
  const detail = data?.job;
  return <Drawer title={job ? `同步任务 · ${job.jobNo}` : '同步任务'} width={540} open={Boolean(job)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 7 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : detail ? <><PhaseOneNotice status={detail.status} /><Descriptions column={1} size="small" bordered><Descriptions.Item label="任务类型">{detail.jobType}</Descriptions.Item><Descriptions.Item label="触发方式">{detail.triggerType}</Descriptions.Item><Descriptions.Item label="连接标识">{detail.connectionCode || '--'}</Descriptions.Item><Descriptions.Item label="请求编号"><span className="mono">{detail.requestId || '--'}</span></Descriptions.Item><Descriptions.Item label="创建时间">{dateTime(detail.createdAt)}</Descriptions.Item><Descriptions.Item label="开始 / 完成">{dateTime(detail.startedAt)} / {dateTime(detail.completedAt)}</Descriptions.Item><Descriptions.Item label="服务端摘要">{detail.summary || '--'}</Descriptions.Item></Descriptions>{detail.requestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detail.requestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}<h3 className="drawer-section-title">状态时间线</h3>{data.timeline?.length ? <Timeline items={data.timeline.map((event) => ({ color: statusColor(event.status), children: <div><StatusTag status={event.status} /><strong>{event.stage}</strong>{event.message && <div className="timeline-detail">{event.message}</div>}<div className="table-sub">{dateTime(event.occurredAt)} · <span className="mono">{event.requestId || detail.requestId || '--'}</span></div></div> }))} /> : <Empty description="服务端未返回状态时间线" />}</> : <Empty description="未找到同步任务详情" />}</Drawer>;
}

function OperationTasks() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<string>();
  const [selected, setSelected] = useState<BankSyncJob>();
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [form] = Form.useForm<BankSyncJobTrigger>();
  const loader = useCallback(() => bankPipelineApi.listJobs({ page, size: 20, status }), [page, status]);
  const { data, loading, error, reload } = useRemote<PageResponse<BankSyncJob>>(loader, [loader]);
  const trigger = async () => {
    const values = await form.validateFields();
    setTriggering(true);
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
  };
  const columns: TableColumnsType<BankSyncJob> = [{ title: '任务编号', dataIndex: 'jobNo', render: (value) => <span className="mono">{value}</span> }, { title: '任务类型', dataIndex: 'jobType' }, { title: '触发方式', dataIndex: 'triggerType' }, { title: '连接标识', dataIndex: 'connectionCode', render: (value) => value || '--' }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '创建时间', dataIndex: 'createdAt', render: (value) => dateTime(value) }, { title: '操作', fixed: 'right', render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>详情</Button> }];
  return <><div className="page-heading"><div><span className="section-kicker">连接与运营 / 监控与日志</span><h2>任务执行</h2><p className="muted">任务由服务端持久化、幂等与审计；浏览器只提交受控触发请求并查看安全摘要。</p></div>{hasPermission('bank-sync:trigger') && <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => setTriggerOpen(true)}>手动触发同步</Button>}</div><Card className="filter-card"><Space wrap><label>状态</label><Input value={status} allowClear placeholder="输入服务端状态" onChange={(event) => { setPage(1); setStatus(event.target.value || undefined); }} /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><PhaseOneNotice /><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前没有服务端同步任务" /> }} scroll={{ x: 980 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><Modal title="手动触发同步" open={triggerOpen} onCancel={() => { setTriggerOpen(false); form.resetFields(); }} onOk={() => void trigger()} okText="创建同步任务" confirmLoading={triggering} destroyOnClose><Alert type="warning" showIcon message="不会直接调用银行" description="提交后由服务端创建可追溯任务；未启用或模拟适配器将由服务端返回对应状态。" /><Form form={form} layout="vertical" initialValues={{ jobType: 'STATEMENT_PULL' }} className="sync-job-form"><Form.Item label="任务类型" name="jobType" rules={[{ required: true }]}><Input placeholder="例如：STATEMENT_PULL" /></Form.Item><Form.Item label="连接标识" name="connectionCode"><Input placeholder="可选，由服务端校验授权范围" /></Form.Item><Form.Item label="开始时间" name="windowStart"><Input placeholder="可选 ISO-8601 时间" /></Form.Item><Form.Item label="结束时间" name="windowEnd"><Input placeholder="可选 ISO-8601 时间" /></Form.Item></Form></Modal><SyncJobDrawer job={selected} onClose={() => setSelected(undefined)} /></>;
}

function OperationLogs() {
  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(1);
  const [requestId, setRequestId] = useState<string>(() => searchParams.get('requestId') || '');
  const loader = useCallback(() => operationsApi.logs({ page, size: 20, requestId }), [page, requestId]);
  const { data, loading, error, reload } = useRemote<PageResponse<OperationLog>>(loader, [loader]);
  const columns: TableColumnsType<OperationLog> = [{ title: '时间', dataIndex: 'occurredAt', render: (value) => dateTime(value) }, { title: '级别', dataIndex: 'level', render: (value) => <StatusTag status={value} /> }, { title: '事件', dataIndex: 'eventType' }, { title: '结果', dataIndex: 'result', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '安全摘要', dataIndex: 'message', ellipsis: true, render: (value) => value || '--' }];
  return <><div className="page-heading"><div><span className="section-kicker">连接与运营 / 监控与日志</span><h2>日志查询</h2><p className="muted">仅显示服务端脱敏的日志摘要；密钥、令牌、私钥和完整账号不会在浏览器中回显。</p></div></div><Card className="filter-card"><Space wrap><label>请求编号</label><Input value={requestId} allowClear placeholder="输入请求编号" onChange={(event) => { setPage(1); setRequestId(event.target.value); }} /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><PhaseOneNotice /><Table rowKey={(row) => `${row.taskId || '--'}-${row.occurredAt}-${row.eventType}`} loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有日志" /> }} scroll={{ x: 920 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card></>;
}

const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
  receipts: { title: '回单查询', permission: 'bankdata:receipt:view' },
  reconciliations: { title: '对账单查询', permission: 'bankdata:reconciliation:view' },
  payments: { title: '支付记录查询', permission: 'bankdata:payment:view' },
  payroll: { title: '代发查询', permission: 'bankdata:payroll:view' },
} as const;

function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const [page, setPage] = useState(1);
  const [submitted, setSubmitted] = useState(false);
  const [filters, setFilters] = useState({ keyword: '', accountId: '', status: '' });
  const [draft, setDraft] = useState(filters);
  const [selected, setSelected] = useState<BankDataProjection>();
  const loader = useCallback(() => submitted ? bankPipelineApi.queryProjection(resource, { page, size: 20, keyword: filters.keyword || undefined, accountId: filters.accountId || undefined, status: filters.status || undefined }) : Promise.resolve<BankDataProjectionPage>({ page, size: 20, total: 0, records: [] }), [resource, page, filters, submitted]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage>(loader, [loader]);
  const query = () => { setPage(1); setFilters(draft); setSubmitted(true); };
  const columns: TableColumnsType<BankDataProjection> = [{ title: '来源记录', dataIndex: 'sourceRecordId', render: (value) => value ? <span className="mono">{value}</span> : '--' }, { title: '来源系统', dataIndex: 'sourceSystem', render: (value) => value || '--' }, { title: '账户', dataIndex: 'accountMasked', render: (value) => value || '--' }, { title: '发生时间', dataIndex: 'occurredAt', render: (value) => dateTime(value) }, { title: '方向', dataIndex: 'direction', render: (value) => value || '--' }, { title: '金额', dataIndex: 'amount', align: 'right', render: (value, row) => value === undefined ? '--' : `${money(value)}${row.currency ? ` ${row.currency}` : ''}` }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value) => value || '--' }, { title: '追溯', fixed: 'right', render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>查看</Button> }];
  const definition = bankDataResources[resource];
  const emptyDescription = data?.enabled === false ? '真实直联未启用或该资源尚未获取。' : data?.status === 'SIMULATED' ? '当前筛选没有模拟业务投影。' : '服务端未返回符合条件的业务投影。';
  return <><div className="page-heading"><div><span className="section-kicker">连接与运营 / 银行数据投影</span><h2>{definition.title}</h2><p className="muted">仅消费服务端企业与账户授权后的业务投影；不读取原始报文、同步日志、密钥或令牌。</p></div></div><Card className="filter-card"><Space wrap><Input value={draft.keyword} placeholder="记录编号或摘要" onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))} /><Input value={draft.accountId} placeholder="账户标识" onChange={(event) => setDraft((current) => ({ ...current, accountId: event.target.value }))} /><Input value={draft.status} placeholder="服务端状态" onChange={(event) => setDraft((current) => ({ ...current, status: event.target.value }))} /><Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button></Space></Card><Card title="查询结果">{error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" /> : <><PhaseOneNotice status={data?.status} message={data?.message} />{data?.requestId && <div className="query-request-id">请求编号：<span className="mono">{data.requestId}</span><Link to={`/operations/logs?requestId=${encodeURIComponent(data.requestId)}`}>查看脱敏审计追溯</Link></div>}<Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description={emptyDescription} /> }} scroll={{ x: 1080 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={(next) => { setPage(next); setSubmitted(true); }} />}</>}</Card><Drawer title={selected ? `业务投影追溯 · ${selected.sourceRecordId || selected.id}` : '业务投影追溯'} width={500} open={Boolean(selected)} onClose={() => setSelected(undefined)}>{selected && <><Alert type="info" showIcon message="仅显示业务投影安全字段" description="原始报文、同步日志、凭据和完整账号不会暴露在此页面。" /><Descriptions className="projection-detail" column={1} size="small" bordered><Descriptions.Item label="来源记录">{selected.sourceRecordId || '--'}</Descriptions.Item><Descriptions.Item label="来源系统">{selected.sourceSystem || '--'}</Descriptions.Item><Descriptions.Item label="账户">{selected.accountMasked || '--'}</Descriptions.Item><Descriptions.Item label="发生时间">{dateTime(selected.occurredAt)}</Descriptions.Item><Descriptions.Item label="方向 / 金额">{selected.direction || '--'} / {selected.amount === undefined ? '--' : money(selected.amount)}</Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={selected.status} /></Descriptions.Item><Descriptions.Item label="请求编号"><span className="mono">{selected.requestId || data?.requestId || '--'}</span></Descriptions.Item><Descriptions.Item label="摘要">{selected.summary || '--'}</Descriptions.Item></Descriptions>{(selected.requestId || data?.requestId) && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(selected.requestId || data?.requestId || '')}`}>查看该请求的脱敏日志与审计追溯</Link>}</>}</Drawer></>;
}

function Forbidden() {
  return <Result status="403" title="暂无访问权限" subTitle="当前角色没有访问该页面的权限。" extra={<Link to="/dashboard"><Button type="primary">返回工作台</Button></Link>} />;
}

function AppRoutes() {
  const hydrate = useAuthStore((state) => state.hydrate);
  useEffect(() => { void hydrate(); }, [hydrate]);
  return <Routes><Route path="/login" element={<Login />} /><Route path="/register" element={<Register />} /><Route element={<AuthGuard />}><Route element={<Shell />}><Route path="/dashboard" element={<Dashboard />} /><Route element={<PermissionGuard permissions={['transfer:create']} />}><Route path="/transfer" element={<PreservedFinancePage title="发起转账" description="高风险资金操作保持独立，不由连接与运营模块触发或改写。" />} /></Route><Route element={<PermissionGuard permissions={['transaction:view']} />}><Route path="/transactions" element={<PreservedFinancePage title="交易记录" description="交易流水入口已保留，连接与运营查询不会替代交易记录。" />} /></Route><Route element={<PermissionGuard permissions={['user:manage']} />}><Route path="/users" element={<PreservedFinancePage title="用户管理" description="用户管理入口已保留，连接与运营权限不提升用户管理权限。" />} /></Route><Route element={<PermissionGuard permissions={['statement:import']} />}><Route path="/statements/import" element={<ImportStatements />} /></Route><Route element={<PermissionGuard permissions={['statement:view']} />}><Route path="/statements/batches" element={<BatchList />} /></Route><Route element={<PermissionGuard permissions={['statement:review']} />}><Route path="/statements/review" element={<ReviewStatements />} /></Route><Route element={<PermissionGuard permissions={['voucher:push']} />}><Route path="/statements/vouchers" element={<VoucherStatements />} /></Route><Route element={<PermissionGuard permissions={['reconciliation:view']} />}><Route path="/statements/reconciliation" element={<Reconciliation />} /></Route><Route element={<PermissionGuard permissions={['connection:view', 'connection:manage']} />}><Route path="/connections/apps" element={<ConnectionConfigurationPage section="applications" title="应用管理" description="管理未来直联应用的受控元数据；一期不建立真实银行连接。" />} /><Route path="/connections/agreements" element={<ConnectionConfigurationPage section="contracts" title="签约管理" description="展示签约准备度和模拟标记，不代表银行签约已生效。" />} /><Route path="/connections/preferences" element={<ConnectionConfigurationPage section="preferences" title="个性化设置" description="展示服务端配置摘要；浏览器不读取或保存密钥、证书或令牌。" />} /></Route><Route element={<PermissionGuard permissions={['operation:monitor']} />}><Route path="/operations/connectivity" element={<ConnectionMonitoring />} /><Route path="/operations/tasks" element={<OperationTasks />} /></Route><Route element={<PermissionGuard permissions={['operation:log:view']} />}><Route path="/operations/logs" element={<OperationLogs />} /></Route><Route element={<PermissionGuard permissions={['bankdata:balance:view']} />}><Route path="/bank-data/balances" element={<BankDataQueryPage resource="balances" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:statement:view']} />}><Route path="/bank-data/statements" element={<BankDataQueryPage resource="statements" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:receipt:view']} />}><Route path="/bank-data/receipts" element={<BankDataQueryPage resource="receipts" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:reconciliation:view']} />}><Route path="/bank-data/reconciliations" element={<BankDataQueryPage resource="reconciliations" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:payment:view']} />}><Route path="/bank-data/payments" element={<BankDataQueryPage resource="payments" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:payroll:view']} />}><Route path="/bank-data/payroll" element={<BankDataQueryPage resource="payroll" />} /></Route><Route path="/403" element={<Forbidden />} /></Route></Route><Route path="*" element={<Navigate to="/dashboard" replace />} /></Routes>;
}

export default function App() {
  return <BrowserRouter><AppRoutes /></BrowserRouter>;
}
