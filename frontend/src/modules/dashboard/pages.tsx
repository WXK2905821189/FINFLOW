import { useCallback } from 'react';
import { Alert, Card, Descriptions, Empty } from 'antd';
import { statementApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { money } from '../shared/format';
import type { StatementDashboard } from '../../types';

export function Dashboard() {
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
