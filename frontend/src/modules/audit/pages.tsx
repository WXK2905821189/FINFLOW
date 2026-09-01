import { useCallback, useState } from 'react';
import { Card, Empty, Input, Pagination, Space, Table, type TableColumnsType } from 'antd';
import { auditApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime } from '../shared/format';
import type { PageResponse, SystemAuditEvent } from '../../types';

export function AuditCenterPage() {
  const [page, setPage] = useState(1); const [filters, setFilters] = useState({ action: '', objectType: '', requestId: '' });
  const loader = useCallback(() => auditApi.events({ page, size: 20, action: filters.action || undefined, objectType: filters.objectType || undefined, requestId: filters.requestId || undefined }), [page, filters]); const { data, loading, error, reload } = useRemote<PageResponse<SystemAuditEvent>>(loader, [loader]);
  const update = (key: keyof typeof filters, value: string) => { setPage(1); setFilters((current) => ({ ...current, [key]: value })); };
  const columns: TableColumnsType<SystemAuditEvent> = [{ title: '时间', dataIndex: 'createdAt', render: dateTime }, { title: '动作', dataIndex: 'action' }, { title: '对象', render: (_, r) => <><span>{r.objectType}</span><span className="table-sub mono">{r.objectId || '--'}</span></> }, { title: '结果', dataIndex: 'result', render: (v) => <StatusTag status={v} /> }, { title: '请求编号', dataIndex: 'requestId', render: (v) => <span className="mono">{v}</span> }, { title: '摘要', dataIndex: 'detail', ellipsis: true }];
  return <><div className="page-heading"><div><span className="section-kicker">系统管理 / 合规</span><h2>审计中心</h2><p className="muted">仅查看当前企业范围内的脱敏操作事件；审计记录不支持业务用户修改或删除。</p></div></div><Card className="filter-card"><Space wrap><Input value={filters.action} allowClear placeholder="动作" onChange={(e) => update('action', e.target.value)} /><Input value={filters.objectType} allowClear placeholder="对象类型" onChange={(e) => update('objectType', e.target.value)} /><Input value={filters.requestId} allowClear placeholder="请求编号" onChange={(e) => update('requestId', e.target.value)} /></Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="当前筛选没有审计事件" /> }} scroll={{ x: 900 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}</>}</Card></>;
}
