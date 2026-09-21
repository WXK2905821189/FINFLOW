import { useCallback, useMemo, useState } from 'react';
import {
  Button, Card, Input, Modal, Pagination, Segmented, Space, message,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import { ReloadOutlined } from '@ant-design/icons';
import { statementApi, voucherGroupApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { ExcelGrid } from '../bank-access/grid/ExcelGrid';
import type { GridRow } from '../bank-access/grid/kernel';
import { AuditDrawer } from '../statements/pages';
import { AiVoucherJobBanner } from './AiVoucherJobBanner';
import { VOUCHER_GROUP_FILTERS } from './voucherTexts';
import { voucherCenterColumns, toVoucherGridRows } from './VoucherCenterGridColumns';
import type { VoucherGroupFilter, VoucherGroupRow } from './types';

/**
 * V34 ⑦ 凭证中心（凭证管线一等视图）：W10（WP-6）由 antd Table 迁移至 Excel 内核。
 *  - 列表行 = 凭证记录（一期推送链路 1 笔流水 → 1 张凭证，「来源流水」列写明 N 笔 → 1 张单据）；
 *  - 状态四签（待复核/待推送/已推送/推送失败/已撤回）同时覆盖 AI 制证与规则引擎两条推送链路；
 *  - 行内操作（内核 data-row-action）：通过/驳回、推送、重新打开、撤回、查看凭证、追溯；
 *  - 内核带来列宽拖拽 / 本页排序 / 列头筛选 / TSV 复制 / 列显隐 / CSV 导出。
 */

export function VoucherCenterPage() {
  const navigate = useNavigate();
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
  const { data, error, reload } = useRemote(loader, [loader]);

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

  /** W10（V39）：撤回未推送金蝶的凭证 —— 标记「已撤回」+ 流水回池可重新制证。 */
  const withdraw = (row: VoucherGroupRow) => {
    Modal.confirm({
      title: `撤回凭证（${row.statementNo}）`,
      content: '仅未推送金蝶的凭证可撤回。撤回后该凭证标记「已撤回」（记录与凭证号保留、可追溯，操作写入审计），'
        + '对应流水回到「未制证」状态并可重新制证。已推送金蝶的凭证不可撤回（请在金蝶侧处理）。',
      okText: '确认撤回',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => withBusy(row, () => statementApi.withdraw(row.statementId), '凭证已撤回，流水可重新制证'),
    });
  };

  // W10（WP-6）：内核列（cell 为 HTML 字符串），操作按钮走 data-row-action 回投到本组件。
  // 必须 memo：ExcelGrid 以引用比较做增量同步，每次渲染都给新数组会触发 setRows → 清空行勾选。
  const gridColumns = useMemo(() => voucherCenterColumns({ canReview, canPush }), [canReview, canPush]);
  const gridRows = useMemo(() => toVoucherGridRows(data?.records || []), [data]);

  /** 内核行内动作 → 既有处理函数（visible 规则在列定义里，这里只做分发）。 */
  const onRowAction = (action: string, row: GridRow) => {
    const target = row as unknown as VoucherGroupRow;
    switch (action) {
      case 'doc': navigate(`/statements/voucher-doc/${target.statementId}`); break;
      case 'approve': approve(target); break;
      case 'reject': setRejectComment(''); setRejectRow(target); break;
      case 'push': push(target); break;
      case 'reopen': reopen(target); break;
      case 'withdraw': withdraw(target); break;
      case 'trace': setTrace(target); break;
      default: break;
    }
  };


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
    {/* 2026-09-21（V40）：DRAFT 制证已后台化，任务进度与逐行结果在此展示；
        任务完成后刷新列表，让新生成的草稿立即出现在下方。 */}
    {canPush && <AiVoucherJobBanner onFinished={() => { void reload(); }} />}
    <Card>
      {error ? <ResourceFailure error={error} onRetry={reload} /> : <>
        <ExcelGrid
          id="voucher.center"
          cols={gridColumns}
          rows={gridRows}
          pageSize={data?.size || 20}
          // 批量推送只针对「已复核且未推送成功」的行（与旧 rowSelection.getCheckboxProps 同口径）
          selectable={canPush}
          isRowSelectable={(row) => {
            const r = row as unknown as VoucherGroupRow;
            return r.reviewStatus === 'APPROVED' && r.pushStatus !== 'PUSHED' && r.pushStatus !== 'GL_PUSHED';
          }}
          disabledRowHint={(row) => {
            const r = row as unknown as VoucherGroupRow;
            return r.pushStatus === 'PUSHED' || r.pushStatus === 'GL_PUSHED'
              ? '该凭证已推送金蝶，不能重复推送'
              : '仅「已复核且未推送成功」的凭证可批量推送';
          }}
          onSelectionChange={(picked) => setSelectedIds(picked.map((row) => Number((row as unknown as VoucherGroupRow).statementId)))}
          onRowAction={onRowAction}
          emptyText="暂无对应状态的凭证记录"
          findPlaceholder="Ctrl+F 凭证号 / 流水号 / 摘要"
          toast={(text) => message.success(text)}
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
