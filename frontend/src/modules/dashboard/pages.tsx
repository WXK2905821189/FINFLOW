import { useCallback } from 'react';
import { Button, Card, Descriptions, Empty, Skeleton, Table, type TableColumnsType } from 'antd';
import { Link } from 'react-router-dom';
import { SendOutlined } from '@ant-design/icons';
import { bankPipelineApi, statementApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { money } from '../shared/format';
import type { BankTaskReconciliationRow } from '../bank-access/types';
import type { StatementDashboard } from '../../types';

/**
 * V34 ⑥ 工作台改版：待办先行（待复核/异常/待完成对账）+ 处理概览 + 任务级对账核对并入本页
 * （原 /reconciliation/dashboard 与 /bank-access/data/reconciliation 两页下线，V34 ⑥）；
 * 凭证中心一键入口衔接制证链路。所有数字均来自服务端汇总。
 */
export function Dashboard() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canReadReconciliation = hasPermission('reconciliation:view');
  const canViewVoucher = hasPermission('voucher:push');
  const canViewTaskRecon = hasPermission('bankdata:view') || hasPermission('bankdata:reconciliation:view');

  const loader = useCallback(() => canReadReconciliation ? statementApi.dashboard() : Promise.resolve<StatementDashboard | undefined>(undefined), [canReadReconciliation]);
  const { data, loading, error, reload } = useRemote<StatementDashboard | undefined>(loader, [loader]);

  const reconLoader = useCallback(() => canViewTaskRecon ? bankPipelineApi.taskReconciliation({ page: 1, size: 5 }) : Promise.resolve(undefined), [canViewTaskRecon]);
  const { data: recon, loading: reconLoading, error: reconError, reload: reloadRecon } = useRemote(reconLoader, [reconLoader]);

  const todoItems = data ? [
    { label: '待复核流水', value: data.pendingReviewCount, tone: data.pendingReviewCount ? 'warning' : 'normal' },
    { label: '入账失败/异常', value: data.invalidCount, tone: data.invalidCount ? 'danger' : 'normal' },
    { label: '待完成对账', value: Math.max(data.totalCount - data.pushedCount, 0), tone: data.totalCount > data.pushedCount ? 'warning' : 'normal' },
  ] : [];

  const reconColumns: TableColumnsType<BankTaskReconciliationRow> = [
    { title: '任务号', dataIndex: 'taskNo', width: 190, render: (value: string | undefined, row) => <span className="mono">{value || `任务 ${row.taskId}`}</span> },
    { title: '状态', dataIndex: 'status', width: 120, render: (value: string | undefined) => value ? <StatusTag status={value} /> : '--' },
    { title: '银行借方', align: 'right', width: 120, render: (_, row) => money(row.bankDebitAmount ?? undefined) },
    { title: '银行贷方', align: 'right', width: 120, render: (_, row) => money(row.bankCreditAmount ?? undefined) },
    { title: '平台支出', align: 'right', width: 120, render: (_, row) => money(row.platformExpenseAmount ?? undefined) },
    { title: '平台收入', align: 'right', width: 120, render: (_, row) => money(row.platformIncomeAmount ?? undefined) },
  ];

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">财务总览</span>
        <h2>工作台</h2>
        <p className="muted">先处理阻断项，再查看采集、复核、入账和对账进度；任务级对账核对已并入本页。所有数字均来自服务端汇总。</p>
      </div>
      {canViewVoucher && <Link to="/statements/vouchers"><Button type="primary" icon={<SendOutlined />}>前往凭证中心</Button></Link>}
    </div>
    {error ? <ResourceFailure error={error} onRetry={reload} /> : !canReadReconciliation ? <Card><Empty description="当前角色没有财务汇总查看权限" /></Card> : <>
      <div className="workbench-todos">
        {todoItems.map((item) => <div className={`todo-item todo-${item.tone}`} key={item.label}>
          <span>{item.label}</span><strong>{loading ? '--' : item.value}</strong><small>笔</small>
        </div>)}
        {!loading && todoItems.length === 0 && <Empty description="暂无可展示的待办汇总" />}
      </div>
      <Card className="workbench-summary" title="处理概览">
        <Descriptions column={{ xs: 1, sm: 2, xl: 4 }} size="small">
          <Descriptions.Item label="采集流水">{loading ? '--' : `${data?.totalCount ?? '--'} 笔`}</Descriptions.Item>
          <Descriptions.Item label="已复核">{loading ? '--' : `${data?.approvedCount ?? '--'} 笔`}</Descriptions.Item>
          <Descriptions.Item label="已制证">{loading ? '--' : `${data?.pushedCount ?? '--'} 笔`}</Descriptions.Item>
          <Descriptions.Item label="已制证金额">{loading ? '--' : money(data?.pushedAmount)}</Descriptions.Item>
        </Descriptions>
      </Card>
      {canViewTaskRecon && <Card
        className="workbench-reconciliation"
        title="对账核对（银行 Z1 口径 vs 平台入库）"
        extra={<Button size="small" type="link" onClick={() => void reloadRecon()}>刷新</Button>}
      >
        {reconError ? <ResourceFailure error={reconError} onRetry={reloadRecon} />
          : reconLoading ? <Skeleton active paragraph={{ rows: 3 }} />
            : (recon?.records || []).length ? <Table
              rowKey="taskId"
              size="small"
              columns={reconColumns}
              dataSource={recon?.records || []}
              pagination={false}
            /> : <Empty description="暂无对账任务记录" />}
      </Card>}
    </>}
  </>;
}
