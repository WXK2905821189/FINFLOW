import { useCallback, useEffect, useMemo, useRef, useState, type DependencyList } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
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
  FileAddOutlined,
  LogoutOutlined,
  MenuOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  UserOutlined,
  NotificationOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { statementApi, authApi, bankApi, bankPipelineApi, operationsApi, feishuApi, validationApi, closingApi, auditApi } from './services/api';
import { ApiRequestError } from './services/http';
import { useAuthStore } from './store/auth';
import type {
  PageResponse,
  BankAccount,
  ConnectionConfiguration,
  ConnectionOverview,
  FeishuOverview,
  BankDataProjection,
  BankDataProjectionPage,
  BankSyncJob,
  BankSyncJobDetail,
  BankSyncJobTrigger,
  OperationLog,
  StatementAuditEvent,
  StatementDetail,
  StatementDashboard,
  StatementImportBatch,
  StatementRecord,
  StatementRecordInput,
  StatementReviewRequest,
  ValidationRule,
  AccountingMapping,
  ClosingPeriod,
  SystemAuditEvent,
} from './types';
import './styles.css';
import { buildProductNavigation, pageTitles, PRODUCT_MENU_STORAGE_KEY } from './modules/navigation/productNavigation';

const { Header, Sider, Content } = Layout;

const money = (value?: number | string) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : '--';
};

const dateTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '--');

const displayValue = (value?: string | number | null) => (value === undefined || value === null || value === '' ? '--' : String(value));

const hasAnyStatus = (status: string | undefined, patterns: RegExp[]) => Boolean(status && patterns.some((pattern) => pattern.test(status)));

const isUnavailableStatus = (status?: string) => hasAnyStatus(status, [/DISABLED/i, /UNAVAILABLE/i, /NOT_ENABLED/i, /NOT_CONFIGURED/i, /未启用/, /不可用/]);

const isSimulatedStatus = (status?: string) => hasAnyStatus(status, [/SIMULATED/i, /MOCK/i, /SANDBOX/i, /模拟/]);

const isFailedStatus = (status?: string) => hasAnyStatus(status, [/FAILED/i, /ERROR/i, /REJECTED/i, /INVALID/i, /失败/, /异常/]);

const cleanText = (value?: string | number | null) => displayValue(value);

