import { useCallback, useEffect, useState, type DependencyList } from 'react';
import { Alert, Button, Card, Skeleton, Tag, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { ApiRequestError } from '../../services/http';
import { dateTime, directStatusText, statusColor } from './format';

export function StatusTag({ status }: { status?: string }) {
  return <Tag color={statusColor(status)}>{status || '--'}</Tag>;
}

/**
 * Per-account direct-connect badge. The status comes from the account row itself (resolved
 * server-side per account), so one bank being connected never turns other banks' rows green.
 */
export function DirectStatusTag({ status, lastRealSyncAt }: { status?: string; lastRealSyncAt?: string }) {
  const hint = lastRealSyncAt ? `最近真实同步：${dateTime(lastRealSyncAt)}` : '尚无真实同步记录';
  return <Tooltip title={hint}><Tag color={statusColor(status)}>{directStatusText(status)}</Tag></Tooltip>;
}

export function ResourceFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const isForbidden = error instanceof ApiRequestError && error.status === 403;
  const description = error instanceof Error ? error.message : '请求未能完成，请稍后重试';
  return <Alert className="resource-alert" type="error" showIcon message={isForbidden ? '暂无访问权限' : '数据暂不可用'} description={description} action={<Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>} />;
}

export function useRemote<T>(loader: () => Promise<T>, dependencies: DependencyList) {
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
  // 通用 hook：依赖数组由调用方传入，静态分析无法校验属预期行为
  // eslint-disable-next-line react-hooks/use-memo, react-hooks/exhaustive-deps
  }, dependencies);

  useEffect(() => { void reload(); }, [reload]);
  return { data, loading, error, reload };
}
export function PageLoading() {
  return <div className="page-loading"><Skeleton active paragraph={{ rows: 5 }} /></div>;
}
export function PhaseOneNotice({ status, message }: { status?: string; message?: string }) {
  // 服务端已连接真实直联（如 REAL）时无需警示；未连接/未配置/禁用等一律红色明确提示，不再展示模拟数据。
  if (status && !/(DISABLED|UNAVAILABLE|NOT_ENABLED|NOT_CONFIGURED|SIMULATED|MOCK|未启用|不可用)/i.test(status)) {
    return null;
  }
  return <Alert className="phase-one-notice" type="error" showIcon message="真实银行直联未连接" description={<span>服务端未启用真实银行适配器，本区域不提供模拟数据。{status && <> 当前服务端状态：<StatusTag status={status} />。</>}{message && <> {message}</>}</span>} />;
}

export function PreservedFinancePage({ title, description }: { title: string; description: string }) {
  return <><div className="page-heading"><div><span className="section-kicker">系统管理</span><h2>{title}</h2><p className="muted">{description}</p></div></div><Card><Alert type="info" showIcon message="入口与路由已保留" description="本次银行接入功能不修改历史支付、调拨、交易或用户管理的业务状态机。未接入的页面数据不会使用浏览器演示数据代替。" /></Card></>;
}
