import { useCallback, useMemo, useState, type Key } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Modal, Pagination, Select, Space, Spin, Table, Tabs, Tag, Tooltip, Tree, message, type TableColumnsType } from 'antd';
import { DownloadOutlined, FileTextOutlined, PlayCircleOutlined, RobotOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi, bankApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, isUnavailableStatus, isFailedStatus } from '../shared/format';
import { BankProjectionState, COMPANY_COLUMN, statementColumns, balanceColumns, StatementDetail, BalanceDetail, BALANCE_COLUMN_OPTIONS, BALANCE_DEFAULT_HIDDEN, type BankQueryRow, type ColumnFilterPatch } from './BankDataQueryColumns';
import { ColumnSettings } from './ColumnSettings';
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
  /** WP-C Excel 式逐列筛选（服务端参数；表头漏斗与筛选区共用）。 */
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
  // WP-C 余额列设置：勾选存 localStorage（组件在 balances/statements 两条路由间复用，
  // 用 version 触发重读而不是依赖 useState 初始化只执行一次）。
  const [hiddenVersion, setHiddenVersion] = useState(0);
  const hiddenColumns = useMemo(() => {
    if (isStatement) return [];
    try {
      const raw = localStorage.getItem('finflow.bankdata.hidden-columns.balances');
      if (raw) return JSON.parse(raw) as string[];
    } catch { /* 损坏则回默认 */ }
    return BALANCE_DEFAULT_HIDDEN;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isStatement, hiddenVersion]);
  const setHiddenColumns = (next: string[]) => {
    try {
      localStorage.setItem('finflow.bankdata.hidden-columns.balances', JSON.stringify(next));
    } catch { /* 隐私模式等场景忽略持久化失败 */ }
    setHiddenVersion((value) => value + 1);
  };
  // WP-C 表头漏斗筛选：提交值直接并入 filters（即选即查），金额区间为字符串需转数值。
  const onColumnFilter = (patch: ColumnFilterPatch) => applyFilter(patch as Partial<BankQueryFilters>);
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
  const reset = () => { setPage(1); setDraft(emptyBankQueryFilters); setFilters(emptyBankQueryFilters); setSubmitted(false); };
  const setDateFilter = (key: 'from' | 'to', value?: string) => applyFilter({ [key]: value || '' });
  const openDetail = useCallback((row: BankQueryRow) => {
    setFocusReturn(document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setSelected(row);
  }, []);
  const closeDetail = () => {
    setSelected(undefined);
    window.setTimeout(() => focusReturn?.focus(), 0);
  };
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
  const rowSelection = canAiVoucher ? {
    selectedRowKeys: selectedStatementIds,
    onChange: (keys: Key[]) => setSelectedStatementIds(keys.map(Number)),
    getCheckboxProps: (row: BankQueryRow) => ({
      disabled: Boolean((row as BankDataStatementRow).transferred) || manualAccountIds.has(Number((row as BankDataStatementRow).bankAccountId)),
    }),
  } : undefined;
  const exportCsv = async () => {
    setExporting(true);
    try {
      await bankPipelineApi.exportCsv(resource, {
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
      });
      message.success('导出已生成');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
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
  const columns: TableColumnsType<BankQueryRow> = useMemo(() => {
    const base = (isStatement
      ? statementColumns({
          openDetail,
          activeFilters: {
            loanCode: filters.loanCode, counterparty: filters.counterparty, statementNo: filters.statementNo,
            minAmount: filters.minAmount, maxAmount: filters.maxAmount,
          },
          onColumnFilter,
        }) as TableColumnsType<BankQueryRow>
      : balanceColumns({ openDetail }) as TableColumnsType<BankQueryRow>);
    // WP-C：余额页按列设置隐藏；流水页暂全量展示。跨公司权限用户注入「公司主体」列。
    const visible = base.filter((column) => !hiddenColumns.includes(String(column.key)));
    return canCrossCompany ? [COMPANY_COLUMN, ...visible] : visible;
  }, [isStatement, openDetail, canCrossCompany, filters, hiddenColumns, onColumnFilter]);
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
  const tableBlock = (
    <>
      <BankProjectionState data={data} />
      {data?.requestId && <div className="query-request-id">请求编号：<span className="mono">{data.requestId}</span><Link to={`/operations/logs?requestId=${encodeURIComponent(data.requestId)}`}>查看脱敏审计追溯</Link></div>}
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={data?.records || []}
        pagination={false}
        rowSelection={rowSelection}
        locale={{ emptyText: <Empty description={emptyDescription} /> }}
        scroll={{ x: isStatement ? 2000 : 1500 }}
      />
      {data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size} total={data.total} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }} />}
    </>
  );
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">银行数据 / 数据查询{companyName ? ` · ${companyName}` : ''}</span>
          <h2>{definition.title}</h2>
          <p className="muted">{canCrossCompany ? '可跨公司主体查看全部 ACTIVE 公司的银行数据，行内标注归属公司；' : '数据按登录公司主体隔离展示；'}查询读取的是已同步落库的银行数据（不实时请求银行，新数据由每晚自动同步任务或手动补拉获取）；「公司主体」为本系统银行账户档案的归属公司——银行报文只含账号与户名，账户归属由贵司在「账户与主体归档」中维护，行内公司主体列实时跟随账户当前归属（未归属账户标注「未归属」）。直出银行原始字段，本方账号明文展示，完整报文体在「原始报文」模块查看。</p>
        </div>
        {submitted && (
          <Tooltip title={directLinkOff ? '真实银行直联未连接：服务端未启用真实银行适配器，暂无可导出的数据。' : undefined}>
            <Button icon={<DownloadOutlined />} loading={exporting} disabled={directLinkOff} onClick={exportCsv}>导出 CSV</Button>
          </Tooltip>
        )}
        {!isStatement && <ColumnSettings options={BALANCE_COLUMN_OPTIONS} hidden={hiddenColumns} onChange={setHiddenColumns} />}
        {canTriggerSync && <Button icon={<PlayCircleOutlined />} loading={syncTriggering} onClick={triggerSyncFromFilters}>按所选账户创建同步任务</Button>}
      </div>
      <Card className="filter-card">
        <div className="bank-query-grid">
          <Input value={draft.keyword} placeholder="关键字：流水号/摘要/收付方/参考号" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))} />
          <Select
            allowClear
            showSearch
            mode="multiple"
            maxTagCount={2}
            placeholder="账户（不选=全部；跨公司视图按公司分组）"
            value={draft.accountIds.length ? draft.accountIds : undefined}
            options={accountOptions}
            notFoundContent={accounts === undefined ? <Spin size="small" /> : <Empty description={draft.companyId ? '该公司主体下暂无账户' : '当前企业暂无授权账户'} />}
            onChange={(values) => applyFilter({ accountIds: values || [] })}
            onClear={() => applyFilter({ accountIds: [] })}
          />
          {canCrossCompany && (
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="公司主体（全部）"
              value={draft.companyId || undefined}
              options={companyOptions}
              notFoundContent={<Spin size="small" />}
              onChange={(value) => applyFilter({ companyId: value ? String(value) : '' })}
            />
          )}
          {!canCrossCompany && (
            <Tooltip title="跨公司查看需「跨公司银行数据查看」权限，当前仅显示本公司数据；公司主体的增删在「银行数据 → 账户与主体归档」维护（字典中心的「公司主体」即该档案的只读镜像）。">
              <Select disabled placeholder="公司主体（仅本公司）" style={{ minWidth: 160 }} options={[]} />
            </Tooltip>
          )}
          <Input value={draft.accountNoSuffix} placeholder="账号后 4/6 位" style={{ maxWidth: 140 }} onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, accountNoSuffix: event.target.value }))} />
          <Select value={draft.currency || undefined} allowClear placeholder="币种" style={{ minWidth: 110 }} options={CURRENCY_OPTIONS} onChange={(value) => applyFilter({ currency: value || '' })} />
          <DatePicker showTime placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} />
          <DatePicker showTime placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} />
          <Space className="bank-query-actions">
            <Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button>
            <Button onClick={reset}>重置</Button>
          </Space>
        </div>
      </Card>
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
          isStatement ? (
            // WP-C（2026-09-17）：金蝶式左侧主体树——公司主体 → 账户，点选即联动筛选。
            <div style={{ display: 'flex', gap: 16, alignItems: 'stretch' }}>
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
              <div style={{ flex: 1, minWidth: 0 }}>
                {tableBlock}
              </div>
            </div>
          ) : tableBlock
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