const maskAccountDisplay = (value?: string | number | null) => {
  const text = displayValue(value);
  if (text === '--' || /\*/.test(text)) return text;
  return text.replace(/\d{8,}/g, (match) => `${match.slice(0, 4)}****${match.slice(-4)}`);
};

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
  const isForbidden = error instanceof ApiRequestError && error.status === 403;
  const description = error instanceof Error ? error.message : '请求未能完成，请稍后重试';
  return <Alert className="resource-alert" type="error" showIcon message={isForbidden ? '暂无访问权限' : '数据暂不可用'} description={description} action={<Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>} />;
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
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [openKeys, setOpenKeys] = useState<string[]>(() => {
    try {
      const saved = window.localStorage.getItem(PRODUCT_MENU_STORAGE_KEY);
      return saved ? JSON.parse(saved) as string[] : ['bank-access'];
    } catch {
      return ['bank-access'];
    }
  });
  const menuItems = buildProductNavigation(hasPermission);
  const logoutAndRedirect = () => {
    logout();
    navigate('/login');
  };
  const handleMenuOpenChange = (nextKeys: string[]) => {
    setOpenKeys(nextKeys);
    try {
      window.localStorage.setItem(PRODUCT_MENU_STORAGE_KEY, JSON.stringify(nextKeys));
    } catch {
      // Menu state is a convenience preference; storage failure must not block navigation.
    }
  };
  const closeMobileNavigation: MenuProps['onClick'] = ({ key }) => {
    if (String(key).startsWith('/')) setMobileNavigationOpen(false);
  };
  return <Layout className="app-shell"><Sider breakpoint="lg" collapsedWidth="64" collapsible><div className="brand"><div className="brand-mark">F</div><span>FINFLOW</span></div><div className="workspace-label">企业财务工作台</div><Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} openKeys={openKeys} onOpenChange={handleMenuOpenChange} items={menuItems} /></Sider><Layout><Header className="topbar"><div className="topbar-title"><Button className="mobile-navigation-button" type="text" aria-label="打开导航" icon={<MenuOutlined />} onClick={() => setMobileNavigationOpen(true)} /><div><div className="eyebrow">FINFLOW / 企业财务工作台</div><h1>{pageTitles[location.pathname] || '财务工作台'}</h1></div></div><Dropdown menu={{ items: [{ key: 'profile', label: user?.email || '个人资料', icon: <UserOutlined /> }, { type: 'divider' }, { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, onClick: logoutAndRedirect }] }}><Button type="text" className="profile-button"><Avatar size={32} icon={<UserOutlined />} /><span>{user?.username}</span></Button></Dropdown></Header><Content className="page-content"><Outlet /></Content></Layout><Drawer className="mobile-navigation" title="导航" placement="left" width={320} open={mobileNavigationOpen} onClose={() => setMobileNavigationOpen(false)}><Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} openKeys={openKeys} onOpenChange={handleMenuOpenChange} onClick={closeMobileNavigation} items={menuItems} /></Drawer></Layout>;
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
    if (loading) return;
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
    if (loading) return;
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
  const canReadReconciliation = useAuthStore((state) => state.hasPermission('reconciliation:view'));
  const loader = useCallback(() => canReadReconciliation ? statementApi.dashboard() : Promise.resolve<StatementDashboard | undefined>(undefined), [canReadReconciliation]);
  const { data, loading, error, reload } = useRemote<StatementDashboard | undefined>(loader, [loader]);
  const todoItems = data ? [
    { label: '待复核流水', value: data.pendingReviewCount, tone: data.pendingReviewCount ? 'warning' : 'normal' },
    { label: '入账失败/异常', value: data.invalidCount, tone: data.invalidCount ? 'danger' : 'normal' },
    { label: '待完成对账', value: Math.max(data.totalCount - data.pushedCount, 0), tone: data.totalCount > data.pushedCount ? 'warning' : 'normal' },
  ] : [];
  return <><div className="page-heading"><div><span className="section-kicker">财务总览</span><h2>工作台</h2><p className="muted">先处理阻断项，再查看采集、复核、入账和对账进度。所有数字均来自服务端汇总。</p></div></div><Alert className="phase-one-notice" type="info" showIcon message="今日作业入口" description="工作台只展示当前企业授权范围内的业务摘要；未接入的银行、金蝶或飞书数据不会用本地演示值补齐。" />{error ? <ResourceFailure error={error} onRetry={reload} /> : !canReadReconciliation ? <Card><Empty description="当前角色没有财务汇总查看权限" /></Card> : <><div className="workbench-todos">{todoItems.map((item) => <div className={`todo-item todo-${item.tone}`} key={item.label}><span>{item.label}</span><strong>{loading ? '--' : item.value}</strong><small>笔</small></div>)}{!loading && todoItems.length === 0 && <Empty description="暂无可展示的待办汇总" />}</div><Card className="workbench-summary" title="处理概览"><Descriptions column={{ xs: 1, sm: 2, xl: 4 }} size="small"><Descriptions.Item label="采集流水">{loading ? '--' : `${data?.totalCount ?? '--'} 笔`}</Descriptions.Item><Descriptions.Item label="已复核">{loading ? '--' : `${data?.approvedCount ?? '--'} 笔`}</Descriptions.Item><Descriptions.Item label="已制证">{loading ? '--' : `${data?.pushedCount ?? '--'} 笔`}</Descriptions.Item><Descriptions.Item label="已制证金额">{loading ? '--' : money(data?.pushedAmount)}</Descriptions.Item></Descriptions></Card></>}</>;
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
    if (loading) return;
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

function VoucherStatements() {
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
  return <><div className="page-heading"><div><span className="section-kicker">系统管理</span><h2>{title}</h2><p className="muted">{description}</p></div></div><Card><Alert type="info" showIcon message="入口与路由已保留" description="本次银行接入功能不修改历史支付、调拨、交易或用户管理的业务状态机。未接入的页面数据不会使用浏览器演示数据代替。" /></Card></>;
}

