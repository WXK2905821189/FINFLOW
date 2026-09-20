import { useCallback, useState } from 'react';
import {
  Button, Card, Empty, Input, Modal, Pagination, Segmented, Space, Table, Tag, Tooltip, message,
  type TableColumnsType,
} from 'antd';
import { Link } from 'react-router-dom';
import { ReloadOutlined } from '@ant-design/icons';
import { statementApi, voucherGroupApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime, money } from '../shared/format';
import { AuditDrawer } from '../statements/pages';
import { VOUCHER_GROUP_FILTERS, voucherStatusTag } from './voucherTexts';
import type { VoucherGroupFilter, VoucherGroupRow } from './types';

/**
 * V34 ⑦ 凭证中心（凭证管线一等视图）：
 *  - 列表行 = 凭证记录（一期推送链路 1 笔流水 → 1 张凭证，「来源流水」列写明 N 笔 → 1 张单据）；
 *  - 状态四签（待复核/待推送/已推送/推送失败）同时覆盖 AI 制证与规则引擎两条推送链路；
 *  - 行内操作：通过/驳回（待复核）、推送金蝶（待推送）、查看凭证（独立单据页，可打印）、追溯；
 *  - 取代原「凭证草稿与制证」页（/statements/vouchers 路由保留，组件替换）。
 */

const sourceFlowTag = (count: number) => (
  <Tag color="geekblue">{count} 笔 → 1 张单据</Tag>
);

