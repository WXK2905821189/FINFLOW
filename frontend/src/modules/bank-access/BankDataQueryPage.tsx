import { useCallback, useMemo, useRef, useState, type Key } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Input, Modal, Pagination, Space, Spin, Table, Tabs, Tag, Tooltip, Tree, message, type TableColumnsType } from 'antd';
import { ApartmentOutlined, FileTextOutlined, PlayCircleOutlined, RobotOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi, bankApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useSubjectScope } from '../../store/scope';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, isUnavailableStatus, isFailedStatus } from '../shared/format';
import { BankProjectionState, StatementDetail, BalanceDetail, type BankQueryRow } from './BankDataQueryColumns';
import { balanceGridColumns, statementGridColumns, decorateStatementRows, decorateBalanceRows } from './BankQueryGridColumns';
import { ExcelGrid } from './grid/ExcelGrid';
import { rawCell, num2, type GridColumn, type GridFilter, type GridInstance, type GridRow } from './grid/kernel';
import { useGridPreference } from './grid/useGridPreference';
import { prettyPayload } from './bankQueryTexts';
import { PromptSettingButton } from '../admin/PromptSettingModal';
import type { BankAccount, CompanyOption, BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow, BankRawMessageDetail, AiVoucherBatchResult, AiVoucherRowResult } from '../../types';

export const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
} as const;

/**
 * V36 D1：查询页以 Excel 内核为核心重做。
 * 工具栏只剩「无法表达为单列筛选」的条件：主体树开关（流水页）+ 时间区间 + 关键字；
 * 其余筛选全部下沉内核列头（values/text/num/date），其中可服务端化的列
 * （见 BankQueryGridColumns 的 filterServer 标注）经 onFilterChange 映射为
 * BankDataExtraFilter 既有参数随请求下发——翻页 / 导出自动同口径。
 */
export type BankQueryFilters = {
  keyword: string;
  /** 多选账户（空数组=全部可见账户）；来自左侧主体树点选。 */
  accountIds: string[];
  from: string;
  to: string;
};

export const emptyBankQueryFilters: BankQueryFilters = { keyword: '', accountIds: [], from: '', to: '' };

/** 列头筛选中可服务端化的部分 → BankDataExtraFilter 参数（与 kernel 的 filterServer 列一一对应）。 */
type ServerFilterParams = {
  accountNoSuffix?: string;
  loanCode?: string;
  counterparty?: string;
  statementNo?: string;
  minAmount?: number;
  maxAmount?: number;
};