function BankAccountPage() {
  const loader = useCallback(() => bankApi.accounts(), []);
  const { data, loading, error, reload } = useRemote<BankAccount[]>(loader, [loader]);
  const columns: TableColumnsType<BankAccount> = [
    { title: '银行', dataIndex: 'bankCode', render: (value) => <span className="mono">{value}</span> },
    { title: '账户名称', dataIndex: 'accountName' },
    { title: '账号', dataIndex: 'maskedAccountNumber', render: (value) => <span className="mono">{value}</span> },
    { title: '币种', dataIndex: 'currency' },
    { title: '账户状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
    { title: '数据来源', render: () => <Tag color="blue">模拟配置</Tag> },
  ];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 账户</span><h2>银行账户</h2><p className="muted">展示当前企业已授权的银行账户；账号仅显示脱敏结果，余额和流水由采集任务更新。</p></div></div><Alert className="phase-one-notice" type="warning" showIcon message="真实银行直联未启用" description="当前账户连接为服务端模拟配置，不能代表已连通真实银行。" /> <Card title="企业授权账户">{error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="id" loading={loading} columns={columns} dataSource={data || []} pagination={false} locale={{ emptyText: <Empty description="当前企业暂无授权银行账户" /> }} scroll={{ x: 780 }} />}</Card></>;
}

function ConnectionConfigurationPage({ section, title, description }: { section: 'applications' | 'contracts' | 'preferences'; title: string; description: string }) {
  const loader = useCallback(() => operationsApi.configuration(section), [section]);
  const { data, loading, error, reload } = useRemote<ConnectionConfiguration>(loader, [loader]);
  const columns: TableColumnsType<ConnectionConfiguration['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '显示名称', dataIndex: 'displayName' }, { title: '提供方类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近检查', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 连接配置</span><h2>{title}</h2><p className="muted">{description}</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="服务端连接元数据">{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="尚未配置银行连接" /> }} scroll={{ x: 780 }} />{data?.supportedProviderTypes?.length ? <div className="capability-meta">支持的模拟提供方：{data.supportedProviderTypes.join('、')}</div> : null}</>}</Card>}</>;
}

function FeishuCollaboration() {
  const { hasPermission } = useAuthStore();
  const canManage = hasPermission('feishu:manage');
  const canNotify = hasPermission('feishu:notify');
  const loader = useCallback(() => feishuApi.overview(), []);
  const { data, loading, error, reload } = useRemote<FeishuOverview>(loader, [loader]);
  const [connectionName, setConnectionName] = useState('FINFLOW 飞书模拟连接');
  const [destinationName, setDestinationName] = useState('财务运营群（模拟）');
  const [destinationKey, setDestinationKey] = useState('mock-finance-ops');
  const [eventType, setEventType] = useState('SYNC_FAILED');
  const [summary, setSummary] = useState('这是一次受控的飞书模拟通知');
  const [working, setWorking] = useState(false);
  const connection = data?.connections[0];
  const destination = data?.destinations[0];
  const run = async (action: () => Promise<unknown>, success: string) => { setWorking(true); try { await action(); message.success(success); await reload(); } catch (reason) { message.error(reason instanceof Error ? reason.message : '操作未完成'); } finally { setWorking(false); } };
  return <><div className="page-heading"><div><span className="section-kicker">系统管理 / 协同配置</span><h2>飞书协同</h2><p className="muted">一期仅使用服务端飞书 MOCK，通知事件、幂等和发送记录均留在 FINFLOW；不连接真实飞书。</p></div></div><Alert className="phase-one-notice" type="warning" showIcon message="真实飞书未启用" description="当前页面只验证模拟连接、接收对象、通知策略和发送审计，不读取或保存 App Secret、Token 或 Webhook。模拟结果带有明确标识。" />{error ? <ResourceFailure error={error} onRetry={reload} /> : <Row gutter={[16, 16]}><Col xs={24} xl={10}><Card title="模拟连接配置">{loading ? <Skeleton active paragraph={{ rows: 5 }} /> : <><Descriptions column={1} size="small"><Descriptions.Item label="状态"><StatusTag status={data?.status} /></Descriptions.Item><Descriptions.Item label="说明">{data?.message}</Descriptions.Item><Descriptions.Item label="连接">{connection ? `${connection.displayName} · ${connection.mode}` : '尚未配置'}</Descriptions.Item></Descriptions>{canManage && <Space direction="vertical" style={{ width: '100%' }}><Input value={connectionName} onChange={(e) => setConnectionName(e.target.value)} placeholder="连接名称" /><Button loading={working} onClick={() => run(() => feishuApi.createConnection({ displayName: connectionName }), '模拟连接已创建')}>创建模拟连接</Button></Space>}</>}</Card></Col><Col xs={24} xl={14}><Card title="接收对象与通知验证">{loading ? <Skeleton active paragraph={{ rows: 5 }} /> : <><Descriptions column={1} size="small"><Descriptions.Item label="接收对象">{destination ? `${destination.displayName} · ${destination.destinationType}` : '尚未配置'}</Descriptions.Item><Descriptions.Item label="通知策略">{data?.policies.length ? `${data.policies.length} 条` : '尚未配置'}</Descriptions.Item><Descriptions.Item label="安全边界">只展示脱敏元数据和通知摘要</Descriptions.Item></Descriptions>{canManage && connection && !destination && <Space direction="vertical" style={{ width: '100%' }}><Input value={destinationName} onChange={(e) => setDestinationName(e.target.value)} placeholder="接收对象名称" /><Input value={destinationKey} onChange={(e) => setDestinationKey(e.target.value)} placeholder="模拟接收对象标识" /><Button loading={working} onClick={() => run(() => feishuApi.createDestination({ connectionId: connection.id, destinationType: 'CHAT', destinationKey, displayName: destinationName }), '模拟接收对象已创建')}>创建接收对象</Button></Space>}{canNotify && destination && <Space direction="vertical" style={{ width: '100%' }}><Input value={eventType} onChange={(e) => setEventType(e.target.value)} placeholder="事件类型" /><Input value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="安全通知摘要" /><Button type="primary" icon={<NotificationOutlined />} loading={working} onClick={() => run(() => feishuApi.notify({ eventType, severity: 'INFO', summary, destinationId: destination.id }), '模拟通知已发送并记录')}>发送模拟通知</Button></Space>}</>}</Card></Col><Col xs={24}><Card title="发送记录"><FeishuDeliveryTable /></Card></Col></Row>}</>;
}

function FeishuDeliveryTable() {
  const loader = useCallback(() => feishuApi.deliveries({ page: 1, size: 20 }), []);
  const { data, loading, error, reload } = useRemote(loader, [loader]);
  return error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="eventId" loading={loading} pagination={false} dataSource={data?.records || []} locale={{ emptyText: <Empty description="尚无飞书模拟发送记录" /> }} columns={[{ title: '事件', dataIndex: 'eventType' }, { title: '严重级别', dataIndex: 'severity' }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => <span className="mono">{value}</span> }, { title: '模拟消息号', dataIndex: 'providerMessageId', render: (value) => <span className="mono">{value || '--'}</span> }, { title: '时间', dataIndex: 'createdAt', render: (value) => dateTime(value) }]} scroll={{ x: 760 }} />;
}

function ConnectionMonitoring() {
  const loader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data, loading, error, reload } = useRemote<ConnectionOverview>(loader, [loader]);
  const columns: TableColumnsType<ConnectionOverview['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '名称', dataIndex: 'displayName' }, { title: '环境 / 类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近心跳', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>直联状态监控</h2><p className="muted">仅呈现服务端的受控状态摘要，不将模拟或未启用状态描述为银行已连接。</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="连接状态"><>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="没有可监控的直联对象" /> }} scroll={{ x: 760 }} /></>}</></Card>}</>;
}

function SyncJobDrawer({ job, onClose }: { job?: BankSyncJob; onClose: () => void }) {
  const loader = useCallback(() => job ? bankPipelineApi.getJob(job.id) : Promise.resolve<BankSyncJobDetail | undefined>(undefined), [job]);
  const { data, loading, error, reload } = useRemote<BankSyncJobDetail | undefined>(loader, [loader]);
  const detail = data?.job;
  return <Drawer title={job ? `同步任务 · ${job.jobNo}` : '同步任务'} width={560} open={Boolean(job)} onClose={onClose}>{loading ? <Skeleton active paragraph={{ rows: 7 }} /> : error ? <ResourceFailure error={error} onRetry={reload} /> : detail ? <><PhaseOneNotice status={detail.status} /><Descriptions column={1} size="small" bordered><Descriptions.Item label="任务编号"><span className="mono">{detail.jobNo}</span></Descriptions.Item><Descriptions.Item label="任务类型">{detail.jobType}</Descriptions.Item><Descriptions.Item label="触发方式">{detail.triggerType}</Descriptions.Item><Descriptions.Item label="连接标识">{displayValue(detail.connectionCode)}</Descriptions.Item><Descriptions.Item label="请求编号"><span className="mono">{displayValue(detail.requestId)}</span></Descriptions.Item><Descriptions.Item label="状态"><StatusTag status={detail.status} /></Descriptions.Item><Descriptions.Item label="创建时间">{dateTime(detail.createdAt)}</Descriptions.Item><Descriptions.Item label="开始 / 完成">{dateTime(detail.startedAt)} / {dateTime(detail.completedAt)}</Descriptions.Item><Descriptions.Item label="服务端摘要">{cleanText(detail.summary)}</Descriptions.Item></Descriptions>{detail.requestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detail.requestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}<h3 className="drawer-section-title">状态时间线</h3>{data.timeline?.length ? <Timeline items={data.timeline.map((event) => ({ color: statusColor(event.status), children: <div><StatusTag status={event.status} /><strong>{event.stage}</strong>{event.message && <div className="timeline-detail">{event.message}</div>}<div className="table-sub">{dateTime(event.occurredAt)} · 请求 <span className="mono">{event.requestId || detail.requestId || '--'}</span></div></div> }))} /> : <Empty description="服务端未返回状态时间线" />}</> : <Empty description="未找到同步任务详情" />}</Drawer>;
}

function OperationTasks() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canTriggerSync = hasPermission('bankdata:sync:trigger') || hasPermission('bankdata:sync') || hasPermission('bank-sync:trigger');
  const [page, setPage] = useState(1);
  const [taskFilters, setTaskFilters] = useState({ status: '', jobType: '', connectionCode: '', requestId: '' });
  const [draftFilters, setDraftFilters] = useState(taskFilters);
  const [submitted, setSubmitted] = useState(false);
  const [selected, setSelected] = useState<BankSyncJob>();
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [form] = Form.useForm<BankSyncJobTrigger>();
  const loader = useCallback(() => submitted ? bankPipelineApi.listJobs({ page, size: 20, status: taskFilters.status || undefined, jobType: taskFilters.jobType || undefined, connectionCode: taskFilters.connectionCode || undefined, requestId: taskFilters.requestId || undefined }) : Promise.resolve<PageResponse<BankSyncJob>>({ page, size: 20, total: 0, records: [] }), [page, taskFilters, submitted]);
  const { data, loading, error, reload } = useRemote<PageResponse<BankSyncJob>>(loader, [loader]);
  const trigger = async () => {
    if (triggering) return;
    const values = await form.validateFields();
    setTriggering(true);
    Modal.confirm({
      title: '确认创建同步任务',
      content: '浏览器只会向 FINFLOW 服务端提交任务请求，不会直接连接银行；未启用、模拟或失败状态以服务端返回为准。',
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
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>任务执行</h2><p className="muted">任务由服务端持久化、幂等与审计；浏览器只提交受控触发请求并查看安全摘要。</p></div>{canTriggerSync && <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => setTriggerOpen(true)}>手动触发同步</Button>}</div>{!canTriggerSync && <Alert className="resource-alert" type="info" showIcon message="手动同步入口未显示" description="当前角色没有同步触发权限；任务列表仍可按已有查看权限只读展示。" />}<Card className="filter-card"><div className="filter-toolbar"><div className="filter-fields"><Input value={draftFilters.jobType} allowClear placeholder="任务类型" onChange={(event) => updateTaskFilter('jobType', event.target.value)} /><Input value={draftFilters.connectionCode} allowClear placeholder="连接标识" onChange={(event) => updateTaskFilter('connectionCode', event.target.value)} /><Input value={draftFilters.status} allowClear placeholder="服务端状态" onChange={(event) => updateTaskFilter('status', event.target.value)} /><Input value={draftFilters.requestId} allowClear placeholder="请求编号" onChange={(event) => updateTaskFilter('requestId', event.target.value)} /></div><Space><Button type="primary" icon={<SearchOutlined />} onClick={submitTaskFilter}>查询</Button><Button onClick={resetTaskFilter}>重置</Button></Space></div></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted ? <Empty description="设置筛选条件后点击查询；页面打开不会创建或查询同步任务。" /> : <><PhaseOneNotice /><Table rowKey={(row) => row.jobNo || String(row.id)} loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有同步任务" /> }} scroll={{ x: 1180 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />}</>}</Card><Modal title="手动触发同步" open={triggerOpen} onCancel={() => { setTriggerOpen(false); form.resetFields(); }} onOk={() => void trigger()} okText="创建同步任务" confirmLoading={triggering} destroyOnClose><Alert type="warning" showIcon message="不会直接调用银行" description="提交后由服务端创建可追溯任务；未启用或模拟适配器将由服务端返回对应状态。" /><Form form={form} layout="vertical" initialValues={{ jobType: 'STATEMENT_PULL' }} className="sync-job-form"><Form.Item label="任务类型" name="jobType" rules={[{ required: true }]}><Input placeholder="例如：STATEMENT_PULL" /></Form.Item><Form.Item label="账户标识" name="bankAccountId" rules={[{ required: true, message: '请选择企业内授权账户' }]}><InputNumber min={1} precision={0} className="full-width-control" placeholder="请输入账户 ID" /></Form.Item><Form.Item label="连接标识" name="connectionCode"><Input placeholder="可选，由服务端校验授权范围" /></Form.Item><Form.Item label="开始时间" name="windowStart"><Input placeholder="可选 ISO-8601 时间" /></Form.Item><Form.Item label="结束时间" name="windowEnd"><Input placeholder="可选 ISO-8601 时间" /></Form.Item></Form></Modal><SyncJobDrawer job={selected} onClose={() => setSelected(undefined)} /></>;
}

function OperationLogs() {
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

const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
  receipts: { title: '回单查询', permission: 'bankdata:receipt:view' },
  reconciliations: { title: '对账单查询', permission: 'bankdata:reconciliation:view' },
  payroll: { title: '代发查询', permission: 'bankdata:payroll:view' },
} as const;

type BankQueryFilters = {
  keyword: string;
  accountId: string;
  status: string;
  sourceSystem: string;
  syncJobNo: string;
  requestId: string;
  from: string;
  to: string;
};

const emptyBankQueryFilters: BankQueryFilters = { keyword: '', accountId: '', status: '', sourceSystem: '', syncJobNo: '', requestId: '', from: '', to: '' };

function BankProjectionState({ data }: { data?: BankDataProjectionPage }) {
  if (!data) return null;
  const status = data.status;
  const type = data.enabled === false || isUnavailableStatus(status) ? 'warning' : isFailedStatus(status) ? 'error' : isSimulatedStatus(status) || data.simulated ? 'info' : 'success';
  const message = data.enabled === false || isUnavailableStatus(status) ? '真实银行直联未启用或该资源未获取' : isSimulatedStatus(status) || data.simulated ? '当前为服务端模拟/沙箱业务投影' : isFailedStatus(status) ? '服务端返回失败状态' : '服务端业务投影已返回';
  const description = data.message || '页面仅展示 FINFLOW 服务端返回的安全字段；未返回的同步信息会显示为未获取。';
  return <Alert className="phase-one-notice" type={type} showIcon message={message} description={<span>{description}{status && <> 服务端状态：<StatusTag status={status} /></>}</span>} />;
}

function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canTriggerSync = hasPermission('bankdata:sync:trigger') || hasPermission('bankdata:sync') || hasPermission('bank-sync:trigger');
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

function ValidationPage() {
  const canManage = useAuthStore((state) => state.hasPermission('validation:manage'));
  const [tab, setTab] = useState<'rules' | 'mappings'>('rules');
  const [page, setPage] = useState(1);
  const [form] = Form.useForm();
  const [open, setOpen] = useState(false);
  const loader = useCallback(() => tab === 'rules' ? validationApi.rules({ page, size: 20 }) : validationApi.mappings({ page, size: 20 }), [tab, page]);
  const { data, loading, error, reload } = useRemote<PageResponse<ValidationRule> | PageResponse<AccountingMapping>>(loader, [loader]);
  const save = async (values: Record<string, string | number>) => {
    try {
      if (tab === 'rules') await validationApi.createRule({ ruleCode: String(values.ruleCode), name: String(values.name), ruleType: String(values.ruleType), expression: String(values.expression), priority: Number(values.priority || 100) });
      else await validationApi.createMapping({ mappingCode: String(values.mappingCode), name: String(values.name), direction: String(values.direction), counterpartyKeyword: values.counterpartyKeyword ? String(values.counterpartyKeyword) : undefined, debitSubject: String(values.debitSubject), creditSubject: String(values.creditSubject), voucherTemplate: String(values.voucherTemplate) });
      message.success('草稿已保存'); setOpen(false); form.resetFields(); void reload();
    } catch (reason) { message.error(reason instanceof Error ? reason.message : '保存失败'); }
  };
  const activate = async (id: number, mapping: boolean) => { try { if (mapping) await validationApi.activateMapping(id); else await validationApi.activateRule(id); message.success('版本已启用'); void reload(); } catch (reason) { message.error(reason instanceof Error ? reason.message : '启用失败'); } };
  const rules = (data as PageResponse<ValidationRule> | undefined)?.records || [];
  const mappings = (data as PageResponse<AccountingMapping> | undefined)?.records || [];
  const columns: TableColumnsType<ValidationRule | AccountingMapping> = tab === 'rules'
    ? [{ title: '规则编码', dataIndex: 'ruleCode', render: (v) => <span className="mono">{v}</span> }, { title: '名称', dataIndex: 'name' }, { title: '类型', dataIndex: 'ruleType' }, { title: '表达式', dataIndex: 'expression', ellipsis: true }, { title: '版本', dataIndex: 'versionNo' }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '操作', render: (_, row) => canManage && row.status !== 'ACTIVE' ? <Button type="link" onClick={() => void activate(row.id, false)}>启用</Button> : null }]
    : [{ title: '映射编码', dataIndex: 'mappingCode', render: (v) => <span className="mono">{v}</span> }, { title: '名称', dataIndex: 'name' }, { title: '方向', dataIndex: 'direction' }, { title: '借方科目', dataIndex: 'debitSubject' }, { title: '贷方科目', dataIndex: 'creditSubject' }, { title: '凭证模板', dataIndex: 'voucherTemplate' }, { title: '版本', dataIndex: 'versionNo' }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '操作', render: (_, row) => canManage && row.status !== 'ACTIVE' ? <Button type="link" onClick={() => void activate(row.id, true)}>启用</Button> : null }];
  return <><div className="page-heading"><div><span className="section-kicker">自动入账 / 配置</span><h2>规则与映射</h2><p className="muted">规则和科目映射采用版本化草稿，启用后供后端校验/制证流程使用；原始流水不可在此修改。</p></div>{canManage && <Button type="primary" icon={<SettingOutlined />} onClick={() => setOpen(true)}>新建草稿</Button>}</div><Alert className="phase-one-notice" type="info" showIcon message="启用前请完成财务复核" description="当前页面只管理 FINFLOW 内部规则和金蝶模拟凭证映射，不代表真实金蝶规则已生效。" /><Card><Space className="segmented-tabs"><Button type={tab === 'rules' ? 'primary' : 'default'} onClick={() => { setTab('rules'); setPage(1); }}>校验规则</Button><Button type={tab === 'mappings' ? 'primary' : 'default'} onClick={() => { setTab('mappings'); setPage(1); }}>入账映射</Button></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={tab === 'rules' ? rules : mappings} pagination={false} locale={{ emptyText: <Empty description="暂无规则草稿" /> }} scroll={{ x: 1000 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}</>}</Card><Modal title={tab === 'rules' ? '新建校验规则草稿' : '新建入账映射草稿'} open={open} onCancel={() => { setOpen(false); form.resetFields(); }} onOk={() => void form.submit()} destroyOnClose><Form form={form} layout="vertical" onFinish={save}>{tab === 'rules' ? <><Form.Item name="ruleCode" label="规则编码" rules={[{ required: true }]}><Input placeholder="例如 AMOUNT_POSITIVE" /></Form.Item><Form.Item name="name" label="规则名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="ruleType" label="规则类型" initialValue="FIELD" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="expression" label="表达式/说明" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item><Form.Item name="priority" label="优先级" initialValue={100}><InputNumber min={1} max={9999} /></Form.Item></> : <><Form.Item name="mappingCode" label="映射编码" rules={[{ required: true }]}><Input placeholder="例如 INCOME-SALES" /></Form.Item><Form.Item name="name" label="映射名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="direction" label="方向" initialValue="BOTH" rules={[{ required: true }]}><Input placeholder="INCOME / EXPENSE / BOTH" /></Form.Item><Form.Item name="counterpartyKeyword" label="对方关键字"><Input /></Form.Item><Form.Item name="debitSubject" label="借方科目" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="creditSubject" label="贷方科目" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="voucherTemplate" label="凭证模板" rules={[{ required: true }]}><Input /></Form.Item></>}</Form></Modal></>;
}

