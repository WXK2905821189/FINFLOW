import { useCallback, useMemo, useRef, useState, type Key } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Input, Modal, Pagination, Space, Spin, Tabs, Tag, Tooltip, Tree, message } from 'antd';
import { ApartmentOutlined, FileTextOutlined, PlayCircleOutlined, SearchOutlined, SendOutlined } from '@ant-design/icons';
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
import { rawCell, num2, type GridColumn, type GridFilter, type GridInstance, type GridRow, type GridSortSpec } from './grid/kernel';
import { useGridPreference } from './grid/useGridPreference';
import { prettyPayload } from './bankQueryTexts';
import { useBankNames, bankAccountLabel } from './useBankNames';
import type { BankAccount, CompanyOption, BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow, BankRawMessageDetail } from '../../types';

export const bankDataResources = {
  balances: { title: '余额查询', permission: 'bankdata:balance:view' },
  statements: { title: '流水查询', permission: 'bankdata:statement:view' },
} as const;

/**
 * V36 D1：查询页以 Excel 内核为核心重做。
 * 工具栏只剩「无法表达为单列筛选」的条件：主体树开关（流水页）+ 时间筛选 + 关键字；
 * 其余筛选全部下沉内核列头（values/text/num/date），其中可服务端化的列
 * （见 BankQueryGridColumns 的 filterServer 标注）经 onFilterChange 映射为
 * BankDataExtraFilter 既有参数随请求下发——翻页 / 导出自动同口径。
 * W16-B4（2026-09-21）：余额页时间筛选改「时间节点」语义——from/to 同给 = 以 to 为节点上限
 * 查看历史快照；都不传 = 每账户最新节点（当前时点快照，后端 latestPerAccount 自动启用）。
 * 流水页时间筛选仍是区间（交易时间天然是流水维度）。
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
  // W16-A1 一键推送（2026-09-21）：流水 tab 专属，终局闸门 voucher:push。
  const canPush = isStatement && hasPermission('voucher:push');
  // 银行中文名（2026-09-21）：字典中心 `bank` 类型为唯一可维护源，代码常量仅兜底。
  // bankRevision 放进列/行/树的 memo 依赖，字典异步到达后自动重建（内核 setCols 按 key
  // 保留用户的可见性与列宽，不会冲掉调好的表格）。
  const { revision: bankRevision, resolve: resolveBankName } = useBankNames();
  // 账户数据源：主体树与推送的 MANUAL 账户判定共用。
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
        children: list.map((account) => {
          // 2026-09-21 用户口径：账户节点显示「银行-尾号」（如「中信银行-4821」），
          // 银行名来自字典中心（新增银行自动跟随，无需改代码）；账户名与完整掩码账号
          // 移到悬浮提示，既缩短节点又不丢原有的「户名」信息。
          const label = bankAccountLabel(account.bankCode, account.maskedAccountNumber) || account.accountName;
          return {
            key: `account:${account.id}`,
            title: <Tooltip title={`${account.accountName}（${account.maskedAccountNumber}）`}>{label}</Tooltip>,
          };
        }),
      };
    });
  }, [accounts, companyNameById, companyName, bankRevision]);
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
    () => (isStatement
      ? statementGridColumns({ canCrossCompany })
      : balanceGridColumns({ canCrossCompany, bankNameOf: resolveBankName })),
    [isStatement, canCrossCompany, resolveBankName, bankRevision],
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
  // W16-B5 排序服务端化：内核把排序规格交回来；页面把 sortServer 列（交易时间）映射成
  // sortDir 请求参数（与后端枚举严格一致）。空数组 = 用户取消了排序 → 不传参走服务端默认
  // （transactionTime desc + id desc，即「最新在前」的既有口径）。
  const [gridSort, setGridSort] = useState<GridSortSpec[]>([]);
  const gridSortJsonRef = useRef('[]');
  const onGridSortChange = useCallback((next: GridSortSpec[]) => {
    const json = JSON.stringify(next);
    if (json === gridSortJsonRef.current) return;
    gridSortJsonRef.current = json;
    setGridSort(next);
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

  // W16-B5：交易时间排序方向（仅流水页消费；余额页排序仍是本页口径）。
  // 只认交易时间列——内核对混合排序已整体放行服务端序，此处对不上列名时忽略。
  const sortDirParam = useMemo<'asc' | 'desc' | undefined>(() => {
    if (!isStatement) return undefined;
    const spec = gridSort.find((s) => s.k === 'transactionTime');
    if (!spec) return undefined;
    return spec.dir === 1 ? 'asc' : 'desc';
  }, [gridSort, isStatement]);

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
      // W16-B4 余额节点语义：from 恒不传（余额页没有「从某时刻起」的语义）；
      // to 传 = 查看该时点之前的最近快照；to 不传 = 后端 latestPerAccount 每账户取最新节点。
      // 流水页维持区间（from/to 独立可空，后端 .ge/.le 原生兼容）。
      from: isStatement ? (filters.from || undefined) : undefined,
      to: filters.to || undefined,
      companyIds: canCrossCompany && scopeCompanyIds.length
        ? scopeCompanyIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0)
        : undefined,
      ...serverFilterParams,
      sortDir: sortDirParam,
    });
  }, [resource, page, size, filters, canCrossCompany, scopeCompanyIds, serverFilterParams, sortDirParam, isStatement, gridPreferenceReady]);
  const { data, loading, error, reload } = useRemote<BankDataProjectionPage<BankQueryRow>>(loader, [loader]);
  // 借贷双轨派生字段必须在灌数据前写进行对象：内核排序 / 区间筛选 / 值勾选 / TSV 复制
  // 都直接读 row[col.k]，派生列没有真实字段就是空的。这里 memo 住，避免每次渲染换新数组
  // 触发 setRows → 行勾选被清空。
  const gridRows = useMemo<GridRow[]>(() => {
    const records = data?.records;
    if (!records) return [];
    return isStatement
      ? decorateStatementRows(records as BankDataStatementRow[])
      : decorateBalanceRows(records as BankDataBalanceRow[], resolveBankName);
  }, [data, isStatement, resolveBankName, bankRevision]);

  const gridGroupBy = isStatement ? 'accountMasked' : 'companyName';
  const gridGroupMeta = useMemo(() => {
    if (isStatement) {
      return (group: string, list: GridRow[]) => {
        const debit = list.reduce((sum, row) => sum + (Number(row.debitAmount) || 0), 0);
        const credit = list.reduce((sum, row) => sum + (Number(row.creditAmount) || 0), 0);
        return `${group ? '' : '未标注账户 · '}${list.length} 笔 · 借 <b>¥ ${num2(debit)}</b> / 贷 <b>¥ ${num2(credit)}</b>`;
      };
    }
    // 余额查询的分组汇总（2026-09-21 用户口径修正）：**不再显示「可用余额合计」**——
    // 同一账户在筛选区间内可能有多天余额，直接求和属重复计入；余额还可能是多币种，不可相加。
    // 账户数改按账户去重：原先用 list.length 实为行数，同一账户多天会被算成多个账户。
    return (group: string, list: GridRow[]) => {
      const accounts = new Set(
        list.map((row) => String(row.bankAccountId ?? row.accountMasked ?? row.bankAccountNo ?? '')).filter(Boolean),
      );
      const accountCount = accounts.size || list.length;
      const latest = list.map((row) => String(row.asOfTime || '')).sort().pop() || '—';
      return `${accountCount} 个账户 · 最近截止 ${latest.slice(0, 16).replace('T', ' ') || '—'}`;
    };
  }, [isStatement]);

  const [exporting, setExporting] = useState(false);
  // W16-A1 一键推送（2026-09-21）：行多选 → 规则引擎自动匹配推送；MANUAL 制证模式账户的行禁选；
  // 已推送/已转入行禁选（服务端幂等键=同公司同银行流水号）。
  const manualAccountIds = useMemo(() => new Set(
    (accounts || []).filter((account) => account.accountingMode === 'MANUAL').map((account) => account.id),
  ), [accounts]);
  const [selectedStatementIds, setSelectedStatementIds] = useState<number[]>([]);
  const [pushSubmitting, setPushSubmitting] = useState(false);
  // W16-A1 单按钮单路：提交异步批任务立即返回，结果摘要（自动推 X / 问题凭证 Y / 跳过 Z）
  // 在「凭证中心」的推送任务横幅查看，不在本页弹窗。
  const pushSelected = () => {
    if (!selectedStatementIds.length) {
      message.warning('请先勾选要推送的银行流水行');
      return;
    }
    Modal.confirm({
      title: `确认一键推送 ${selectedStatementIds.length} 条银行流水至金蝶`,
      content: '流程：转入标准流水（幂等）→ 按规则中心匹配 → 唯一命中的自动组装并推送金蝶草稿；'
        + '多候选、未命中或需人工定金额的流水将生成「问题凭证」，在凭证中心人工处理。'
        + '提交后后台执行，可离开本页；结果摘要见「凭证中心」推送任务横幅。',
      okText: '确认推送',
      cancelText: '取消',
      onOk: async () => {
        const ids = selectedStatementIds;
        setSelectedStatementIds([]);
        setPushSubmitting(true);
        try {
          const submitted = await bankPipelineApi.pushToKingdee({ statementIds: ids });
          message.success({
            content: `已提交 ${submitted.totalCount} 条推送任务（任务号 #${submitted.jobId ?? '--'}），处理中，结果摘要在「凭证中心」查看`,
            duration: 6,
          });
          reload();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '推送任务提交失败');
        } finally {
          setPushSubmitting(false);
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
    // W16-B4：余额导出与屏幕同口径 —— from 不传（节点语义），to 传 = 该时点快照。
    from: isStatement ? (filters.from || undefined) : undefined,
    to: filters.to || undefined,
    companyId: canCrossCompany && scopeCompanyIds.length === 1 ? Number(scopeCompanyIds[0]) : undefined,
    ...serverFilterParams,
    // W16-B5：导出与屏幕查询同口径 —— 屏幕上按交易时间升序排，CSV 行序也升序。
    sortDir: sortDirParam,
  }), [filters, canCrossCompany, scopeCompanyIds, serverFilterParams, sortDirParam, isStatement]);
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
        <label>{isStatement ? '时间区间' : '时间节点'}</label>
        {isStatement ? (
          <DatePicker.RangePicker
            showTime
            allowEmpty={[true, true]}
            style={{ width: 336 }}
            value={[filters.from ? dayjs(filters.from) : null, filters.to ? dayjs(filters.to) : null]}
            onChange={(range) => applyFilter({ from: range?.[0]?.toISOString() || '', to: range?.[1]?.toISOString() || '' })}
          />
        ) : (
          // W16-B4：余额是「某一时点的快照」，不是流量——区间语义会重复计入同一账户的多天余额。
          // 节点选择：不选 = 每账户最新节点（当前时点）；选了 = 查看该时点之前的最近一次快照。
          <DatePicker
            showTime
            allowClear
            style={{ width: 336 }}
            placeholder="不选 = 最新时间节点"
            value={filters.to ? dayjs(filters.to) : null}
            onChange={(d) => applyFilter({ from: '', to: d ? d.toISOString() : '' })}
          />
        )}
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
        title={canPush
          ? <Space wrap>
              <span>查询结果</span>
              <Button size="small" type="primary" icon={<SendOutlined />} disabled={!selectedStatementIds.length} loading={pushSubmitting} onClick={pushSelected}>一键推送至金蝶{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button>
              <span className="muted">勾选要推送的流水后提交：规则唯一命中的自动推金蝶；多候选/未命中/需定金额的进「问题凭证」，在凭证中心人工处理。已推送行与纯人工制证账户不可选</span>
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
                /* 2026-09-21：分组默认开启（两个 tab 一致）——用户认可分组行上的汇总
                   「M 个账户 · 最近截止 …」/「N 笔 · 借 X / 贷 Y」，
                   但它原先在流水页默认关、余额页还依赖跨公司权限，导致「时有时无」。
                   这里统一默认开启；仍可点「按…分组」开关关掉。
                   （2026-09-21 二次修正：余额侧去掉「可用余额合计」——同账户多天求和是重复计入；
                   账户数同时改为按账户去重。）
                   注：**不**把这套汇总搬进状态栏——W8（2026-09-20）已决定状态栏不再显示
                   本页金额小计，避免被误当成全量合计；分组行的汇总有分组标签限定口径，不冲突。 */
                groupedDefault
                showGroupSwitch={isStatement || canCrossCompany}
                groupSwitchLabel={isStatement ? '按本方账户分组' : '按主体分组'}
                groupMeta={gridGroupMeta}
                selectable={canPush}
                isRowSelectable={isStatement
                  ? (row) => !row.transferred && !manualAccountIds.has(Number(row.bankAccountId))
                  : undefined}
                disabledRowHint={isStatement
                  ? (row) => (row.transferred ? '该行已转入标准流水（已推送或已生成凭证），不能重复推送' : '该账户为纯人工制证模式，不能自动推送')
                  : undefined}
                rowClass={isStatement ? (row) => (row.transferred ? 'is-locked' : '') : undefined}
                onSelectionChange={canPush
                  ? (picked) => setSelectedStatementIds(picked.map((row) => Number(row.id)))
                  : undefined}
                onRowAction={(action, row) => { if (action === 'detail') openDetail(row as BankQueryRow); }}
                onExport={exportAll}
                onExportRows={directLinkOff ? undefined : exportSelectedRows}
                exportLabel={exporting ? '导出中…' : '导出 CSV'}
                exportBadge={<Tag color="green" style={{ height: 18, lineHeight: '16px', fontSize: 11 }}>筛选全量 {data?.total ?? 0} 行</Tag>}
                findPlaceholder={isStatement ? 'Ctrl+F 流水号 / 收付方' : 'Ctrl+F 账号 / 户名'}
                emptyText={filters.keyword
                  ? `没有匹配「${filters.keyword}」的银行数据。`
                  : loading && !data ? '正在加载……' : emptyDescription}
                preferenceReady={gridPreferenceReady}
                initialSnapshot={gridSnapshot}
                onSnapshotChange={saveGridPreference}
                onFilterChange={onGridFiltersChange}
                onSortChange={onGridSortChange}
                toast={(text) => message.success(text)}
                footer={data ? (
                  <Pagination
                    className="table-pagination"
                    current={data.page}
                    pageSize={data.size || size}
                    total={data.total}
                    showSizeChanger
                    pageSizeOptions={[10, 20, 50, 100]}
                    onChange={(next, nextSize) => { setPage(next); setSize(nextSize); }}
                  />
                ) : undefined}
              />
            </div>
          </div>
        )}
      </Card>
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
