import { useCallback } from 'react';
import { Alert, Card, Empty, Table, type TableColumnsType } from 'antd';
import { bankApi, operationsApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag, DirectStatusTag } from '../shared/components';
import type { BankAccount, ConnectionOverview } from '../../types';

export function BankAccountPage() {
  const loader = useCallback(() => bankApi.accounts(), []);
  const { data, loading, error, reload } = useRemote<BankAccount[]>(loader, [loader]);
  const overviewLoader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data: overview } = useRemote<ConnectionOverview>(overviewLoader, [overviewLoader]);
  // Provider-level flag: only used for the page banner. Each row's own status comes from the
  // account itself (directStatus), so a connected bank never turns other banks' rows green.
  const connected = overview?.status === 'REAL';
  const columns: TableColumnsType<BankAccount> = [
    { title: '银行', dataIndex: 'bankCode', render: (value) => <span className="mono">{value}</span> },
    { title: '账户名称', dataIndex: 'accountName' },
    { title: '账号', dataIndex: 'maskedAccountNumber', render: (value) => <span className="mono">{value}</span> },
    { title: '币种', dataIndex: 'currency' },
    { title: '账户状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
    { title: '直联状态', render: (_, row) => <DirectStatusTag status={row.directStatus} lastRealSyncAt={row.lastRealSyncAt} /> },
  ];
  const banner = overview ? (connected
    ? <Alert className="phase-one-notice" type="success" showIcon message="已连接真实银行直联" description={overview.message || '余额/流水查询走真实银行接口。'} />
    : <Alert className="phase-one-notice" type="error" showIcon message="真实银行直联未连接" description={overview.message || '服务端未装配真实银行适配器，当前无法获取银行数据。'} />) : null;
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 账户</span><h2>银行账户</h2><p className="muted">展示当前企业已授权的银行账户；账号仅显示脱敏结果，余额和流水由真实银行直联采集任务更新。</p></div></div>{banner}<Card title="企业授权账户">{error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="id" loading={loading} columns={columns} dataSource={data || []} pagination={false} locale={{ emptyText: <Empty description="当前企业暂无授权银行账户" /> }} scroll={{ x: 780 }} />}</Card></>;
}