function ClosingPage() {
  const canManage = useAuthStore((state) => state.hasPermission('closing:manage'));
  const [page, setPage] = useState(1); const [period, setPeriod] = useState(dayjs().format('YYYY-MM')); const [working, setWorking] = useState(false);
  const loader = useCallback(() => closingApi.periods({ page, size: 20 }), [page]); const { data, loading, error, reload } = useRemote<PageResponse<ClosingPeriod>>(loader, [loader]);
  const run = (action: 'check' | 'close') => { setWorking(true); const request = action === 'check' ? closingApi.check(period) : closingApi.close(period); void request.then(() => { message.success(action === 'check' ? '账期检查完成' : '账期已结账'); void reload(); }).catch((reason) => message.error(reason instanceof Error ? reason.message : '操作失败')).finally(() => setWorking(false)); };
  const columns: TableColumnsType<ClosingPeriod> = [{ title: '账期', dataIndex: 'period', render: (v) => <span className="mono">{v}</span> }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '流水', dataIndex: 'totalCount' }, { title: '待复核', dataIndex: 'pendingCount' }, { title: '异常', dataIndex: 'exceptionCount' }, { title: '未制证', dataIndex: 'unpostedCount' }, { title: '请求编号', dataIndex: 'requestId', render: (v) => <span className="mono">{v || '--'}</span> }, { title: '更新时间', dataIndex: 'updatedAt', render: dateTime }];
  return <><div className="page-heading"><div><span className="section-kicker">对账结账 / 账期</span><h2>结账管理</h2><p className="muted">结账前检查待复核、异常和已复核未制证流水；结账后只允许通过新增更正记录调整。</p></div></div><Alert className="phase-one-notice" type="warning" showIcon message="结账是服务端事实确认" description="检查或结账均写入请求编号和审计记录，不会修改原始流水、账户余额或银行数据。" /><Card className="filter-card"><Space wrap><Input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="账期 YYYY-MM" /><Button loading={working} onClick={() => run('check')}>检查账期</Button>{canManage && <Button type="primary" loading={working} onClick={() => Modal.confirm({ title: `确认结账 ${period}`, content: '结账后原始流水和已有凭证关联不可覆盖。', onOk: () => run('close') })}>确认结账</Button>}</Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="尚无账期检查记录" /> }} scroll={{ x: 900 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}</>}</Card></>;
}

