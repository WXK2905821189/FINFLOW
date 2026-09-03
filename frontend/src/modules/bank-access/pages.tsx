import { useCallback } from 'react';
import { Alert, Card, Empty, Skeleton, Table, Tag, type TableColumnsType } from 'antd';
import { bankApi, operationsApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag, PhaseOneNotice } from '../shared/components';
import { dateTime } from '../shared/format';
import type { BankAccount, ConnectionConfiguration, ConnectionOverview } from '../../types';

export function BankAccountPage() {
  const loader = useCallback(() => bankApi.accounts(), []);
  const { data, loading, error, reload } = useRemote<BankAccount[]>(loader, [loader]);
  const overviewLoader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data: overview } = useRemote<ConnectionOverview>(overviewLoader, [overviewLoader]);
  const connected = overview?.enabled === true && overview?.status === 'REAL';
  const columns: TableColumnsType<BankAccount> = [
    { title: '银行', dataIndex: 'bankCode', render: (value) => <span className="mono">{value}</span> },
    { title: '账户名称', dataIndex: 'accountName' },
    { title: '账号', dataIndex: 'maskedAccountNumber', render: (value) => <span className="mono">{value}</span> },
    { title: '币种', dataIndex: 'currency' },
    { title: '账户状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
    { title: '直联状态', render: () => connected ? <Tag color="green">真实银行直联已连接</Tag> : <Tag color="red">真实银行直联未连接</Tag> },
  ];
  const banner = overview ? (connected
    ? <Alert className="phase-one-notice" type="success" showIcon message="已连接真实银行直联（招行 CMB）" description={overview.message || '余额/流水查询走真实银行接口。'} />
    : <Alert className="phase-one-notice" type="error" showIcon message="真实银行直联未连接" description={overview.message || '服务端未启用真实银行适配器，当前无法获取银行数据。'} />) : null;
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 账户</span><h2>银行账户</h2><p className="muted">展示当前企业已授权的银行账户；账号仅显示脱敏结果，余额和流水由真实银行直联采集任务更新。</p></div></div>{banner}<Card title="企业授权账户">{error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="id" loading={loading} columns={columns} dataSource={data || []} pagination={false} locale={{ emptyText: <Empty description="当前企业暂无授权银行账户" /> }} scroll={{ x: 780 }} />}</Card></>;
}

export function ConnectionConfigurationPage({ section, title, description }: { section: 'applications' | 'contracts' | 'preferences'; title: string; description: string }) {
  const loader = useCallback(() => operationsApi.configuration(section), [section]);
  const { data, loading, error, reload } = useRemote<ConnectionConfiguration>(loader, [loader]);
  const columns: TableColumnsType<ConnectionConfiguration['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '显示名称', dataIndex: 'displayName' }, { title: '提供方类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近检查', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 连接配置</span><h2>{title}</h2><p className="muted">{description}</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="服务端连接元数据">{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="尚未配置银行连接" /> }} scroll={{ x: 780 }} />{data?.supportedProviderTypes?.length ? <div className="capability-meta">可直联提供方：{data.supportedProviderTypes.join('、')}</div> : null}</>}</Card>}</>;
}
export function ConnectionMonitoring() {
  const loader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data, loading, error, reload } = useRemote<ConnectionOverview>(loader, [loader]);
  const columns: TableColumnsType<ConnectionOverview['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '名称', dataIndex: 'displayName' }, { title: '环境 / 类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近心跳', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>直联状态监控</h2><p className="muted">呈现服务端直联状态：已连接或未连接以服务端返回为准。</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="连接状态"><>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="没有可监控的直联对象" /> }} scroll={{ x: 760 }} /></>}</></Card>}</>;
}
