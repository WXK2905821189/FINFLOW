import { Button, Result } from 'antd';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../store/auth';

/** 独立文件承载 403 页：App 以 React.lazy 加载，避免 Result 大件进入登录首屏同步链（P2-3）。 */
export function Forbidden() {
  const user = useAuthStore((state) => state.user);
  const fallback = user?.permissions.includes('dashboard:view') ? '/dashboard' : user?.permissions.includes('statement:view') ? '/statements/batches' : '/login';
  return <Result status="403" title="暂无访问权限" subTitle="当前角色没有访问该页面的权限。" extra={<Link to={fallback}><Button type="primary">返回可访问页面</Button></Link>} />;
}
