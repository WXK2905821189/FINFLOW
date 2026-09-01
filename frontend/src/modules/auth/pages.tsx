import { useState } from 'react';
import { Alert, Button, Form, Input, Result } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import { ApiRequestError } from '../../services/http';
import { useAuthStore } from '../../store/auth';
import { PageLoading } from '../shared/components';

export function AuthGuard() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();
  if (status === 'restoring') return <PageLoading />;
  return status === 'authenticated' ? <Outlet /> : <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
}

export function PermissionGuard({ permissions }: { permissions: string[] }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  return permissions.some(hasPermission) ? <Outlet /> : <Navigate to="/403" replace />;
}
export function Login() {
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

export function Register() {
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
export function Forbidden() {
  const user = useAuthStore((state) => state.user);
  const fallback = user?.permissions.includes('dashboard:view') ? '/dashboard' : user?.permissions.includes('statement:view') ? '/statements/batches' : '/login';
  return <Result status="403" title="暂无访问权限" subTitle="当前角色没有访问该页面的权限。" extra={<Link to={fallback}><Button type="primary">返回可访问页面</Button></Link>} />;
}
