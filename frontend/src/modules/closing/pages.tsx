import { useCallback, useState } from 'react';
import dayjs from 'dayjs';
import { Alert, Button, Card, Empty, Input, Modal, Pagination, Space, Table, message, type TableColumnsType } from 'antd';
import { closingApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime } from '../shared/format';
import type { PageResponse, ClosingPeriod } from '../../types';

export function ClosingPage() {
  const canManage = useAuthStore((state) => state.hasPermission('closing:manage'));
  const isAdmin = useAuthStore((state) => Boolean(state.user?.roles.includes('ADMIN')));
  const [page, setPage] = useState(1); const [period, setPeriod] = useState(dayjs().format('YYYY-MM')); const [working, setWorking] = useState(false);
  const loader = useCallback(() => closingApi.periods({ page, size: 20 }), [page]); const { data, loading, error, reload } = useRemote<PageResponse<ClosingPeriod>>(loader, [loader]);
  const run = (action: 'check' | 'close' | 'unlock', targetPeriod?: string) => {
    setWorking(true);
    const p = targetPeriod || period;
    const request = action === 'check' ? closingApi.check(p) : action === 'close' ? closingApi.close(p) : closingApi.unlock(p);
    void request.then(() => { message.success(action === 'check' ? '账期检查完成' : action === 'close' ? '账期已结账' : '账期已解锁'); void reload(); }).catch((reason) => message.error(reason instanceof Error ? reason.message : '操作失败')).finally(() => setWorking(false));
  };
  const confirmUnlock = (targetPeriod: string) => Modal.confirm({ title: `确认解锁账期 ${targetPeriod}`, content: '解锁后账期回到 READY，期间流水可重新导入、制证与推送；操作将写入审计记录。', okText: '解锁', okButtonProps: { danger: true }, onOk: () => run('unlock', targetPeriod) });
  const columns: TableColumnsType<ClosingPeriod> = [{ title: '账期', dataIndex: 'period', render: (v) => <span className="mono">{v}</span> }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '流水', dataIndex: 'totalCount' }, { title: '待复核', dataIndex: 'pendingCount' }, { title: '异常', dataIndex: 'exceptionCount' }, { title: '未制证', dataIndex: 'unpostedCount' }, { title: '请求编号', dataIndex: 'requestId', render: (v) => <span className="mono">{v || '--'}</span> }, { title: '更新时间', dataIndex: 'updatedAt', render: dateTime }, ...(isAdmin ? [{ title: '操作', render: (_: unknown, row: ClosingPeriod) => (row.status === 'CLOSED' ? <Button type="link" size="small" danger loading={working} onClick={() => confirmUnlock(row.period)}>解锁</Button> : <span className="muted">--</span>) }] : [])];
  return <><div className="page-heading"><div><span className="section-kicker">对账结账 / 账期</span><h2>结账管理</h2><p className="muted">结账前检查待复核、异常和已复核未制证流水；结账后导入、AI 制证与推送被拦截，超管可解锁后补录。</p></div></div><Alert className="phase-one-notice" type="warning" showIcon message="结账是服务端事实确认" description="检查或结账均写入请求编号和审计记录，不会修改原始流水、账户余额或银行数据。" /><Card className="filter-card"><Space wrap><Input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="账期 YYYY-MM" /><Button loading={working} onClick={() => run('check')}>检查账期</Button>{canManage && <Button type="primary" loading={working} onClick={() => Modal.confirm({ title: `确认结账 ${period}`, content: '结账后原始流水和已有凭证关联不可覆盖。', onOk: () => run('close') })}>确认结账</Button>}{isAdmin && <Button danger loading={working} onClick={() => confirmUnlock(period)}>解锁账期</Button>}</Space></Card><Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <><Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []} pagination={false} locale={{ emptyText: <Empty description="尚无账期检查记录" /> }} scroll={{ x: 900 }} />{data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}</>}</Card></>;
}
