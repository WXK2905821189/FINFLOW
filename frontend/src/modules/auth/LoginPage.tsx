import { useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiRequestError } from '../../services/http';
import { useAuthStore } from '../../store/auth';

/** 独立文件承载 Login：App 以 React.lazy 加载，避免登录首屏下载整套控制台骨架（P2-3）。 */
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
  return <div className="auth-page"><div className="auth-panel"><div className="brand brand-light"><div className="brand-mark">F</div><span>FINFLOW</span></div><div className="auth-copy"><div className="eyebrow">企业级资金管理平台</div><h1>让每一笔资金，<br /><em>清晰且可追溯。</em></h1><p>从流水导入到人工复核与制证追溯，在一个受控工作台完成协作。</p></div><div className="auth-note"><SafetyCertificateOutlined /> 数据访问受角色权限保护</div></div><div className="auth-form-wrap"><div className="auth-form"><span className="section-kicker">欢迎回来</span><h2>登录财务工作台</h2><p className="muted">使用您的企业账号继续</p>{location.search.includes('reason=expired') && <Alert className="login-alert" type="warning" showIcon message="登录已失效，请重新登录" />}{error && <Alert className="login-alert" type="error" showIcon message={error} />}<Form layout="vertical" onFinish={submit}><Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}><Input size="large" autoFocus placeholder="用户名或邮箱" /></Form.Item><Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}><Input.Password size="large" placeholder="请输入密码" /></Form.Item><div className="form-meta"><span>登录即表示同意平台安全政策</span><span>账号由管理员开通</span></div><Button loading={loading} htmlType="submit" type="primary" size="large" block>进入工作台</Button></Form></div></div></div>;
}