function AuditCenterPage() {
  const [page, setPage] = useState(1); const [filters, setFilters] = useState({ action: '', objectType: '', requestId: '' });
  const loader = useCallback(() => auditApi.events({ page, size: 20, action: filters.action || undefined, objectType: filters.objectType || undefined, requestId: filters.requestId || undefined }), [page, filters]); const { data, loading, error, reload } = useRemote<PageResponse<SystemAuditEvent>>(loader, [loader]);
  const update = (key: keyof typeof filters, value: string) => { setPage(1); setFilters((current) => ({ ...current, [key]: value })); };
  const columns: TableColumnsType<SystemAuditEvent> = [{ title: '时间', dataIndex: 'createdAt', render: dateTime }, { title: '动作', dataIndex: 'action' }, { title: '对象', render: (_, r) => <><span>{r.objectType}</span><span className="table-sub mono">{r.objectId || '--'}</span></> }, { title: '结果', dataIndex: 'result', render: (v) => <StatusTag status={v} /> }, { title: '请求编号', dataIndex: 'requestId', render: (v) => <span className="mono">{v}</span> }, { title: '摘要', dataIndex: 'detail', ellipsis: true }];
  return <><div className="page-heading"><div><span className="section-kicker">系统管理 / 合规</span><h2>审计中心</h2><p className="muted">仅查看当前企业范围内的脱敏操作事件；审计记录不支持业务用户修改或删除。</p></div></div><Card className="filter-card"><Space wrap><Input value={filters.action} allowClear placeholder="动作" onChange={(e) => update('action', e.target.value)} /><Input value={filters.objectType} allowClear placeholder="对象类型" onChange={(e) => update('objectType', e.target.value)} /><Input value={filters.requestId} allowClear placeholder="请求编号" onChange={(e) => update('requestId', e.target.value)} /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有审计事件" /> }} scroll={{ x: 900 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}</>}</Card></>;
}

