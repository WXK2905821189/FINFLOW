import { useCallback, useMemo, useRef, useState, type Key } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, InputNumber, Modal, Pagination, Segmented, Select, Space, Spin, Table, Tabs, Tag, Tooltip, Tree, message, type TableColumnsType } from 'antd';
import { FileTextOutlined, FilterOutlined, PlayCircleOutlined, RobotOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi, bankApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, isUnavailableStatus, isFailedStatus } from '../shared/format';
import { BankProjectionState, StatementDetail, BalanceDetail, type BankQueryRow } from './BankDataQueryColumns';
import { balanceGridColumns, statementGridColumns, decorateStatementRows } from './BankQueryGridColumns';
import { ExcelGrid } from './grid/ExcelGrid';
import { rawCell, num2, type GridColumn, type GridInstance, type GridRow, type GridTotals } from './grid/kernel';
import { useGridPreference } from './grid/useGridPreference';
import { BANK_NAME_TEXT, prettyPayload } from './bankQueryTexts';
import type { BankAccount, CompanyOption, BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow, BankRawMessageDetail, AiVoucherBatchResult, AiVoucherRowResult } from '../../types';

export const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
} as const;

export type BankQueryFilters = {
  keyword: string;
  /** 多选账户（空数组=全部可见账户）；即选即查。 */
  accountIds: string[];
  status: string;
  sourceSystem: string;
  syncJobNo: string;
  requestId: string;
  from: string;
  to: string;
  companyId: string;
  /** WP-C Excel 式逐列筛选（服务端参数；「更多筛选」抽屉与 chips 共用）。 */
  accountNoSuffix: string;
  currency: string;
  loanCode: string;
  counterparty: string;
  statementNo: string;
  minAmount: string;
  maxAmount: string;
};

export const emptyBankQueryFilters: BankQueryFilters = {
  keyword: '', accountIds: [], status: '', sourceSystem: '', syncJobNo: '', requestId: '', from: '', to: '', companyId: '',
  accountNoSuffix: '', currency: '', loanCode: '', counterparty: '', statementNo: '', minAmount: '', maxAmount: '',
};

/** 币种下拉（WP-C）：后端把 CNY 展开命中 {CNY,10,01}，银行码/ISO 全覆盖。 */
const CURRENCY_OPTIONS = [{ value: 'CNY', label: '人民币' }];

