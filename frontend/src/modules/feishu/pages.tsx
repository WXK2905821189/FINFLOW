import { useCallback, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Empty, Input, Row, Skeleton, Space, Table, message } from 'antd';
import { NotificationOutlined } from '@ant-design/icons';
import { feishuApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime } from '../shared/format';
import type { FeishuOverview } from '../../types';

export function FeishuCollaboration() {
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

export function FeishuDeliveryTable() {
  const loader = useCallback(() => feishuApi.deliveries({ page: 1, size: 20 }), []);
  const { data, loading, error, reload } = useRemote(loader, [loader]);
  return error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="eventId" loading={loading} pagination={false} dataSource={data?.records || []} locale={{ emptyText: <Empty description="尚无飞书模拟发送记录" /> }} columns={[{ title: '事件', dataIndex: 'eventType' }, { title: '严重级别', dataIndex: 'severity' }, { title: '状态', dataIndex: 'status', render: (value) => <StatusTag status={value} /> }, { title: '请求编号', dataIndex: 'requestId', render: (value) => <span className="mono">{value}</span> }, { title: '模拟消息号', dataIndex: 'providerMessageId', render: (value) => <span className="mono">{value || '--'}</span> }, { title: '时间', dataIndex: 'createdAt', render: (value) => dateTime(value) }]} scroll={{ x: 760 }} />;
}
