import { useCallback, useMemo, useState } from 'react';
import { Button, Card, Input, Pagination, Segmented, Space, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { voucherProblemApi } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import { esc, type GridColumn, type GridRow } from '../bank-access/grid/kernel';
import { ExcelGrid } from '../bank-access/grid/ExcelGrid';
import { dateTime, money } from '../shared/format';
import { problemTypeText } from './voucherTexts';
import type { VoucherProblemRow } from './types';

/**
 * W16-A2 问题凭证列表（/statements/voucher-problems，voucher:push）：
 * 一键推送落桶行（statement_record.problem_type IS NOT NULL，V44）的统一工作清单。
 *  - 类型筛选（Segmented）+ 关键字搜索（流水号/摘要/对手方），problem_updated_at 倒序；
 *  - 行内「去修复」进编辑器（/statements/voucher-problems/:statementId）；
 *  - Excel 内核带来列宽拖拽 / 本页排序 / 列头筛选 / TSV 复制 / 列显隐（与凭证中心同口径）。
 *
 * 两条内核硬约束（同 VoucherCenterGridColumns）：
 *  · cell 返回 HTML 字符串（内核 innerHTML 渲染），所有文本必须过 esc()；
 *  · 非真实行字段 / 需要纯文本取值的列必须另给 text，否则 TSV 复制 / 导出出空白列。
 */

const TYPE_FILTERS: Array<{ key: string; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'PROBLEM_UNMATCHED', label: '未命中规则' },
  { key: 'PROBLEM_CANDIDATES', label: '多候选' },
  { key: 'PROBLEM_MANUAL_AMOUNT', label: '需人工定金额' },
  { key: 'PROBLEM_ELIGIBLE', label: '不满足自动制证' },
  { key: 'PROBLEM_PUSH_FAILED', label: '推送失败' },
];

const tag = (text: string, cls: string) => '<span class="tag ' + cls + '">' + esc(text) + '</span>';

const typeHtml = (value?: string | null): string => {
  if (!value) return tag('--', 'tag-muted');
  return value === 'PROBLEM_PUSH_FAILED'
    ? tag(problemTypeText(value), 'tag-danger')
    : tag(problemTypeText(value), 'tag-warn');
};

const asRow = (row: GridRow): VoucherProblemRow => row as unknown as VoucherProblemRow;

const problemColumns: GridColumn[] = [
  {
    k: 'statementNo', t: '流水号', w: 190, on: true, req: true, def: '必需，不可关闭',
    type: 'text', filter: 'text',
    cell: (row) => '<span class="mono">' + esc(asRow(row).statementNo) + '</span>',
    text: (row) => String(asRow(row).statementNo || ''),
  },
  {
    k: 'problemType', t: '问题类型', w: 150, on: true, type: 'text', filter: 'value',
    filterPlaceholder: '如 未命中规则',
    cell: (row) => typeHtml(asRow(row).problemType),
    text: (row) => problemTypeText(asRow(row).problemType),
  },
  {
    k: 'problemReason', t: '落桶原因', w: 280, on: true, type: 'text', filter: 'text',
    cell: (row) => {
      const text = String(asRow(row).problemReason || '--');
      return '<span title="' + esc(text) + '">' + esc(text.length > 46 ? text.slice(0, 46) + '…' : text) + '</span>';
    },
    text: (row) => String(asRow(row).problemReason || ''),
  },
  {
    k: 'transactionTime', t: '交易时间', w: 150, on: true, type: 'text', filter: 'date',
    cell: (row) => esc(dateTime(asRow(row).transactionTime || undefined)),
    text: (row) => String(asRow(row).transactionTime || ''),
  },
  {
    k: 'direction', t: '方向', w: 76, on: true, type: 'text', filter: 'value',
    cell: (row) => {
      const value = asRow(row).direction;
      return value === 'INCOME' ? tag('收', 'tag-ok')
        : value === 'EXPENSE' ? tag('付', 'tag-warn') : esc('--');
    },
    text: (row) => {
      const value = asRow(row).direction;
      return value === 'INCOME' ? '收' : value === 'EXPENSE' ? '付' : '';
    },
  },
  {
    k: 'amount', t: '金额', w: 130, on: true, type: 'money', align: 'num', filter: 'num',
    cell: (row) => '<span class="mono">' + esc(money(asRow(row).amount ?? undefined)) + '</span>',
    text: (row) => String(asRow(row).amount ?? ''),
  },
  {
    k: 'summary', t: '摘要', w: 220, on: true, type: 'text', filter: 'text',
    cell: (row) => esc(asRow(row).summary || '--'),
    text: (row) => String(asRow(row).summary || ''),
  },
  {
    k: 'counterpartyName', t: '对手方', w: 170, on: true, type: 'text', filter: 'text',
    cell: (row) => esc(asRow(row).counterpartyName || '--'),
    text: (row) => String(asRow(row).counterpartyName || ''),
  },
  {
    k: 'reviewStatus', t: '复核', w: 90, on: true, type: 'text', filter: 'value',
    cell: (row) => esc(asRow(row).reviewStatus || '--'),
    text: (row) => String(asRow(row).reviewStatus || ''),
  },
  {
    k: 'pushStatus', t: '推送', w: 110, on: true, type: 'text', filter: 'value',
    cell: (row) => esc(asRow(row).pushStatus || '--'),
    text: (row) => String(asRow(row).pushStatus || ''),
  },
  {
    k: 'problemUpdatedAt', t: '最近处理', w: 150, on: true, type: 'text', filter: 'date',
    cell: (row) => esc(dateTime(asRow(row).problemUpdatedAt || undefined)),
    text: (row) => String(asRow(row).problemUpdatedAt || ''),
  },
  {
    k: 'actions', t: '操作', w: 110, on: true, req: true, def: '必需，不可关闭',
    type: 'text',
    cell: () => '<button class="btn btn-sm" data-row-action="edit">去修复</button>',
    text: () => '',
  },
];

