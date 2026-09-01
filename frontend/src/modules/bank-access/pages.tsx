import { useCallback } from 'react';
import { Alert, Card, Empty, Skeleton, Table, Tag, type TableColumnsType } from 'antd';
import { bankApi, operationsApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag, PhaseOneNotice } from '../shared/components';
import { dateTime } from '../shared/format';
import type { BankAccount, ConnectionConfiguration, ConnectionOverview } from '../../types';

export function BankAccountPage() {
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

export function ConnectionConfigurationPage({ section, title, description }: { section: 'applications' | 'contracts' | 'preferences'; title: string; description: string }) {
  const loader = useCallback(() => operationsApi.configuration(section), [section]);
  const { data, loading, error, reload } = useRemote<ConnectionConfiguration>(loader, [loader]);
  const columns: TableColumnsType<ConnectionConfiguration['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '显示名称', dataIndex: 'displayName' }, { title: '提供方类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近检查', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 连接配置</span><h2>{title}</h2><p className="muted">{description}</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="服务端连接元数据">{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="尚未配置银行连接" /> }} scroll={{ x: 780 }} />{data?.supportedProviderTypes?.length ? <div className="capability-meta">支持的模拟提供方：{data.supportedProviderTypes.join('、')}</div> : null}</>}</Card>}</>;
}
export function ConnectionMonitoring() {
  const loader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data, loading, error, reload } = useRemote<ConnectionOverview>(loader, [loader]);
  const columns: TableColumnsType<ConnectionOverview['connections'][number]> = [{ title: '连接标识', dataIndex: 'connectionCode', render: (value) => <span className="mono">{value}</span> }, { title: '名称', dataIndex: 'displayName' }, { title: '环境 / 类型', dataIndex: 'providerType' }, { title: '服务端状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '最近心跳', dataIndex: 'lastCheckedAt', render: (value) => dateTime(value) }];
  return <><div className="page-heading"><div><span className="section-kicker">银行接入 / 采集运营</span><h2>直联状态监控</h2><p className="muted">仅呈现服务端的受控状态摘要，不将模拟或未启用状态描述为银行已连接。</p></div></div>{error ? <ResourceFailure error={error} onRetry={reload} /> : <Card title="连接状态"><>{loading ? <Skeleton active paragraph={{ rows: 6 }} /> : <><PhaseOneNotice status={data?.status} message={data?.message} /><Table rowKey="connectionCode" columns={columns} dataSource={data?.connections || []} pagination={false} locale={{ emptyText: <Empty description="没有可监控的直联对象" /> }} scroll={{ x: 760 }} /></>}</></Card>}</>;
}
