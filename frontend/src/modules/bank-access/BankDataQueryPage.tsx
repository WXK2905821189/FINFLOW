import { useCallback, useMemo, useState, type Key } from 'react';
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Modal, Pagination, Select, Space, Spin, Table, Tabs, message, type TableColumnsType } from 'antd';
import { DownloadOutlined, FileTextOutlined, PlayCircleOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link } from 'react-router-dom';
import { bankPipelineApi, bankApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { syncStatusOptions } from '../shared/dict';
import { dateTime, displayValue, isUnavailableStatus, isFailedStatus } from '../shared/format';
import { BankProjectionState, COMPANY_COLUMN, statementColumns, balanceColumns, StatementDetail, BalanceDetail, type BankQueryRow } from './BankDataQueryColumns';
import { BANK_NAME_TEXT, prettyPayload } from './bankQueryTexts';
import type { BankAccount, CompanyOption, BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow, BankRawMessageDetail } from '../../types';

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
};

export const emptyBankQueryFilters: BankQueryFilters = { keyword: '', accountIds: [], status: '', sourceSystem: '', syncJobNo: '', requestId: '', from: '', to: '', companyId: '' };

export function BankDataQueryPage({ resource }: { resource: keyof typeof bankDataResources }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const companyName = useAuthStore((state) => state.user?.companyName);
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const canViewRawMessage = hasPermission('bankdata:raw:view');
  // 跨公司查看（V24）：仅持有 bankdata:cross-company:view 权限的用户渲染公司下拉与公司列。
  const canCrossCompany = hasPermission('bankdata:cross-company:view');
  const companyOptionsLoader = useCallback(() => bankPipelineApi.companyOptions(), []);
  const { data: companyOptions } = useRemote<CompanyOption[]>(companyOptionsLoader, [companyOptionsLoader]);
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
  // 一键转入（C1）：流水 tab 专属，权限与「导入流水」一致（statement:import）。
  const canTransferStatement = isStatement && hasPermission('statement:import');
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
  const loader = useCallback(() => submitted ? bankPipelineApi.queryProjection<BankQueryRow>(resource, { page, size, keyword: filters.keyword || undefined, accountIds: filters.accountIds.map(Number).filter((id) => Number.isSafeInteger(id) && id > 0), status: filters.status || undefined, from: filters.from || undefined, to: filters.to || undefined, sourceSystem: filters.sourceSystem || undefined, syncJobNo: filters.syncJobNo || undefined, requestId: filters.requestId || undefined, companyId: filters.companyId ? Number(filters.companyId) : undefined }) : Promise.resolve<BankDataProjectionPage<BankQueryRow>>({ page, size, total: 0, records: [] }), [resource, page, size, filters, submitted]);
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
  // 一键转入（C1）：行多选 → 转入标准流水；已转入行禁选（服务端幂等键=同公司同银行流水号）。
  const [selectedStatementIds, setSelectedStatementIds] = useState<number[]>([]);
  const [transferring, setTransferring] = useState(false);
  const transferSelected = () => {
    if (!selectedStatementIds.length) {
      message.warning('请先勾选要转入的银行流水行');
      return;
    }
    Modal.confirm({
      title: `确认转入 ${selectedStatementIds.length} 条银行流水`,
      content: '转入后在「流水与入账」生成标准流水（保留人工复核与制证闸门）；重复转入会被服务端自动去重。',
      okText: '确认转入',
      cancelText: '取消',
      onOk: async () => {
        setTransferring(true);
        try {
          const result = await bankPipelineApi.transferFromBankdata({ statementIds: selectedStatementIds });
          message.success(`转入完成：新增 ${result.importedCount ?? 0} 条，重复跳过 ${result.duplicateCount ?? 0} 条（批次 ${result.batchNo}）`);
          setSelectedStatementIds([]);
          reload();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '转入失败，请稍后重试');
        } finally {
          setTransferring(false);
        }
      },
    });
  };
  const rowSelection = canTransferStatement ? {
    selectedRowKeys: selectedStatementIds,
    onChange: (keys: Key[]) => setSelectedStatementIds(keys.map(Number)),
    getCheckboxProps: (row: BankQueryRow) => ({ disabled: Boolean((row as BankDataStatementRow).transferred) }),
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
      ? statementColumns((row) => openDetail(row)) as TableColumnsType<BankQueryRow>
      : balanceColumns((row) => openDetail(row)) as TableColumnsType<BankQueryRow>);
    // 跨公司权限用户注入「公司主体」列；单公司用户所有行同属一家，不占列宽。
    return canCrossCompany ? [COMPANY_COLUMN, ...base] : base;
  }, [isStatement, openDetail, canCrossCompany]);
  const definition = bankDataResources[resource];
  const emptyDescription = data?.enabled === false || isUnavailableStatus(data?.status) ? '真实银行直联未连接，无法获取数据。' : isFailedStatus(data?.status) ? '银行查询失败，请检查同步任务。' : '当前筛选没有匹配的真实银行数据。';
  const detailRequestId = isStatement
    ? (selected as BankDataStatementRow | undefined)?.taskRequestId
    : (selected as BankDataBalanceRow | undefined)?.taskRequestId;
  const detailTitle = selected
    ? (isStatement ? `银行流水字段 · ${(selected as BankDataStatementRow).statementNo || selected.id}` : `银行余额字段 · ${selected.id}`)
    : '银行字段明细';
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">银行数据 / 数据查询{companyName ? ` · ${companyName}` : ''}</span>
          <h2>{definition.title}</h2>
          <p className="muted">{canCrossCompany ? '可跨公司主体查看全部 ACTIVE 公司的银行数据，行内标注归属公司；' : '数据按登录公司主体隔离展示；'}查询读取的是已同步落库的银行数据（不实时请求银行，新数据由每晚自动同步任务或手动补拉获取）；「公司主体」为本系统银行账户档案的归属公司——银行报文只含账号与户名，账户归属由贵司在「银行账户」中维护，新增分公司/子公司账户后即可在此分开查看。直出银行原始字段，本方账号脱敏，完整报文体在「原始报文」模块查看。</p>
        </div>
        {submitted && <Button icon={<DownloadOutlined />} loading={exporting} onClick={exportCsv}>导出 CSV</Button>}
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
          <Select value={draft.status || undefined} allowClear placeholder="任务状态" style={{ minWidth: 130 }} options={syncStatusOptions} onChange={(value) => applyFilter({ status: value || '' })} />
          <Input value={draft.sourceSystem} placeholder="来源（真实数据为 BANKDATA）" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, sourceSystem: event.target.value }))} />
          <Input value={draft.syncJobNo} placeholder="任务号" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, syncJobNo: event.target.value }))} />
          <Input value={draft.requestId} placeholder="请求编号" onPressEnter={query} onChange={(event) => setDraft((current) => ({ ...current, requestId: event.target.value }))} />
          <DatePicker showTime placeholder="开始时间" value={draft.from ? dayjs(draft.from) : undefined} onChange={(value) => setDateFilter('from', value?.toISOString())} />
          <DatePicker showTime placeholder="结束时间" value={draft.to ? dayjs(draft.to) : undefined} onChange={(value) => setDateFilter('to', value?.toISOString())} />
          <Space className="bank-query-actions">
            <Button type="primary" icon={<SearchOutlined />} onClick={query}>查询</Button>
            <Button onClick={reset}>重置</Button>
          </Space>
        </div>
      </Card>
      <Card
        title={canTransferStatement && submitted
          ? <Space wrap><span>查询结果</span><Button size="small" type="primary" ghost icon={<ThunderboltOutlined />} disabled={!selectedStatementIds.length} loading={transferring} onClick={transferSelected}>转入流水与入账{selectedStatementIds.length ? `（${selectedStatementIds.length}）` : ''}</Button><span className="muted">已转入行不可再选；转入后保留人工复核与制证闸门</span></Space>
          : '查询结果'}
      >
        {error ? <ResourceFailure error={error} onRetry={reload} /> : !submitted && !loading ? <Empty description="设置筛选条件后点击查询；没有默认或浏览器生成的数据。" /> : (
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
              scroll={{ x: isStatement ? 1900 : 1900 }}
            />
            {data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size} total={data.total} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(next, nextSize) => { setPage(next); setSize(nextSize); setSubmitted(true); }} />}
          </>
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
        {rawMessageLoading && <div className="raw-message-loading"><Spin tip="正在加载报文……" /></div>}
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