export function VoucherProblemListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');

  const loader = useCallback(
    () => voucherProblemApi.list({
      page,
      size: 20,
      type: typeFilter === 'ALL' ? undefined : typeFilter,
      keyword: keyword || undefined,
    }),
    [page, typeFilter, keyword],
  );
  const { data, error, reload } = useRemote(loader, [loader]);

  const gridRows = useMemo(() => (data?.records || []) as unknown as GridRow[], [data]);

  const onRowAction = useCallback((action: string, row: GridRow) => {
    if (action === 'edit') {
      navigate(`/statements/voucher-problems/${asRow(row).id}`);
    }
  }, [navigate]);

  const toast = useCallback((text: string) => message.success(text), []);

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">凭证与入账 / 问题凭证</span>
        <h2>问题凭证</h2>
        <p className="muted">
          「一键推送至金蝶」无法自动完成的流水在此排队（多候选 / 未命中规则 / 需人工定金额 / 推送失败）。
          进入编辑器修正科目、金额、借贷与辅助核算后提交推送，成功即自动出列。
        </p>
      </div>
      <Space wrap>
        <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
      </Space>
    </div>
    <Space wrap style={{ marginBottom: 12 }} size={12}>
      <Segmented
        value={typeFilter}
        onChange={(value) => { setPage(1); setTypeFilter(String(value)); }}
        options={TYPE_FILTERS.map(({ key, label }) => ({ label, value: key }))}
      />
      <Input.Search
        allowClear
        placeholder="流水号 / 摘要 / 对手方"
        style={{ width: 260 }}
        value={keywordInput}
        onChange={(event) => setKeywordInput(event.target.value)}
        onSearch={(value) => { setPage(1); setKeyword(value.trim()); }}
      />
    </Space>
    <Card>
      {error ? <ResourceFailure error={error} onRetry={reload} /> : <>
        <ExcelGrid
          id="voucher.problems"
          cols={problemColumns}
          rows={gridRows}
          pageSize={data?.size || 20}
          onRowAction={onRowAction}
          emptyText="暂无问题凭证 —— 一键推送的流水全部自动完成"
          findPlaceholder="Ctrl+F 流水号 / 摘要"
          toast={toast}
        />
        {data && data.total > data.size && (
          <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} showSizeChanger={false} onChange={setPage} />
        )}
      </>}
    </Card>
  </>;
}