function Forbidden() {
  const user = useAuthStore((state) => state.user);
  const fallback = user?.permissions.includes('dashboard:view') ? '/dashboard' : user?.permissions.includes('statement:view') ? '/statements/batches' : '/login';
  return <Result status="403" title="暂无访问权限" subTitle="当前角色没有访问该页面的权限。" extra={<Link to={fallback}><Button type="primary">返回可访问页面</Button></Link>} />;
}

function AppRoutes() {
  const hydrate = useAuthStore((state) => state.hydrate);
  useEffect(() => { void hydrate(); }, [hydrate]);
  return <Routes><Route path="/login" element={<Login />} /><Route path="/register" element={<Register />} /><Route element={<AuthGuard />}><Route element={<Shell />}><Route element={<PermissionGuard permissions={['dashboard:view']} />}><Route path="/dashboard" element={<Dashboard />} /></Route><Route element={<PermissionGuard permissions={['user:manage']} />}><Route path="/users" element={<PreservedFinancePage title="用户管理" description="用户管理入口已保留，银行接入权限不提升用户管理权限。" />} /></Route><Route element={<PermissionGuard permissions={['audit:view']} />}><Route path="/audit" element={<AuditCenterPage />} /></Route><Route element={<PermissionGuard permissions={['validation:view', 'validation:manage']} />}><Route path="/validation" element={<ValidationPage />} /></Route><Route element={<PermissionGuard permissions={['closing:view', 'closing:manage']} />}><Route path="/closing" element={<ClosingPage />} /></Route><Route element={<PermissionGuard permissions={['feishu:view', 'feishu:manage']} />}><Route path="/feishu" element={<FeishuCollaboration />} /></Route><Route element={<PermissionGuard permissions={['statement:import']} />}><Route path="/statements/import" element={<ImportStatements />} /></Route><Route element={<PermissionGuard permissions={['statement:view']} />}><Route path="/statements/batches" element={<BatchList />} /></Route><Route element={<PermissionGuard permissions={['statement:review']} />}><Route path="/statements/review" element={<ReviewStatements />} /></Route><Route element={<PermissionGuard permissions={['voucher:push']} />}><Route path="/statements/vouchers" element={<VoucherStatements />} /></Route><Route element={<PermissionGuard permissions={['reconciliation:view']} />}><Route path="/statements/reconciliation" element={<Reconciliation />} /></Route><Route element={<PermissionGuard permissions={['connection:view', 'connection:manage']} />}><Route path="/bank-access/connections" element={<ConnectionConfigurationPage section="applications" title="银行连接" description="管理未来银行直联的受控元数据；一期不建立真实银行连接。" />} /><Route path="/bank-access/agreements" element={<ConnectionConfigurationPage section="contracts" title="签约准备" description="展示签约准备度和模拟标记，不代表银行签约已生效。" />} /><Route path="/bank-access/preferences" element={<ConnectionConfigurationPage section="preferences" title="采集设置" description="展示服务端采集配置摘要；浏览器不读取或保存密钥、证书或令牌。" />} /></Route><Route element={<PermissionGuard permissions={['bank:view']} />}><Route path="/bank-access/accounts" element={<BankAccountPage />} /></Route><Route element={<PermissionGuard permissions={['operation:monitor']} />}><Route path="/bank-access/monitoring" element={<ConnectionMonitoring />} /></Route><Route element={<PermissionGuard permissions={['operation:monitor', 'bankdata:view']} />}><Route path="/bank-access/tasks" element={<OperationTasks />} /></Route><Route element={<PermissionGuard permissions={['operation:log:view']} />}><Route path="/bank-access/logs" element={<OperationLogs />} /></Route><Route element={<PermissionGuard permissions={['bankdata:balance:view']} />}><Route path="/bank-access/data/balances" element={<BankDataQueryPage resource="balances" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:statement:view']} />}><Route path="/bank-access/data/statements" element={<BankDataQueryPage resource="statements" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:receipt:view']} />}><Route path="/bank-access/data/receipts" element={<BankDataQueryPage resource="receipts" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:reconciliation:view']} />}><Route path="/bank-access/data/reconciliations" element={<BankDataQueryPage resource="reconciliations" />} /></Route><Route element={<PermissionGuard permissions={['bankdata:payroll:view']} />}><Route path="/bank-access/data/payroll" element={<BankDataQueryPage resource="payroll" />} /></Route><Route path="/connections/apps" element={<Navigate to="/bank-access/connections" replace />} /><Route path="/connections/agreements" element={<Navigate to="/bank-access/agreements" replace />} /><Route path="/connections/preferences" element={<Navigate to="/bank-access/preferences" replace />} /><Route path="/operations/connectivity" element={<Navigate to="/bank-access/monitoring" replace />} /><Route path="/operations/tasks" element={<Navigate to="/bank-access/tasks" replace />} /><Route path="/operations/logs" element={<Navigate to="/bank-access/logs" replace />} /><Route path="/bank-data/balances" element={<Navigate to="/bank-access/data/balances" replace />} /><Route path="/bank-data/statements" element={<Navigate to="/bank-access/data/statements" replace />} /><Route path="/bank-data/receipts" element={<Navigate to="/bank-access/data/receipts" replace />} /><Route path="/bank-data/reconciliations" element={<Navigate to="/bank-access/data/reconciliations" replace />} /><Route path="/bank-data/payroll" element={<Navigate to="/bank-access/data/payroll" replace />} /><Route path="/403" element={<Forbidden />} /></Route></Route><Route path="*" element={<Navigate to="/dashboard" replace />} /></Routes>;
}

export default function App() {
  return <BrowserRouter><AppRoutes /></BrowserRouter>;
}