/** CSV 单元格：逗号 / 引号 / 换行必须加引号包裹，否则 Excel 会把一行拆成多列。 */
const csvCell = (value: string) => (/[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value);

export function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const companyName = useAuthStore((state) => state.user?.companyName);
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const canViewRawMessage = hasPermission('bankdata:raw:view');
  // 跨公司查看（V24）：仅持有 bankdata:cross-company:view 权限的用户可用顶栏主体切换器。
  const canCrossCompany = hasPermission('bankdata:cross-company:view');
  // V36 D3：主体范围来自顶栏全局切换器（全站生效），不再是页内筛选字段。
  // W8（2026-09-20）多选化：companyIds 数组（空数组=全部主体）。
  const scopeCompanyIds = useSubjectScope((state) => state.companyIds);
  const setScopeCompanies = useSubjectScope((state) => state.setCompanyIds);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [syncTriggering, setSyncTriggering] = useState(false);
  const [filters, setFilters] = useState<BankQueryFilters>(emptyBankQueryFilters);
  const [keywordDraft, setKeywordDraft] = useState('');
  // 流水页左侧主体树开关（V36 工具栏一行化：树默认展开，可收起让位给表格）。
  const [treeOpen, setTreeOpen] = useState(true);
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
  // W9：AI 提示词设置入口（仅超管；提示词全局生效）。
  const canConfigAi = hasPermission('ai:config');
  // 账户数据源：主体树（公司 → 账户）与 AI 制证的 MANUAL 账户判定共用。
  const accountsLoader = useCallback(() => bankApi.accounts(), []);
  const { data: accounts } = useRemote<BankAccount[]>(accountsLoader, [accountsLoader]);
  const companyOptionsLoader = useCallback(() => bankPipelineApi.companyOptions(), []);
  const { data: companyOptionRows } = useRemote<CompanyOption[]>(companyOptionsLoader, [companyOptionsLoader]);
  const companyNameById = useMemo(() => {
    const map = new Map<number, string>();
    (companyOptionRows || []).forEach((option) => map.set(option.id, option.name));
    return map;
  }, [companyOptionRows]);
  // WP-C（2026-09-17）流水查询左侧主体树：公司主体 → 账户。V36 起主体节点点击
  // 联动的是**全站主体范围**（顶栏切换器），账户节点仍是本页筛选。
  const subjectTreeData = useMemo(() => {
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
  }, [accounts, companyNameById, companyName]);
  const treeSelectedKeys = useMemo(() => [
    ...filters.accountIds.map((id) => `account:${id}`),
    ...(canCrossCompany ? scopeCompanyIds.map((id) => `company:${id}`) : []),
  ], [filters.accountIds, canCrossCompany, scopeCompanyIds]);
  const applyFilter = (patch: Partial<BankQueryFilters>) => {
    setPage(1);
    setFilters((current) => ({ ...current, ...patch }));
  };
  // W8 多选树：公司节点可多选（联动顶栏 chips，双向同步）；账户节点维持单选语义
  // （手动同步/详情都按单账户设计），点不同账户 = 切换，点已选账户 = 取消。
  const onTreeSelect = (keys: readonly Key[]) => {
    const companyKeys = keys.filter((k): k is string => typeof k === 'string' && k.startsWith('company:'))
      .map((k) => k.slice('company:'.length))
      .filter((id) => id !== 'unassigned');
    const accountKeys = keys.filter((k): k is string => typeof k === 'string' && k.startsWith('account:'))
      .map((k) => k.slice('account:'.length));
    if (companyKeys.length) {
      // 未归属节点与「全部主体」同效果，不进 companyIds；其余按本次勾选集合整体生效。
      setScopeCompanies(canCrossCompany ? companyKeys : []);
      applyFilter({ accountIds: [] });
      return;
    }
    if (accountKeys.length) {
      applyFilter({ accountIds: [accountKeys[accountKeys.length - 1]] });
      return;
    }
    applyFilter({ accountIds: [] });
  };

  /* ==================================================================
     V35 表格内核接入 + V36 筛选服务端化
     ================================================================== */

  /** 内核实例：导出选中行时按「用户当前实际可见的列」出列，所以必须读实例而不是声明。 */
  const gridInstanceRef = useRef<GridInstance | null>(null);
  const gridColumns = useMemo<GridColumn[]>(
    () => (isStatement ? statementGridColumns({ canCrossCompany }) : balanceGridColumns({ canCrossCompany })),
    [isStatement, canCrossCompany],
  );
  const preferenceScope = isStatement ? 'bankdata.statements' : 'bankdata.balances';
  const { snapshot: gridSnapshot, ready: gridPreferenceReady, save: saveGridPreference } = useGridPreference(preferenceScope);

  // ---- 筛选服务端化（V36 D1 核心）----
  // 内核把「列头筛选集合」整体交回来；页面只把**声明了 filterServer 的列**映射成
  // BankDataExtraFilter 既有参数（与后端语义严格一致），其余列留在内核本地过滤。
  // JSON 去重：renderAll 每次都会给出新引用，值没变就不能触发重新请求。
  const [gridFilters, setGridFilters] = useState<Record<string, GridFilter>>({});
  const gridFiltersJsonRef = useRef('{}');
  const onGridFiltersChange = useCallback((next: Record<string, GridFilter>) => {
    const json = JSON.stringify(next);
    if (json === gridFiltersJsonRef.current) return;
    gridFiltersJsonRef.current = json;
    setGridFilters(next);
  }, []);
  const serverFilterParams = useMemo<ServerFilterParams>(() => {
    const out: ServerFilterParams = {};
    const textOf = (key: string): string => {
      const filter = gridFilters[key];
      return filter && filter.kind === 'text' ? filter.q.trim() : '';
    };
    const singleValueOf = (key: string): string => {
      const filter = gridFilters[key];
      return filter && filter.kind === 'values' && filter.set.length === 1 ? filter.set[0] : '';
    };
    const numRangeOf = (key: string): { min: string; max: string } | undefined => {
      const filter = gridFilters[key];
      return filter && filter.kind === 'num' ? { min: filter.min ?? '', max: filter.max ?? '' } : undefined;
    };
    if (isStatement) {
      const loan = singleValueOf('loanCode');
      if (loan === 'C' || loan === 'D') out.loanCode = loan;
      const counterparty = textOf('counterparty');
      if (counterparty) out.counterparty = counterparty;
      const statementNo = textOf('statementNo');
      if (statementNo) out.statementNo = statementNo;
      const amount = numRangeOf('signedAmount');
      if (amount) {
        if (amount.min !== '') out.minAmount = Number(amount.min);
        if (amount.max !== '') out.maxAmount = Number(amount.max);
      }
      const accountSuffix = textOf('accountLabel');
      if (accountSuffix) out.accountNoSuffix = accountSuffix;
    } else {
      const accountSuffix = textOf('account');
      if (accountSuffix) out.accountNoSuffix = accountSuffix;
    }
    return out;
  }, [gridFilters, isStatement]);

  const loader = useCallback(() => {
    // 首次请求等账号级偏好落定：偏家里存的列头筛选（含服务端列）要先合并进参数，
    // 否则会「先空参查一次、再带偏好查一次」，财务对不上请求流水。
    if (!gridPreferenceReady) {
      return Promise.resolve<BankDataProjectionPage<BankQueryRow>>({ page, size, total: 0, records: [] });
    }
    return bankPipelineApi.queryProjection<BankQueryRow>(resource, {
      page,
      size,
      keyword: filters.keyword || undefined,
      accountIds: filters.accountIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0),
      from: filters.from || undefined,
      to: filters.to || undefined,
      companyIds: canCrossCompany && scopeCompanyIds.length
        ? scopeCompanyIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0)
        : undefined,
      ...serverFilterParams,
    });
  }, [resource, page, size, filters, canCrossCompany, scopeCompanyIds, serverFilterParams, gridPreferenceReady]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage<BankQueryRow>>(loader, [loader]);
  // 借贷双轨派生字段必须在灌数据前写进行对象：内核排序 / 区间筛选 / 值勾选 / TSV 复制
  // 都直接读 row[col.k]，派生列没有真实字段就是空的。这里 memo 住，避免每次渲染换新数组
  // 触发 setRows → 行勾选被清空。
  const gridRows = useMemo<GridRow[]>(() => {
    const records = data?.records;
    if (!records) return [];
    return isStatement
      ? decorateStatementRows(records as BankDataStatementRow[])
      : decorateBalanceRows(records as BankDataBalanceRow[]);
  }, [data, isStatement]);

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
  // 导出 = 当前查询条件的全量结果（口径④）：关键字 / 账户 / 时间 / 主体范围 / 服务端列筛选
  // 全部随请求下发，翻页与导出永远同口径。
  // W8：CSV 布局镜像银行单文件（无公司列），多主体混导会破坏与银行文件逐列对账——
  // 单选主体时随请求下发该主体；多选时导出按钮置灰提示按主体分别导出（后端同样 400 兜底）。
  const exportQuery = useMemo(() => ({
    keyword: filters.keyword || undefined,
    accountIds: filters.accountIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0),
    from: filters.from || undefined,
    to: filters.to || undefined,
    companyId: canCrossCompany && scopeCompanyIds.length === 1 ? Number(scopeCompanyIds[0]) : undefined,
    ...serverFilterParams,
  }), [filters, canCrossCompany, scopeCompanyIds, serverFilterParams]);
  const multiCompanyExportBlocked = canCrossCompany && scopeCompanyIds.length > 1;
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
  /** 导出按钮的统一入口：直连未启用时导出必然 503，先在按钮层给明确原因，别让用户白等一次失败。
   *  W8：多选主体时提示按主体分别导出（CSV 无公司列，混导无法与银行单文件对账）。 */
  const exportAll = () => {
    if (directLinkOff) {
      message.warning('真实银行直联未连接，服务端暂无可导出的数据');
      return;
    }
    if (multiCompanyExportBlocked) {
      message.warning('当前勾选了多个主体：CSV 与银行单文件逐列对账，请通过顶栏切换器选择单个主体后分别导出');
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
    // 手动同步仍是单账户语义：主体树点选的账户；多账户补拉请用「同步任务 → 补拉历史数据」。
    const accountId = Number(filters.accountIds[0]);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      message.warning('请先在左侧主体树中选择要同步的银行账户');
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
            windowStart: filters.from || undefined,
            windowEnd: filters.to || undefined,
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

  const openDetail = useCallback((row: BankQueryRow) => {
    setFocusReturn(document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setSelected(row);
  }, []);
  const closeDetail = () => {
    setSelected(undefined);
    window.setTimeout(() => focusReturn?.focus(), 0);
  };

  /** 工具栏左侧：主体树开关（流水页）+ 时间区间 + 关键字。列设置 / 视图 / 导出在内核工具栏右侧。 */
  const toolbarStart = (
    <>
      {isStatement && (
        <div className="field">
          <label>主体树</label>
          <Button
            icon={<ApartmentOutlined />}
            type={treeOpen ? 'primary' : 'default'}
            ghost={treeOpen}
            onClick={() => setTreeOpen((current) => !current)}
          >
            {treeOpen ? '已展开' : '已收起'}
          </Button>
        </div>
      )}
      <div className="field">
        <label>时间区间</label>
        <DatePicker.RangePicker
          showTime
          allowEmpty={[true, true]}
          style={{ width: 336 }}
          value={[filters.from ? dayjs(filters.from) : null, filters.to ? dayjs(filters.to) : null]}
          onChange={(range) => applyFilter({ from: range?.[0]?.toISOString() || '', to: range?.[1]?.toISOString() || '' })}
        />
      </div>
      <div className="field">
        <label>关键字</label>
        <Input
          style={{ width: 196 }}
          allowClear
          value={keywordDraft}
          placeholder="流水号 / 摘要 / 收付方"
          suffix={<SearchOutlined style={{ color: '#b9c8cc' }} />}
          onChange={(event) => {
            const value = event.target.value;
            setKeywordDraft(value);
            if (value === '') applyFilter({ keyword: '' });
          }}
          onPressEnter={() => applyFilter({ keyword: keywordDraft.trim() })}
        />
      </div>
    </>
  );

  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">银行数据 / 数据查询{companyName ? ` · ${companyName}` : ''}</span>
          <h2>{definition.title}</h2>
          <p className="muted">
            数据为已同步落库的真实银行数据（不实时请求银行；新数据由每晚自动同步或手动补拉获取）。
            逐列筛选在表头漏斗里，标【全量】的列同时作用于翻页与导出；{canCrossCompany ? '数据范围由顶栏主体切换器决定；' : ''}
            完整报文体在「原始报文」模块查看。
          </p>
        </div>
        {canTriggerSync && <Button icon={<PlayCircleOutlined />} loading={syncTriggering} onClick={triggerSyncFromFilters}>按所选账户创建同步任务</Button>}
      </div>
      <Card
        title={canAiVoucher
          ? <Space wrap>
              <span>查询结果</span>
              <Button size="small" type="primary" icon={<RobotOutlined />} disabled={!selectedStatementIds.length} loading={aiVoucherRunning} onClick={() => aiVoucherSelected('DRAFT')}>AI 制证为草稿{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button>
              <Button size="small" type="primary" ghost icon={<ThunderboltOutlined />} disabled={!selectedStatementIds.length} loading={aiVoucherRunning} onClick={() => aiVoucherSelected('PUSH')}>AI 制证并推送{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button>
              {canConfigAi && <PromptSettingButton capability="accounting-suggestion" hint="设置「智能入账建议」的系统提示词（全局生效，仅超管）" />}
              <span className="muted">草稿：AI 预填后在「凭证草稿与制证」页人工审核推送；推送：复核内化后直送金蝶。行首方框勾选要制证的流水；已推送行与纯人工制证账户不可选</span>
            </Space>
          : '查询结果'}
      >
        {error ? <ResourceFailure error={error} onRetry={reload} /> : (
          <div style={isStatement && treeOpen ? { display: 'flex', gap: 16, alignItems: 'stretch' } : undefined}>
            {isStatement && treeOpen && (
              // WP-C（2026-09-17）金蝶式左侧主体树；V36 起主体节点联动顶栏全局主体范围。
              <div style={{ width: 260, flexShrink: 0, borderRight: '1px solid #f0f0f0', paddingRight: 12, overflow: 'auto', maxHeight: 680 }}>
                <div className="muted" style={{ marginBottom: 8 }}>
                  公司主体 / 账户
                  {canCrossCompany && (
                    <span className="table-sub">（范围：{scopeCompanyIds.length === 0
                      ? '全部主体'
                      : scopeCompanyIds.length === 1
                        ? (companyNameById.get(Number(scopeCompanyIds[0])) || `主体 #${scopeCompanyIds[0]}`)
                        : `${scopeCompanyIds.length} 个主体`}）</span>
                  )}
                </div>
                {subjectTreeData.length ? (
                  <Tree
                    blockNode
                    multiple
                    defaultExpandAll
                    selectedKeys={treeSelectedKeys}
                    onSelect={onTreeSelect}
                    treeData={subjectTreeData}
                  />
                ) : <Spin size="small" />}
                <p className="muted" style={{ fontSize: 12, marginTop: 8 }}>点选主体＝加入/移出全站主体范围（可多选，顶栏同步生效）；点选账户＝本页账户筛选。再次点击取消。</p>
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
                toolbarStart={toolbarStart}
                groupBy={gridGroupBy}
                groupedDefault={isStatement ? false : canCrossCompany}
                showGroupSwitch={isStatement || canCrossCompany}
                groupSwitchLabel={isStatement ? '按本方账户分组' : '按主体分组'}
                groupMeta={gridGroupMeta}
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
                emptyText={loading && !data ? '正在加载……' : emptyDescription}
                preferenceReady={gridPreferenceReady}
                initialSnapshot={gridSnapshot}
                onSnapshotChange={saveGridPreference}
                onFilterChange={onGridFiltersChange}
                toast={(text) => message.success(text)}
                footer={data ? (
                  <Pagination
                    className="table-pagination"
                    current={data.page}
                    pageSize={data.size || size}
                    total={data.total}
                    showSizeChanger
                    pageSizeOptions={[10, 20, 50]}
                    onChange={(next, nextSize) => { setPage(next); setSize(nextSize); }}
                  />
                ) : undefined}
              />
            </div>
          </div>
        )}
      </Card>
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