export function VoucherCenterPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canReview = hasPermission('statement:review');
  const canPush = hasPermission('voucher:push');
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState<VoucherGroupFilter>('ALL');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [trace, setTrace] = useState<VoucherGroupRow>();
  const [rejectRow, setRejectRow] = useState<VoucherGroupRow>();
  const [rejectComment, setRejectComment] = useState('');
  const [busyRowId, setBusyRowId] = useState<number>();
  // W8：批量重推——勾选可推送行（已复核且未推送成功，含推送失败行）一次提交。
  const [selectedIds, setSelectedIds] = useState<number[]>([]);

  const loader = useCallback(() => voucherGroupApi.list({ page, size: 20, status: filter, keyword: keyword || undefined }),
    [page, filter, keyword]);
  const { data, loading, error, reload } = useRemote(loader, [loader]);

  const withBusy = async (row: VoucherGroupRow, action: () => Promise<unknown>, done: string) => {
    if (busyRowId != null) return;
    setBusyRowId(row.statementId);
    try {
      await action();
      message.success(done);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '操作未能完成');
    } finally {
      setBusyRowId(undefined);
    }
  };

  const approve = (row: VoucherGroupRow) => {
    Modal.confirm({
      title: `确认通过该凭证草稿（${row.statementNo}）`,
      content: '通过后进入待推送队列，可推送金蝶（出纳收付款单，提交不审核）。',
      okText: '确认通过',
      cancelText: '取消',
      onOk: () => withBusy(row, () => statementApi.batchReview({ ids: [row.statementId], action: 'APPROVE' }), '草稿已通过'),
    });
  };

  const push = (row: VoucherGroupRow) => {
    const failed = row.pushStatus === 'FAILED' || row.pushStatus === 'GL_FAILED';
    Modal.confirm({
      title: `确认${failed ? '重试推送' : '推送'}金蝶（${row.statementNo}）`,
      content: failed
        ? '该凭证此前推送失败，重试将重新提交金蝶（幂等，已推送的行不会重复）。失败原因见状态列提示。'
        : '只推送「已复核通过」的行；推送幂等，此前已推送的行不会重复推送。',
      okText: failed ? '确认重试' : '确认推送',
      cancelText: '取消',
      onOk: () => withBusy(row, () => statementApi.batchPush({ ids: [row.statementId] }), failed ? '已重新提交推送' : '已提交推送'),
    });
  };

  /** W8：重新打开已驳回的流水（REJECTED→PENDING），之后可重新制证。 */
  const reopen = (row: VoucherGroupRow) => {
    Modal.confirm({
      title: `重新打开已驳回流水（${row.statementNo}）`,
      content: '驳回状态将复位为「待复核」，可重新生成凭证草稿（一键 AI 制证 / 人工存草稿）。上次驳回意见保留可追溯；该操作写入审计。',
      okText: '确认重新打开',
      cancelText: '取消',
      onOk: () => withBusy(row, () => statementApi.reopen(row.statementId), '流水已重新打开，可重新制证'),
    });
  };

  const columns: TableColumnsType<VoucherGroupRow> = [
    {
      title: '来源流水', dataIndex: 'statementNo', width: 210,
      render: (value: string, row) => <Space size={4} wrap><span className="mono">{value}</span>{sourceFlowTag(row.statementCount)}</Space>,
    },
    { title: '业务日期', dataIndex: 'businessDate', width: 110, render: (value: string) => dateTime(value) },
    { title: '公司主体', dataIndex: 'companyName', ellipsis: true, render: (value: string | null) => value || '--' },
    { title: '银行账户', dataIndex: 'bankAccount', ellipsis: true, render: (value: string | null) => value ? <span className="mono">{value}</span> : '--' },
    { title: '方向', dataIndex: 'direction', width: 70, render: (value: string) => value === 'INCOME' ? <Tag color="green">收</Tag> : value === 'EXPENSE' ? <Tag color="orange">付</Tag> : value || '--' },
    { title: '金额', dataIndex: 'amount', align: 'right', width: 120, render: (value) => money(value) },
    { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value: string | null) => value || '--' },
    { title: '金蝶凭证号', dataIndex: 'voucherNo', width: 130, render: (value: string | null) => value ? <span className="mono">{value}</span> : '--' },
    {
      title: '状态', width: 130,
      render: (_, row) => {
        const tag = voucherStatusTag(row);
        return <>{<Tag color={tag.color}>{tag.text}</Tag>}{row.pushMessage && <Tooltip title={row.pushMessage}><span className="table-sub">{row.pushMessage}</span></Tooltip>}</>;
      },
    },
    {
      title: '操作', fixed: 'right', width: 250,
      render: (_, row) => <Space size={0} wrap>
        <Link to={`/statements/voucher-doc/${row.statementId}`}>查看凭证</Link>
        {canReview && row.reviewStatus === 'PENDING' && (
          <Button type="link" size="small" disabled={busyRowId != null} onClick={() => approve(row)}>通过</Button>
        )}
        {canReview && row.reviewStatus === 'PENDING' && (
          <Button type="link" size="small" disabled={busyRowId != null} onClick={() => { setRejectComment(''); setRejectRow(row); }}>驳回</Button>
        )}
        {canPush && row.reviewStatus === 'APPROVED' && row.pushStatus !== 'PUSHED' && row.pushStatus !== 'GL_PUSHED' && (
          <Button type="link" size="small" disabled={busyRowId != null}
            onClick={() => push(row)}>{row.pushStatus === 'FAILED' || row.pushStatus === 'GL_FAILED' ? '重试推送' : '推送'}</Button>
        )}
        {canPush && row.reviewStatus === 'REJECTED' && (
          <Button type="link" size="small" disabled={busyRowId != null} onClick={() => reopen(row)}>重新打开</Button>
        )}
        <Button type="link" size="small" onClick={() => setTrace(row)}>追溯</Button>
      </Space>,
    },
  ];

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">凭证与入账 / 凭证中心</span>
        <h2>凭证中心</h2>
        <p className="muted">
          以「凭证」为一等对象：一组流水生成一张金蝶单据（多行分录），AI 预填科目与逐行置信度，人工可改；
          推送与审核状态在此跟踪。金蝶侧完成最终审核。
        </p>
      </div>
      <Space wrap>
        <Button icon={<ReloadOutlined />} onClick={() => { setSelectedIds([]); void reload(); }}>刷新</Button>
        {canPush && selectedIds.length > 0 && (
          <Button type="primary" onClick={() => {
            const ids = [...selectedIds];
            Modal.confirm({
              title: `批量推送 ${ids.length} 行到金蝶`,
              content: '推送幂等：已推送的行自动跳过，失败的行保留原因可再次重试。',
              okText: '确认批量推送',
              cancelText: '取消',
              onOk: async () => {
                try {
                  const result = await statementApi.batchPush({ ids });
                  message.success(`批量推送完成：成功 ${result.successCount} / 跳过 ${result.skippedCount} / 失败 ${result.failedCount}`);
                  setSelectedIds([]);
                  await reload();
                } catch (reason) {
                  message.error(reason instanceof Error ? reason.message : '批量推送未能完成');
                }
              },
            });
          }}>批量推送（{selectedIds.length}）</Button>
        )}
      </Space>
    </div>
    <Space wrap style={{ marginBottom: 12 }} size={12}>
      <Segmented
        value={filter}
        onChange={(value) => { setPage(1); setFilter(value as VoucherGroupFilter); }}
        options={VOUCHER_GROUP_FILTERS.map(({ key, label }) => ({ label, value: key }))}
      />
      <Input.Search
        allowClear
        placeholder="凭证号 / 流水号 / 摘要 / 对手方"
        style={{ width: 280 }}
        value={keywordInput}
        onChange={(event) => setKeywordInput(event.target.value)}
        onSearch={(value) => { setPage(1); setKeyword(value.trim()); }}
      />
    </Space>
    <Card>
      {error ? <ResourceFailure error={error} onRetry={reload} /> : <>
        <Table
          rowKey="statementId"
          loading={loading}
          columns={columns}
          dataSource={data?.records || []}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无对应状态的凭证记录" /> }}
          scroll={{ x: 1480 }}
          rowSelection={canPush ? {
            selectedRowKeys: selectedIds,
            onChange: (keys) => setSelectedIds(keys.map(Number)),
            getCheckboxProps: (row: VoucherGroupRow) => ({
              // 仅「已复核且未推送成功」的行可批量推送（待推送 + 推送失败）。
              disabled: !(row.reviewStatus === 'APPROVED' && row.pushStatus !== 'PUSHED' && row.pushStatus !== 'GL_PUSHED'),
            }),
          } : undefined}
        />
        {data && data.total > data.size && (
          <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />
        )}
      </>}
    </Card>
    <Modal
      title={`驳回凭证草稿（${rejectRow?.statementNo ?? ''}）`}
      open={Boolean(rejectRow)}
      onCancel={() => setRejectRow(undefined)}
      onOk={() => {
        const row = rejectRow;
        setRejectRow(undefined);
        if (!row) return;
        void withBusy(row,
          () => statementApi.batchReview({ ids: [row.statementId], action: 'REJECT', comment: rejectComment.trim() || '人工驳回' }),
          '草稿已驳回');
      }}
      okText="确认驳回"
      cancelText="取消"
    >
      <Input.TextArea
        value={rejectComment}
        onChange={(event) => setRejectComment(event.target.value)}
        placeholder="驳回原因（留空默认记为「人工驳回」）"
        rows={3}
      />
    </Modal>
    <AuditDrawer statement={trace ? { id: trace.statementId, statementNo: trace.statementNo } : undefined} onClose={() => setTrace(undefined)} />
  </>;
}