/** CSV 单元格：逗号 / 引号 / 换行必须加引号包裹，否则 Excel 会把一行拆成多列。 */
const csvCell = (value: string) => (/[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value);

export function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const companyName = useAuthStore((state) => state.user?.companyName);
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const canViewRawMessage = hasPermission('bankdata:raw:view');
  // 跨公司查看（V24）：仅持有 bankdata:cross-company:view 权限的用户渲染公司下拉与公司列。
  const canCrossCompany = hasPermission('bankdata:cross-company:view');
  const companyOptionsLoader = useCallback(() => bankPipelineApi.companyOptions(), []);
  const { data: companyOptionRows } = useRemote<CompanyOption[]>(companyOptionsLoader, [companyOptionsLoader]);
  // antd Select 需要 {value,label}；后端返回 {id,name}，此前直喂导致下拉渲染空白项（2026-09-17 修复）。
  const companyOptions = (companyOptionRows || []).map((option) => ({ value: option.id, label: option.name }));
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [submitted, setSubmitted] = useState(false);
  const [syncTriggering, setSyncTriggering] = useState(false);
  const [filters, setFilters] = useState<BankQueryFilters>(emptyBankQueryFilters);
  const [draft, setDraft] = useState<BankQueryFilters>(emptyBankQueryFilters);
  const [selected, setSelected] = useState<BankQueryRow>();
  // 行级报文抽屉（方案 A）：余额/流水行 → 该次同步留存的原始报文，证明银行真的回答了什么。
  // 权限与「原始报文」模块同源（bankdata:raw:view，仅 role 1/2），无权限的用户不渲染入口。
  const [rawMessage, setRawMessage] = useState<BankRawMessageDetail>();
  const [rawMessageLoading, setRawMessageLoading] = useState(false);
  const [rawMessageError, setRawMessageError] = useState<string>();
  const openRawMessage = async (id: number) => {
    setRawMessage(undefined);
    setRawMessageError(undefined);
    setRawMessageLoading(true);
    try {
      setRawMessage(await bankPipelineApi.getRawMessage(id));
    } catch (reason) {
      setRawMessageError(reason instanceof Error ? reason.message : '报文加载失败，请稍后重试');
    } finally {
      setRawMessageLoading(false);
    }
  };
  // Where to return keyboard focus when the detail drawer closes. Kept in state rather
  // than a ref: writing a ref from a handler that flows through render-created column
  // callbacks trips the react-compiler refs rule, and state does the same job here.
  const [focusReturn, setFocusReturn] = useState<HTMLElement | null>(null);
  const isStatement = resource === 'statements';
  // 一键 AI 制证（2026-09-16）：流水 tab 专属，终局闸门 voucher:push（复核已内化进服务端并留审计）。
  const canAiVoucher = isStatement && hasPermission('voucher:push');
  // 账户筛选数据源：当前企业授权的银行账户（后端按公司隔离返回）。按银行分组展示，
  // 选项值仍是内部账户 ID —— 查询接口本身无需改动。
  const accountsLoader = useCallback(() => bankApi.accounts(), []);
  const { data: accounts } = useRemote<BankAccount[]>(accountsLoader, [accountsLoader]);
  const accountOptions = useMemo(() => {
    const groups = new Map<string, BankAccount[]>();
    (accounts || []).forEach((account) => {
      const list = groups.get(account.bankCode) || [];
      list.push(account);
      groups.set(account.bankCode, list);
    });
    return Array.from(groups.entries()).map(([bankCode, list]) => ({
      label: BANK_NAME_TEXT[bankCode] || bankCode,
      title: bankCode,
      options: list.map((account) => ({
        value: String(account.id),
        label: `${account.accountName}（${account.maskedAccountNumber}）`,
      })),
    }));
  }, [accounts]);
  // WP-C（2026-09-17）流水查询左侧主体树：公司主体 → 账户，点选即联动 accountIds/companyId。
  const subjectTreeData = useMemo(() => {
    const companyNameById = new Map<number, string>();
    (companyOptionRows || []).forEach((option) => companyNameById.set(option.id, option.name));
    const groups = new Map<string, BankAccount[]>();
    (accounts || []).forEach((account) => {
      const key = account.companyId != null ? String(account.companyId) : 'unassigned';
      const list = groups.get(key) || [];
      list.push(account);
      groups.set(key, list);
    });
    return Array.from(groups.entries()).map(([companyKey, list]) => {
      const first = list[0];
      const name = (first?.companyId != null && companyNameById.get(first.companyId))
        || first?.companyName || companyName || '本公司主体';
      return {
        key: `company:${companyKey}`,
        title: <span>{name}<span className="table-sub">（{list.length} 户）</span></span>,
        children: list.map((account) => ({
          key: `account:${account.id}`,
          title: `${account.accountName}（${account.maskedAccountNumber}）`,
        })),
      };
    });
  }, [accounts, companyOptionRows, companyName]);
  const treeSelectedKeys = useMemo(() => [
    ...filters.accountIds.map((id) => `account:${id}`),
    ...(filters.companyId && filters.accountIds.length === 0 ? [`company:${filters.companyId}`] : []),
  ], [filters.accountIds, filters.companyId]);
  const onTreeSelect = (keys: readonly Key[]) => {
    const active = keys[keys.length - 1];
    if (typeof active !== 'string') {
      applyFilter({ accountIds: [] });
      return;
    }
    if (active.startsWith('account:')) {
      applyFilter({ accountIds: [active.slice('account:'.length)] });
    } else if (active.startsWith('company:')) {
      const company = active.slice('company:'.length);
      applyFilter({ companyId: company === 'unassigned' ? '' : company, accountIds: [] });
    }
  };
  // 「更多筛选」抽屉：服务端逐列筛选（借贷 / 收付方 / 流水号 / 金额区间）。
  // V35 内核的列头筛选是**仅本页**口径，替代不了这几个服务端条件，所以必须保留入口。
  const [moreFilterOpen, setMoreFilterOpen] = useState(false);
  const [moreDraft, setMoreDraft] = useState({ loanCode: '', counterparty: '', statementNo: '', minAmount: '', maxAmount: '' });
  const openMoreFilter = () => {
    setMoreDraft({
      loanCode: filters.loanCode, counterparty: filters.counterparty, statementNo: filters.statementNo,
      minAmount: filters.minAmount, maxAmount: filters.maxAmount,
    });
    setMoreFilterOpen(true);
  };
  const applyMoreFilter = () => {
    applyFilter({ ...moreDraft });
    setMoreFilterOpen(false);
  };
  const clearMoreFilter = () => {
    setMoreDraft({ loanCode: '', counterparty: '', statementNo: '', minAmount: '', maxAmount: '' });
    applyFilter({ loanCode: '', counterparty: '', statementNo: '', minAmount: '', maxAmount: '' });
    setMoreFilterOpen(false);
  };
  const activeMoreFilters = [filters.loanCode, filters.counterparty, filters.statementNo, filters.minAmount, filters.maxAmount]
    .filter((value) => value !== '').length;
  const loader = useCallback(() => submitted ? bankPipelineApi.queryProjection<BankQueryRow>(resource, { page, size, keyword: filters.keyword || undefined, accountIds: filters.accountIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0), status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined, sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined, requestId: filters.requestId || undefined, companyId: filters.companyId ? Number(filters.companyId) : undefined, accountNoSuffix: filters.accountNoSuffix || undefined, currency: filters.currency || undefined, loanCode: filters.loanCode || undefined, counterparty: filters.counterparty || undefined, statementNo: filters.statementNo || undefined, minAmount: filters.minAmount === '' ? undefined : Number(filters.minAmount), maxAmount: filters.maxAmount === '' ? undefined : Number(filters.maxAmount) }) : Promise.resolve<BankDataProjectionPage<BankQueryRow>>({ page, size, total: 0, records: [] }), [resource, page, size, filters, submitted]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage<BankQueryRow>>(loader, [loader]);
  const query = () => { setPage(1); setFilters(draft); setSubmitted(true); };
  // 离散筛选（账户/公司/状态/日期）「即选即查」：不必再点查询按钮。输入框仍走按钮/回车，
  // 避免逐字符触发请求。
  const applyFilter = (patch: Partial<BankQueryFilters>) => {
    setPage(1);
    setDraft((current) => ({ ...current, ...patch }));
    setFilters((current) => ({ ...current, ...patch }));
    setSubmitted(true);
  };
  const reset = () => {
    setPage(1);
    setDraft(emptyBankQueryFilters);
    setFilters(emptyBankQueryFilters);
    setMoreDraft({ loanCode: '', counterparty: '', statementNo: '', minAmount: '', maxAmount: '' });
    setSubmitted(false);
  };
  const setDateFilter = (key: 'from' | 'to', value?: string) => applyFilter({ [key]: value || '' });
  const openDetail = useCallback((row: BankQueryRow) => {
    setFocusReturn(document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setSelected(row);
  }, []);
  const closeDetail = () => {
    setSelected(undefined);
    window.setTimeout(() => focusReturn?.focus(), 0);
  };

  /* ==================================================================
     V35 表格内核接入
     ================================================================== */

  /** 内核实例：导出选中行时按「用户当前实际可见的列」出列，所以必须读实例而不是声明。 */
  const gridInstanceRef = useRef<GridInstance | null>(null);
  const gridColumns = useMemo<GridColumn[]>(
    () => (isStatement ? statementGridColumns({ canCrossCompany }) : balanceGridColumns({ canCrossCompany })),
    [isStatement, canCrossCompany],
  );
  // 借贷双轨派生字段必须在灌数据前写进行对象：内核排序 / 区间筛选 / 值勾选 / TSV 复制
  // 都直接读 row[col.k]，派生列没有真实字段就是空的。这里 memo 住，避免每次渲染换新数组
  // 触发 setRows → 行勾选被清空。
  const gridRows = useMemo<GridRow[]>(() => {
    const records = data?.records;
    if (!records) return [];
    return isStatement
      ? decorateStatementRows(records as BankDataStatementRow[])
      : (records as GridRow[]);
  }, [data, isStatement]);
  const preferenceScope = isStatement ? 'bankdata.statements' : 'bankdata.balances';
  const { snapshot: gridSnapshot, ready: gridPreferenceReady, save: saveGridPreference } = useGridPreference(preferenceScope);
  // 全量口径只拿得到行数：投影接口没有返回金额聚合。宁可只报行数，也绝不拿本页求和冒充全量合计。
  const gridTotalAgg = useMemo<GridTotals | undefined>(() => (data ? { count: data.total } : undefined), [data]);
  const gridGroupBy = isStatement ? 'accountMasked' : 'companyName';
  const gridGroupMeta = useMemo(() => {
    if (isStatement) {
      return (group: string, list: GridRow[]) => {
        const debit = list.reduce((sum, row) => sum + (Number(row.debitAmount) || 0), 0);
        const credit = list.reduce((sum, row) => sum + (Number(row.creditAmount) || 0), 0);
        return `${group ? '' : '未标注账户 · '}${list.length} 笔 · 借 <b>¥ ${num2(debit)}</b> / 贷 <b>¥ ${num2(credit)}</b>`;
      };
    }
    return (group: string, list: GridRow[]) => {
      const total = list.reduce((sum, row) => sum + (Number(row.availableBalance) || 0), 0);
      const latest = list.map((row) => String(row.asOfTime || '')).sort().pop() || '—';
      return `${list.length} 个账户 · 可用余额合计 <b>¥ ${num2(total)}</b> · 最近截止 ${latest.slice(0, 16).replace('T', ' ') || '—'}`;
    };
  }, [isStatement]);

  const [exporting, setExporting] = useState(false);
  // 一键 AI 制证（2026-09-16）：行多选 → AI 建议 → 推送金蝶；MANUAL 制证模式账户的行禁选；
  // 已推送行禁选（服务端幂等键=同公司同银行流水号）。
  const manualAccountIds = useMemo(() => new Set(
    (accounts || []).filter((account) => account.accountingMode === 'MANUAL').map((account) => account.id),
  ), [accounts]);
  const [selectedStatementIds, setSelectedStatementIds] = useState<number[]>([]);
  const [aiVoucherRunning, setAiVoucherRunning] = useState(false);
  const [aiVoucherResult, setAiVoucherResult] = useState<AiVoucherBatchResult>();
  // 双模式（2026-09-17）：DRAFT=生成草稿停在「凭证草稿与制证」页待人工复核；PUSH=复核内化后直接推送金蝶。
  const aiVoucherSelected = (mode: 'DRAFT' | 'PUSH') => {
    if (!selectedStatementIds.length) {
      message.warning('请先勾选要制证的银行流水行');
      return;
    }
    const asDraft = mode === 'DRAFT';
    Modal.confirm({
      title: asDraft
        ? `确认对 ${selectedStatementIds.length} 条银行流水生成 AI 制证草稿`
        : `确认对 ${selectedStatementIds.length} 条银行流水 AI 制证并推送`,
      content: asDraft
        ? '流程：转入标准流水（幂等）→ AI 生成入账建议写入复核意见 → 停留在「凭证草稿与制证」页待复核，不会推送金蝶。请稍后在该页人工审核并点击推送。'
        : '流程：转入标准流水（幂等）→ AI 生成入账建议 → 复核内化后直接推送金蝶（出纳收付款单，提交不审核）。审核请在金蝶侧人工完成；AI 建议不可用时将直接推送原文摘要并标注。',
      okText: asDraft ? '确认生成草稿' : '确认制证推送',
      cancelText: '取消',
      onOk: async () => {
        setAiVoucherRunning(true);
        try {
          const result = await bankPipelineApi.aiVoucher({ statementIds: selectedStatementIds, mode });
          setAiVoucherResult(result);
          setSelectedStatementIds([]);
          reload();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : 'AI 制证请求未能完成');
        } finally {
          setAiVoucherRunning(false);
        }
      },
    });
  };
  const exportQuery = useMemo(() => ({
    keyword: filters.keyword || undefined, accountIds: filters.accountIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0),
    status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined,
    sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined,
    requestId: filters.requestId || undefined,
    companyId: filters.companyId ? Number(filters.companyId) : undefined,
    accountNoSuffix: filters.accountNoSuffix || undefined, currency: filters.currency || undefined,
    loanCode: filters.loanCode || undefined, counterparty: filters.counterparty || undefined,
    statementNo: filters.statementNo || undefined,
    minAmount: filters.minAmount === '' ? undefined : Number(filters.minAmount),
    maxAmount: filters.maxAmount === '' ? undefined : Number(filters.maxAmount),
  }), [filters]);
  const exportCsv = async () => {
    setExporting(true);
    try {
      await bankPipelineApi.exportCsv(resource, exportQuery);
      message.success('导出已生成');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
  };
  /** 导出按钮的统一入口：直连未启用时导出必然 503，先在按钮层给明确原因，别让用户白等一次失败。 */
  const exportAll = () => {
    if (directLinkOff) {
      message.warning('真实银行直联未连接，服务端暂无可导出的数据');
      return;
    }
    void exportCsv();
  };
  /** 仅导出选中行：服务端导出接口只吃查询条件、不吃行 ID，所以这里按当前可见列在本地出 CSV。 */
  const exportSelectedRows = (picked: GridRow[]) => {
    const cols = (gridInstanceRef.current?.state.cols || gridColumns).filter((col) => col.on && col.k !== 'detail');
    const lines = [cols.map((col) => csvCell(col.t)).join(',')];
    picked.forEach((row) => lines.push(cols.map((col) => csvCell(rawCell(row, col))).join(',')));
    const blob = new Blob(['\ufeff' + lines.join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${isStatement ? '银行流水' : '银行余额'}选中${picked.length}行.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    message.success(`已导出选中 ${picked.length} 行（${cols.length} 列，按当前显示列序）`);
  };
  const triggerSyncFromFilters = () => {
    // 手动同步仍是单账户语义：多选中取第一个；多账户补拉请用「同步任务 → 补拉历史数据」。
    const accountId = Number(draft.accountIds[0] || filters.accountIds[0]);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      message.warning('请先在「账户」下拉中选择要同步的银行账户');
      return;
    }
    Modal.confirm({
      title: '确认创建同步任务',
      content: '将按所选账户与时间范围向 FINFLOW 服务端创建同步任务；浏览器不会直接连接银行，任务结果以服务端幂等状态为准。',
      okText: '确认创建',
      cancelText: '取消',
      onOk: async () => {
        setSyncTriggering(true);
        try {
          const job = await bankPipelineApi.triggerJob({
            jobType: 'STATEMENT_PULL',
            bankAccountId: accountId,
            connectionCode: draft.sourceSystem || filters.sourceSystem || undefined,
            windowStart: draft.from || filters.from || undefined,
            windowEnd: draft.to || filters.to || undefined,
          });
          message.success(`同步任务已创建：${job.jobNo}`);
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '未能创建同步任务');
        } finally {
          setSyncTriggering(false);
        }
      },
    });
  };
  const definition = bankDataResources[resource];
  /** 真实直连未启用：后端 notConnectedPage 会带 enabled=false，此时导出必然 503，按钮须先禁用。 */
  const directLinkOff = data?.enabled === false || isUnavailableStatus(data?.status);
  const emptyDescription = directLinkOff ? '真实银行直联未连接，无法获取数据。' : isFailedStatus(data?.status) ? '银行查询失败，请检查同步任务。' : '当前筛选没有匹配的真实银行数据。';
  const detailRequestId = isStatement
    ? (selected as BankDataStatementRow | undefined)?.taskRequestId
    : (selected as BankDataBalanceRow | undefined)?.taskRequestId;
  const detailTitle = selected
    ? (isStatement ? `银行流水字段 · ${(selected as BankDataStatementRow).statementNo || selected.id}` : `银行余额字段 · ${selected.id}`)
    : '银行字段明细';

  const filterBar = (
    <div className="filters">
      <div className="field">
        <label>关键字</label>
        <Input style={{ width: 200 }} value={draft.keyword} placeholder="流水号 / 摘要 / 收付方" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))} />
      </div>
      <div className="field">
        <label>账户</label>
        <Select
          allowClear
          showSearch
          mode="multiple"
          maxTagCount={2}
          style={{ minWidth: 238 }}
          placeholder="全部账户"
          value={draft.accountIds.length ? draft.accountIds : undefined}
          options={accountOptions}
          notFoundContent={accounts === undefined ? <Spin size="small" /> : <Empty description={draft.companyId ? '该公司主体下暂无账户' : '当前企业暂无授权账户'} />}
          onChange={(values) => applyFilter({ accountIds: values || [] })}
          onClear={() => applyFilter({ accountIds: [] })}
        />
      </div>
      {canCrossCompany && (
        <div className="field">
          <label>公司主体</label>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ minWidth: 170 }}
            placeholder="全部"
            value={draft.companyId || undefined}
            options={companyOptions}
            notFoundContent={<Spin size="small" />}
            onChange={(value) => applyFilter({ companyId: value ? String(value) : '' })}
          />
        </div>
      )}
      {!canCrossCompany && (
        <div className="field">
          <label>公司主体</label>
          <Tooltip title="跨公司查看需「跨公司银行数据查看」权限，当前仅显示本公司数据；公司主体的增删在「银行数据 → 账户与主体归档」维护（字典中心的「公司主体」即该档案的只读镜像）。">
            <Select disabled placeholder="仅本公司" style={{ minWidth: 170 }} options={[]} />
          </Tooltip>
        </div>
      )}
      <div className="field">
        <label>账号尾号</label>
        <Input style={{ width: 130 }} value={draft.accountNoSuffix} placeholder="后 4/6 位" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, accountNoSuffix: event.target.value }))} />
      </div>
      <div className="field">
        <label>币种</label>
        <Select style={{ minWidth: 110 }} value={draft.currency || undefined} allowClear placeholder="全部" options={CURRENCY_OPTIONS} onChange={(value) => applyFilter({ currency: value || '' })} />
      </div>
      <div className="field">
        <label>开始时间</label>
        <DatePicker showTime style={{ width: 168 }} placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} />
      </div>
      <div className="field">
        <label>结束时间</label>
        <DatePicker showTime style={{ width: 168 }} placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} />
      </div>
      <div className="field">
        <label>&nbsp;</label>
        <Space>
          <Button icon={<FilterOutlined />} onClick={openMoreFilter}>
            更多筛选{activeMoreFilters > 0 && <Tag color="green" style={{ marginLeft: 6, height: 18, lineHeight: '16px' }}>{activeMoreFilters} 项已生效</Tag>}
          </Button>
          <Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button>
          <Button onClick={reset}>重置</Button>
        </Space>
      </div>
    </div>
  );

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">银行数据 / 数据查询{companyName ? ` · ${companyName}` : ''}</span>
          <h2>{definition.title}</h2>
          <p className="muted">{canCrossCompany ? '可跨公司主体查看全部 ACTIVE 公司的银行数据，行内标注归属公司；' : '数据按登录公司主体隔离展示；'}查询读取的是已同步落库的银行数据（不实时请求银行，新数据由每晚自动同步任务或手动补拉获取）；「公司主体」为本系统银行账户档案的归属公司——银行报文只含账号与户名，账户归属由贵司在「账户与主体归档」中维护，行内公司主体列实时跟随账户当前归属（未归属账户标注「未归属」）。直出银行原始字段，本方账号明文展示，完整报文体在「原始报文」模块查看。</p>
        </div>
        {canTriggerSync && <Button icon={<PlayCircleOutlined />} loading={syncTriggering} onClick={triggerSyncFromFilters}>按所选账户创建同步任务</Button>}
      </div>
      <Card className="filter-card">{filterBar}</Card>
      <Card
        title={canAiVoucher && submitted
          ? <Space wrap>
              <span>查询结果</span>
              <Button size="small" type="primary" icon={<RobotOutlined />} disabled={!selectedStatementIds.length} loading={aiVoucherRunning} onClick={() => aiVoucherSelected('DRAFT')}>AI 制证为草稿{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button>
              <Button size="small" type="primary" ghost icon={<ThunderboltOutlined />} disabled={!selectedStatementIds.length} loading={aiVoucherRunning} onClick={() => aiVoucherSelected('PUSH')}>AI 制证并推送{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button>
              <span className="muted">草稿：AI 预填后在「凭证草稿与制证」页人工审核推送；推送：复核内化后直送金蝶。已推送行与纯人工制证账户不可选</span>
            </Space>
          : '查询结果'}
      >
        {error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" /> : (
          <div style={isStatement ? { display: 'flex', gap: 16, alignItems: 'stretch' } : undefined}>
            {isStatement && (
              // WP-C（2026-09-17）：金蝶式左侧主体树——公司主体 → 账户，点选即联动筛选。
              <div style={{ width: 260, flexShrink: 0, borderRight: '1px solid #f0f0f0', paddingRight: 12, overflow: 'auto', maxHeight: 680 }}>
                <div className="muted" style={{ marginBottom: 8 }}>公司主体 / 账户</div>
                {subjectTreeData.length ? (
                  <Tree
                    blockNode
                    defaultExpandAll
                    selectedKeys={treeSelectedKeys}
                    onSelect={onTreeSelect}
                    treeData={subjectTreeData}
                  />
                ) : <Spin size="small" />}
                <p className="muted" style={{ fontSize: 12, marginTop: 8 }}>点选主体或账户筛选数据；再次点击取消。时间区间与摘要关键字在上方筛选区。</p>
              </div>
            )}
            <div style={{ flex: 1, minWidth: 0 }}>
              <BankProjectionState data={data} />
              {data?.requestId && <div className="query-request-id">请求编号：<span className="mono">{data.requestId}</span><Link to={`/operations/logs?requestId=${encodeURIComponent(data.requestId)}`}>查看脱敏审计追溯</Link></div>}
              <ExcelGrid
                id={resource}
                instanceRef={gridInstanceRef}
                cols={gridColumns}
                rows={gridRows}
                pageSize={data?.size || size}
                groupBy={gridGroupBy}
                groupedDefault={isStatement ? false : canCrossCompany}
                showGroupSwitch={isStatement || canCrossCompany}
                groupSwitchLabel={isStatement ? '按本方账户分组' : '按主体分组'}
                groupMeta={gridGroupMeta}
                sumKey={isStatement ? 'signedAmount' : 'availableBalance'}
                sumLabel={isStatement ? '本页金额净额（贷−借）' : '本页可见小计'}
                totalAgg={gridTotalAgg}
                selectable={canAiVoucher}
                isRowSelectable={isStatement
                  ? (row) => !row.transferred && !manualAccountIds.has(Number(row.bankAccountId))
                  : undefined}
                disabledRowHint={isStatement
                  ? (row) => (row.transferred ? '该行已转入标准流水，不能重复制证' : '该账户为纯人工制证模式，不能走 AI 制证')
                  : undefined}
                rowClass={isStatement ? (row) => (row.transferred ? 'is-locked' : '') : undefined}
                onSelectionChange={canAiVoucher
                  ? (picked) => setSelectedStatementIds(picked.map((row) => Number(row.id)))
                  : undefined}
                onRowAction={(action, row) => { if (action === 'detail') openDetail(row as BankQueryRow); }}
                onExport={exportAll}
                onExportRows={directLinkOff ? undefined : exportSelectedRows}
                exportLabel={exporting ? '导出中…' : '导出 CSV'}
                exportBadge={<Tag color="green" style={{ height: 18, lineHeight: '16px', fontSize: 11 }}>筛选全量 {data?.total ?? 0} 行</Tag>}
                findPlaceholder={isStatement ? 'Ctrl+F 流水号 / 收付方' : 'Ctrl+F 账号 / 户名'}
                emptyText={emptyDescription}
                preferenceReady={gridPreferenceReady}
                initialSnapshot={gridSnapshot}
                onSnapshotChange={saveGridPreference}
                toast={(text) => message.success(text)}
                footer={data ? (
                  <Pagination
                    className="table-pagination"
                    current={data.page}
                    pageSize={data.size || size}
                    total={data.total}
                    showSizeChanger
                    pageSizeOptions={[10, 20, 50]}
                    onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }}
                  />
                ) : undefined}
              />
            </div>
          </div>
        )}
      </Card>
      <Drawer
        title="更多筛选（服务端口径）"
        width={420}
        open={moreFilterOpen}
        onClose={() => setMoreFilterOpen(false)}
        extra={<Space><Button onClick={clearMoreFilter}>清除</Button><Button type="primary" onClick={applyMoreFilter}>应用并查询</Button></Space>}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="与表头筛选不是一回事"
          description="这里填写的是服务端查询条件，作用于全量数据；表格列头漏斗筛选只作用于当前已加载的这一页，两者叠加生效。"
        />
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <div className="muted" style={{ marginBottom: 6 }}>借贷方向</div>
            <Segmented
              value={moreDraft.loanCode || 'ALL'}
              options={[{ label: '全部', value: 'ALL' }, { label: '收（贷）', value: 'C' }, { label: '付（借）', value: 'D' }]}
              onChange={(value) => setMoreDraft((current) => ({ ...current, loanCode: value === 'ALL' ? '' : String(value) }))}
            />
          </div>
          <div>
            <div className="muted" style={{ marginBottom: 6 }}>收付方</div>
            <Input allowClear placeholder="收付方名称关键字" value={moreDraft.counterparty} onChange={(event) => setMoreDraft((current) => ({ ...current, counterparty: event.target.value }))} />
          </div>
          <div>
            <div className="muted" style={{ marginBottom: 6 }}>流水号</div>
            <Input allowClear placeholder="流水号关键字" value={moreDraft.statementNo} onChange={(event) => setMoreDraft((current) => ({ ...current, statementNo: event.target.value }))} />
          </div>
          <div>
            <div className="muted" style={{ marginBottom: 6 }}>金额（带符号）</div>
            <Space>
              <InputNumber style={{ width: 150 }} placeholder="最小金额" value={moreDraft.minAmount === '' ? undefined : Number(moreDraft.minAmount)} onChange={(value) => setMoreDraft((current) => ({ ...current, minAmount: value === null ? '' : String(value) }))} />
              <span>~</span>
              <InputNumber style={{ width: 150 }} placeholder="最大金额" value={moreDraft.maxAmount === '' ? undefined : Number(moreDraft.maxAmount)} onChange={(value) => setMoreDraft((current) => ({ ...current, maxAmount: value === null ? '' : String(value) }))} />
            </Space>
            <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>带符号金额：收款为正、付款为负</div>
          </div>
        </Space>
      </Drawer>
      <Modal
        title={`AI 制证结果 · 草稿 ${aiVoucherResult?.draftCount ?? 0} / 推送 ${aiVoucherResult?.pushedCount ?? 0} / 幂等跳过 ${aiVoucherResult?.alreadyCount ?? 0} / 跳过 ${aiVoucherResult?.skippedCount ?? 0} / 失败 ${aiVoucherResult?.failedCount ?? 0}`}
        open={Boolean(aiVoucherResult)}
        onCancel={() => setAiVoucherResult(undefined)}
        footer={<Space>
          {(aiVoucherResult?.draftCount ?? 0) > 0 && <Link to="/statements/vouchers"><Button>去「凭证草稿与制证」审核推送</Button></Link>}
          <Button type="primary" onClick={() => setAiVoucherResult(undefined)}>知道了</Button>
        </Space>}
        width={720}
      >
        {aiVoucherResult && (
          <Table<AiVoucherRowResult>
            rowKey="bankDataStatementId"
            size="small"
            pagination={false}
            dataSource={aiVoucherResult.rows}
            columns={[
              { title: '流水号', dataIndex: 'statementNo', render: (value: string) => <span className="mono">{value}</span> },
              {
                title: '结果', dataIndex: 'outcome', width: 110,
                render: (value: AiVoucherRowResult['outcome']) => (
                  <Tag color={value === 'PUSHED' ? 'green' : value === 'DRAFT_CREATED' ? 'blue' : value === 'ALREADY_PUSHED' ? 'geekblue' : value === 'ALREADY_APPROVED' ? 'cyan' : value.startsWith('SKIPPED') ? 'orange' : 'red'}>
                    {value === 'PUSHED' ? '已推送' : value === 'DRAFT_CREATED' ? '草稿已生成' : value === 'ALREADY_APPROVED' ? '已过复核' : value === 'ALREADY_PUSHED' ? '幂等跳过' : value === 'SKIPPED_MANUAL' ? '人工制证' : value === 'SKIPPED_REJECTED' ? '已驳回' : '失败'}
                  </Tag>
                ),
              },
              {
                title: 'AI 建议', dataIndex: 'aiSuggestedSummary', ellipsis: true,
                render: (value: string | null, row: AiVoucherRowResult) => row.aiStatus === 'OK'
                  ? (value || '--')
                  : <Tooltip title={row.message}><Tag>AI 不可用</Tag></Tooltip>,
              },
              { title: '金蝶单号', dataIndex: 'voucherNo', render: (value: string | null) => value ? <span className="mono">{value}</span> : '--' },
              { title: '说明', dataIndex: 'message', ellipsis: true, render: (value: string | null) => value || '--' },
            ] satisfies TableColumnsType<AiVoucherRowResult>}
          />
        )}
      </Modal>
      <Drawer title={detailTitle} width={560} open={Boolean(selected)} onClose={closeDetail}>
        {selected && (
          <>
            <Alert
              type="info"
              showIcon
              message="银行原始字段"
              description="字段 ID 与银行接口一致，可与银行导出的交易明细逐列比对；借贷、冲账、信息标志均未做业务翻译。点击下方「查看本次报文」可直接看到该行数据的原始请求/响应留档。"
            />
            {isStatement ? <StatementDetail row={selected as BankDataStatementRow} /> : <BalanceDetail row={selected as BankDataBalanceRow} />}
            {canViewRawMessage && selected.rawMessageId && (
              <Button
                className="raw-message-entry"
                icon={<FileTextOutlined />}
                onClick={() => void openRawMessage(selected.rawMessageId as number)}
              >
                查看本次报文
              </Button>
            )}
            <Descriptions className="projection-detail" column={1} size="small" bordered>
              <Descriptions.Item label="银行请求号"><span className="mono">{displayValue(selected.bankRequestNo)}</span></Descriptions.Item>
              <Descriptions.Item label="同步任务号"><span className="mono">{displayValue(selected.taskNo)}</span></Descriptions.Item>
              <Descriptions.Item label="请求编号"><span className="mono">{displayValue(detailRequestId)}</span></Descriptions.Item>
              <Descriptions.Item label="同步任务状态">
                {selected.taskStatus === 'UNKNOWN'
                  ? <Space size={4} wrap><StatusTag status="UNKNOWN" /><span>银行响应状态未知，该行数据待人工核验</span></Space>
                  : displayValue(selected.taskStatus)}
              </Descriptions.Item>
              <Descriptions.Item label="校验状态"><StatusTag status={selected.validationStatus} /></Descriptions.Item>
              <Descriptions.Item label="报文摘要"><span className="mono">{displayValue(selected.contentSha256)}</span></Descriptions.Item>
              <Descriptions.Item label="入库时间">{dateTime(selected.createdAt)}</Descriptions.Item>
            </Descriptions>
            {detailRequestId && <Link className="trace-link" to={`/operations/logs?requestId=${encodeURIComponent(detailRequestId)}`}>查看该请求的脱敏日志与审计追溯</Link>}
          </>
        )}
      </Drawer>
      <Drawer
        title={`银行报文留档 · ${rawMessage?.bankRequestNo || (rawMessageLoading ? '加载中' : '未知请求')}`}
        width={720}
        open={Boolean(rawMessage) || rawMessageLoading}
        onClose={() => { setRawMessage(undefined); setRawMessageError(undefined); setRawMessageLoading(false); }}
      >
        {rawMessageLoading && <div className="raw-message-loading"><Spin /><div className="muted" style={{ marginTop: 8 }}>正在加载报文……</div></div>}
        {rawMessageError && <Alert type="error" showIcon message="报文加载失败" description={rawMessageError} />}
        {rawMessage && (
          <>
            <Alert
              type={rawMessage.realDirect ? 'success' : 'warning'}
              showIcon
              message={rawMessage.realDirect ? '真实银行直联报文' : '非 REAL 适配器报文'}
              description={rawMessage.realDirect
                ? '该报文由真实银行直联适配器产生，报文体即银行应答的留存（敏感字段已脱敏）。'
                : '该报文不是真实银行直联产生，仅作留档比对，不能作为银行应答证据。'}
            />
            <Descriptions className="projection-detail" column={1} size="small" bordered>
              <Descriptions.Item label="银行请求号"><span className="mono">{displayValue(rawMessage.bankRequestNo)}</span></Descriptions.Item>
              <Descriptions.Item label="接收时间">{dateTime(rawMessage.receivedAt)}</Descriptions.Item>
              <Descriptions.Item label="适配器">{displayValue(rawMessage.adapterCode)}</Descriptions.Item>
              <Descriptions.Item label="报文大小">
                {rawMessage.payloadBytes} 字节
                {rawMessage.responsePayloadBytes ? <>（银行原文 {rawMessage.responsePayloadBytes} 字节）</> : null}
              </Descriptions.Item>
              <Descriptions.Item label="报文摘要"><span className="mono">{displayValue(rawMessage.contentSha256)}</span></Descriptions.Item>
              <Descriptions.Item label="保留期限">{dateTime(rawMessage.retentionUntil)}</Descriptions.Item>
            </Descriptions>
            <Tabs
              defaultActiveKey={rawMessage.responsePayload ? 'raw' : 'view'}
              items={[
                ...(rawMessage.responsePayload ? [{
                  key: 'raw',
                  label: '银行原文',
                  children: <pre className="raw-payload">{prettyPayload(rawMessage.responsePayload)}</pre>,
                }] : []),
                ...(rawMessage.requestEvidence ? [{
                  key: 'evidence',
                  label: '请求要素',
                  children: <pre className="raw-payload">{prettyPayload(rawMessage.requestEvidence)}</pre>,
                }] : []),
                {
                  key: 'view',
                  label: '解析视图',
                  children: <pre className="raw-payload">{rawMessage.payload ? prettyPayload(rawMessage.payload) : '（该报文体已按保留策略清理，仅剩元数据。）'}</pre>,
                },
              ]}
            />
          </>
        )}
      </Drawer>
    </>
  );
}
