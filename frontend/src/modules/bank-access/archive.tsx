import { useState, type CSSProperties, type DragEvent } from 'react';
import { Alert, Badge, Button, Descriptions, Drawer, Empty, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, message, type TableColumnsType } from 'antd';
import { ApiOutlined, DeleteOutlined, EditOutlined, FolderAddOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons';
import { bankApi } from '../../services/api';
import { ApiRequestError } from '../../services/http';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime } from '../shared/format';
import { useBankNames } from './useBankNames';
import { PromptSettingButton } from '../admin/PromptSettingModal';
import type { AiCompanyApplyResponse, AiCompanyApplyRow, AiCompanySuggestion, BankAccountCreatePayload, BankConnectionTestResult, CompanyArchiveAccount, CompanyArchiveCompany, CompanyArchiveView } from '../../types';

/**
 * 拖拽式账户档案管理：公司档案是投放区，账户卡片拖到目标公司上松手完成归类。
 * 原生 HTML5 drag & drop，不引入第三方拖拽库；历史流水/余额由服务端一并迁移。
 * 外层 Drawer destroyOnHidden：每次打开重新挂载档案板并加载数据。
 */
export function CompanyArchiveDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Drawer
      title="账户档案管理"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {open ? <ArchiveBoard /> : null}
    </Drawer>
  );
}

function ArchiveBoard() {
  const { data: view, loading, error, reload } = useRemote<CompanyArchiveView>(() => bankApi.archive(), []);
  // 银行中文名统一取字典中心（bank 字典类型）；档案下拉的候选集也随之自动扩展。
  const { resolve: resolveBankName, options: bankOptions } = useBankNames();
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canManage = hasPermission('bank:manage');
  // 测试连接会发起一次真实银行调用（只读不落库），与手动同步同级管控。
  const canTest = canManage || hasPermission('bankdata:sync:trigger');
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const [dragOverId, setDragOverId] = useState<number | 'unassigned'>();
  const [renaming, setRenaming] = useState<CompanyArchiveCompany>();
  const [renameValue, setRenameValue] = useState('');
  const [renamingBusy, setRenamingBusy] = useState(false);
  const [deletingCompanyId, setDeletingCompanyId] = useState<number>();
  // 409 引用检查占用清单：响应原文展示，用户据此先清理引用再删主体。
  const [deleteConflict, setDeleteConflict] = useState<{ company: CompanyArchiveCompany; detail: string }>();
  const [createAcctOpen, setCreateAcctOpen] = useState(false);
  const [creatingAcct, setCreatingAcct] = useState(false);
  const emptyAcctDraft: BankAccountCreatePayload = { bankCode: 'CITIC', accountName: '', accountNumber: '', currency: 'CNY', availableBalance: 0, status: 'ACTIVE' };
  const [acctDraft, setAcctDraft] = useState<BankAccountCreatePayload>(emptyAcctDraft);
  const [kingdeeCode, setKingdeeCode] = useState('');
  const [testingId, setTestingId] = useState<number>();
  const [testResult, setTestResult] = useState<{ accountName: string; result: BankConnectionTestResult }>();
  // AI 智能归类（V32）：建议 → 预览（公司名可编辑/行可勾选）→ 批量应用。
  const canAi = canManage && hasPermission('ai:use');
  // W9：AI 提示词设置入口（仅超管）。
  const canConfigAi = hasPermission('ai:config');
  const [aiSuggesting, setAiSuggesting] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);
  const [aiSuggestions, setAiSuggestions] = useState<AiCompanySuggestion[]>([]);
  const [aiEdits, setAiEdits] = useState<Record<number, string>>({});
  const [aiSelected, setAiSelected] = useState<number[]>([]);
  const [aiApplying, setAiApplying] = useState(false);
  const [aiApplyResult, setAiApplyResult] = useState<AiCompanyApplyResponse>();

  const createCompany = async () => {
    const name = newName.trim();
    if (!name || creating) return;
    setCreating(true);
    try {
      await bankApi.createArchiveCompany(name);
      message.success(`公司档案「${name}」已创建`);
      setNewName('');
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '未能创建公司档案');
    } finally {
      setCreating(false);
    }
  };

  const renameCompany = async () => {
    if (!renaming) return;
    const name = renameValue.trim();
    if (!name || renamingBusy) return;
    setRenamingBusy(true);
    try {
      await bankApi.renameArchiveCompany(renaming.id, name);
      message.success('公司档案已更新');
      setRenaming(undefined);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '未能更新公司档案');
    } finally {
      setRenamingBusy(false);
    }
  };

  const deleteCompany = async (company: CompanyArchiveCompany) => {
    if (deletingCompanyId) return;
    setDeletingCompanyId(company.id);
    try {
      await bankApi.deleteArchiveCompany(company.id);
      message.success(`公司主体「${company.name}」已停用`);
      await reload();
    } catch (reason) {
      const detail = reason instanceof Error ? reason.message : '';
      if (reason instanceof ApiRequestError && reason.status === 409) {
        // 409：Modal 完整展示服务端占用清单（活跃账户/用户/流水/余额/规则引用各计数）。
        setDeleteConflict({ company, detail });
      } else {
        message.error(detail || '公司主体删除失败');
      }
    } finally {
      setDeletingCompanyId(undefined);
    }
  };

  const submitAccount = async () => {
    if (creatingAcct) return;
    const name = acctDraft.accountName.trim();
    const number = acctDraft.accountNumber.trim();
    if (!name) {
      message.warning('请填写账户名称');
      return;
    }
    if (!/^[0-9A-Za-z]{8,64}$/.test(number)) {
      message.warning('银行账号需为 8-64 位数字或字母');
      return;
    }
    setCreatingAcct(true);
    try {
      await bankApi.createAccount({ ...acctDraft, accountName: name, accountNumber: number, currency: (acctDraft.currency ?? '').trim().toUpperCase() || 'CNY', kingdeeAccountNumber: kingdeeCode.trim() || undefined });
      message.success(`账户「${name}」已创建，可在下方档案板拖拽归类到公司主体`);
      setCreateAcctOpen(false);
      setAcctDraft(emptyAcctDraft);
      setKingdeeCode('');
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '未能创建银行账户');
    } finally {
      setCreatingAcct(false);
    }
  };

  const runTest = async (account: CompanyArchiveAccount) => {
    if (testingId) return;
    setTestingId(account.id);
    try {
      const result = await bankApi.testConnection(account.id);
      setTestResult({ accountName: account.accountName, result });
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '连接测试失败');
    } finally {
      setTestingId(undefined);
    }
  };

  const removeAccount = (account: CompanyArchiveAccount) => {
    return async () => {
      try {
        await bankApi.deleteAccount(account.id);
        message.success(`账户「${account.accountName}」已从档案移除，历史流水与余额保留可查`);
        await reload();
      } catch (reason) {
        message.error(reason instanceof Error ? reason.message : '移除失败');
      }
    };
  };

  const runAiSuggest = async () => {
    if (aiSuggesting) return;
    setAiSuggesting(true);
    try {
      const response = await bankApi.aiSuggestCompanies();
      setAiSuggestions(response.suggestions);
      setAiEdits(Object.fromEntries(response.suggestions.map((item) => [item.accountId, item.suggestedCompanyName])));
      setAiSelected(response.suggestions.map((item) => item.accountId));
      if (response.suggestions.length === 0) {
        message.info('当前没有待归类的账户：所有账户都已有公司主体归属');
        return;
      }
      setAiOpen(true);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : 'AI 归类建议生成失败（请确认 AI 设置已启用并勾选「公司归类建议」能力）');
    } finally {
      setAiSuggesting(false);
    }
  };

  const applyAiSuggestions = async () => {
    if (aiApplying || aiSelected.length === 0) return;
    const invalid = aiSelected.filter((id) => !(aiEdits[id] || '').trim());
    if (invalid.length > 0) {
      message.warning('存在公司主体名称为空的行，请填写或取消勾选');
      return;
    }
    setAiApplying(true);
    try {
      const result = await bankApi.aiApplyCompanies(aiSelected.map((id) => ({ accountId: id, companyName: (aiEdits[id] || '').trim() })));
      setAiOpen(false);
      setAiApplyResult(result);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '归类应用失败');
    } finally {
      setAiApplying(false);
    }
  };

  const AI_SUGGESTION_COLUMNS: TableColumnsType<AiCompanySuggestion> = [
    { title: '账户名称', dataIndex: 'accountName', ellipsis: true },
    {
      title: '建议公司主体（可修改）',
      width: 260,
      render: (_, item) => (
        <Input
          value={aiEdits[item.accountId] ?? item.suggestedCompanyName}
          maxLength={128}
          onChange={(event) => setAiEdits((current) => ({ ...current, [item.accountId]: event.target.value }))}
        />
      ),
    },
    {
      title: '置信度',
      width: 90,
      render: (_, item) => (item.confidence == null ? '--' : (
        <Tag color={item.confidence >= 0.8 ? 'green' : item.confidence >= 0.5 ? 'orange' : 'red'}>
          {Math.round(item.confidence * 100)}%
        </Tag>
      )),
    },
    { title: '判断依据', dataIndex: 'reason', ellipsis: true, render: (value: string | null) => value || '--' },
  ];

  const AI_APPLY_OUTCOME_TEXT: Record<string, { label: string; color: string }> = {
    CREATED: { label: '新建档案并归入', color: 'green' },
    ASSIGNED: { label: '归入已有档案', color: 'blue' },
    FAILED: { label: '失败', color: 'red' },
  };

  const CONNECTION_RESULT_TEXT: Record<string, string> = {
    CONNECTED: '连接正常',
    FAILED: '银行返回失败',
    DISABLED: '真实适配器未启用',
    TIMEOUT: '连接超时',
    PENDING: '被限频排队',
  };

  const assign = (accountId: number, targetCompanyId: number) => {
    const account = view?.accounts.find((item) => item.id === accountId);
    const target = view?.companies.find((company) => company.id === targetCompanyId);
    if (!account || !target || account.companyId === targetCompanyId) return;
    Modal.confirm({
      title: '确认账户归类',
      content: `将「${account.accountName}（${account.maskedAccountNumber}）」归入「${target.name}」？该账户的历史流水与余额将一并改挂到该主体，筛选口径即时生效。`,
      okText: '确认归类',
      cancelText: '取消',
      onOk: async () => {
        try {
          await bankApi.assignArchiveAccount(account.id, targetCompanyId);
          message.success(`「${account.accountName}」已归入「${target.name}」`);
          await reload();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '归类失败');
        }
      },
    });
  };

  const onChipDragStart = (event: DragEvent<HTMLDivElement>, accountId: number) => {
    event.dataTransfer.setData('text/plain', String(accountId));
    event.dataTransfer.effectAllowed = 'move';
  };

  const onZoneDrop = (event: DragEvent<HTMLDivElement>, targetCompanyId: number) => {
    event.preventDefault();
    setDragOverId(undefined);
    const accountId = Number(event.dataTransfer.getData('text/plain'));
    if (Number.isFinite(accountId) && accountId > 0) {
      assign(accountId, targetCompanyId);
    }
  };

  // 拖回「未归属」区（2026-09-17）：取消归属等待重新/AI 归类，历史流水与余额口径对称置空。
  const unassign = (accountId: number) => {
    const account = view?.accounts.find((item) => item.id === accountId);
    if (!account || account.companyId === null) return;
    Modal.confirm({
      title: '确认取消归属',
      content: `将「${account.accountName}（${account.maskedAccountNumber}）」移回未归属区？该账户的历史流水与余额将一并改挂为「未归属」（仅管理员可查），重新归类后随新主体一并迁移。`,
      okText: '确认取消归属',
      cancelText: '取消',
      onOk: async () => {
        try {
          await bankApi.unassignArchiveAccount(account.id);
          message.success(`「${account.accountName}」已移至未归属区`);
          await reload();
        } catch (reason) {
          message.error(reason instanceof Error ? reason.message : '取消归属失败');
        }
      },
    });
  };

  const onUnassignedDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragOverId(undefined);
    const accountId = Number(event.dataTransfer.getData('text/plain'));
    if (Number.isFinite(accountId) && accountId > 0) {
      unassign(accountId);
    }
  };

  const chipStyle: CSSProperties = {
    border: '1px solid #d9d9d9',
    borderRadius: 6,
    padding: '6px 10px',
    marginBottom: 8,
    background: '#fff',
    cursor: 'grab',
    userSelect: 'none',
  };
  const zoneStyle = (zoneId: number | 'unassigned'): CSSProperties => ({
    border: dragOverId === zoneId ? '2px dashed #1677ff' : '1px solid #f0f0f0',
    borderRadius: 8,
    padding: 12,
    background: dragOverId === zoneId ? '#e6f4ff' : '#fafafa',
    minHeight: 64,
    transition: 'all 0.15s',
  });

  const chipFor = (account: CompanyArchiveAccount) => (
    <div
      key={account.id}
      draggable
      onDragStart={(event) => onChipDragStart(event, account.id)}
      style={chipStyle}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <div style={{ fontWeight: 500 }}>{account.accountName}</div>
        <Space size={2} style={{ flexShrink: 0 }}>
          {canTest && (
            <Button
              type="link"
              size="small"
              icon={<ApiOutlined />}
              style={{ padding: 0, height: 'auto' }}
              loading={testingId === account.id}
              disabled={Boolean(testingId) && testingId !== account.id}
              onClick={(event) => {
                event.stopPropagation();
                void runTest(account);
              }}
            >
              测试连接
            </Button>
          )}
          {canManage && (
            <Popconfirm
              title="从档案移除该账户？"
              description="该账户将从档案板、下拉与查询中隐藏，历史流水与余额保留可查；如需恢复可重新添加账户。"
              okText="移除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={(event) => {
                event?.stopPropagation();
                void removeAccount(account)();
              }}
              onCancel={(event) => event?.stopPropagation()}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                style={{ padding: 0, height: 'auto' }}
                onClick={(event) => event.stopPropagation()}
              >
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      </div>
      <div style={{ color: '#888', fontSize: 12 }}>
        <span className="mono">{account.maskedAccountNumber}</span> · {resolveBankName(account.bankCode)} · {account.currency}
        {account.directStatus === 'DIRECT_CONNECTED' && <Tag color="green" style={{ marginLeft: 6, fontSize: 11 }}>直联</Tag>}
      </div>
    </div>
  );

  const companyCard = (company: CompanyArchiveCompany) => {
    const rows = (view?.accounts || []).filter((account) => account.companyId === company.id);
    return (
      <div
        key={company.id}
        style={{ ...zoneStyle(company.id), width: 280 }}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = 'move';
          setDragOverId(company.id);
        }}
        onDragLeave={() => setDragOverId((current) => (current === company.id ? undefined : current))}
        onDrop={(event) => onZoneDrop(event, company.id)}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <Space size={6}>
            <span style={{ fontWeight: 600 }}>{company.name}</span>
            {company.status !== 'ACTIVE' && <Tag color="default">停用</Tag>}
          </Space>
          <Space size={2}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setRenaming(company);
                setRenameValue(company.name);
              }}
            />
            {canManage && (
              <Popconfirm
                title={`停用公司主体「${company.name}」？`}
                description="系统会先检查活跃账户、用户、流水、余额和规则引用；存在引用时会返回原因。"
                okText="确认停用"
                cancelText="取消"
                onConfirm={() => void deleteCompany(company)}
              >
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  loading={deletingCompanyId === company.id}
                />
              </Popconfirm>
            )}
          </Space>
        </div>
        {rows.length === 0
          ? <div style={{ color: '#bbb', fontSize: 12, padding: '8px 0' }}>暂无账户，拖动账户卡片到这里归类</div>
          : rows.map(chipFor)}
      </div>
    );
  };

  const unassigned = (view?.accounts || []).filter((account) => account.companyId === null);

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="把账户卡片拖到目标公司档案上即可完成归类"
        description="归属决定数据查询里的「公司主体」口径：归类后该账户的历史流水与余额会一并改挂到新主体，晚上照常自动同步。拖回「未归属」区可取消归属（历史一并置为未归属），或直接点「AI 智能归类」让 AI 建议未归属账户的主体。银行报文里的户名（accnam）可在余额页与归属互相核对。"
      />
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Space.Compact style={{ flex: 1, minWidth: 280 }}>
          <Input
            placeholder="新公司主体名称，如：XX 有限公司深圳分公司"
            value={newName}
            maxLength={128}
            onChange={(event) => setNewName(event.target.value)}
            onPressEnter={() => void createCompany()}
          />
          <Button type="primary" icon={<FolderAddOutlined />} loading={creating} onClick={() => void createCompany()}>
            新建公司主体
          </Button>
        </Space.Compact>
        {canManage && (
          <Button icon={<PlusOutlined />} onClick={() => setCreateAcctOpen(true)}>
            新增银行账户
          </Button>
        )}
        {canAi && (
          <Button
            type="primary"
            ghost
            icon={<RobotOutlined />}
            loading={aiSuggesting}
            onClick={() => void runAiSuggest()}
          >
            AI 智能归类
          </Button>
        )}
        {/* W9：AI 提示词设置入口（仅超管可见；company-classification 能力全局生效）。 */}
        {canConfigAi && (
          <PromptSettingButton capability="company-classification" hint="设置「公司主体归类」的系统提示词（全局生效，仅超管）" />
        )}
      </div>
      {error
        ? <ResourceFailure error={error} onRetry={() => void reload()} />
        : loading && !view
          ? <Empty description="正在加载账户档案…" />
          : (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'flex-start' }}>
              {(view?.companies || []).map(companyCard)}
              {/* 未归属区常驻（2026-09-17）：空区也显示，账户可拖回等待 AI 智能归类 */}
              <div
                style={{ ...zoneStyle('unassigned'), width: 280 }}
                onDragOver={(event) => {
                  event.preventDefault();
                  event.dataTransfer.dropEffect = 'move';
                  setDragOverId('unassigned');
                }}
                onDragLeave={() => setDragOverId((current) => (current === 'unassigned' ? undefined : current))}
                onDrop={onUnassignedDrop}
              >
                <Badge count={unassigned.length} style={{ backgroundColor: '#faad14' }} offset={[-4, 0]}>
                  <span style={{ fontWeight: 600, marginRight: 8 }}>未归属 · 待 AI 归类</span>
                </Badge>
                <div style={{ height: 8 }} />
                {unassigned.length === 0
                  ? <div style={{ color: '#bbb', fontSize: 12, padding: '8px 0' }}>暂无未归属账户；把账户卡片拖到这里可取消归属，稍后用「AI 智能归类」重新归档</div>
                  : unassigned.map(chipFor)}
              </div>
            </div>
          )}
      <Modal
        title={`重命名公司档案${renaming ? `：${renaming.name}` : ''}`}
        open={Boolean(renaming)}
        onOk={() => void renameCompany()}
        onCancel={() => setRenaming(undefined)}
        okText="保存"
        cancelText="取消"
        confirmLoading={renamingBusy}
        destroyOnHidden
      >
        <Input
          value={renameValue}
          maxLength={128}
          onChange={(event) => setRenameValue(event.target.value)}
          onPressEnter={() => void renameCompany()}
          placeholder="公司主体名称"
        />
      </Modal>
      <Modal
        title={`无法停用「${deleteConflict?.company.name ?? ''}」`}
        open={Boolean(deleteConflict)}
        footer={<Button type="primary" onClick={() => setDeleteConflict(undefined)}>知道了</Button>}
        onCancel={() => setDeleteConflict(undefined)}
        width={520}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="公司主体仍被以下资源引用，停用前请先处理："
        />
        <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.9 }}>
          {deleteConflict?.detail ?? ''}
        </div>
        <div style={{ color: '#999', fontSize: 12, marginTop: 12 }}>
          处理完引用后回到本页重新点「删除」即可。账户可拖到其他主体或从档案移除；规则主体范围请在凭证规则中心调整。
        </div>
      </Modal>
      <Modal
        title="新增银行账户"
        open={createAcctOpen}
        onOk={() => void submitAccount()}
        onCancel={() => setCreateAcctOpen(false)}
        okText="创建"
        cancelText="取消"
        confirmLoading={creatingAcct}
        destroyOnHidden
        width={460}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={10}>
          <Select
            value={acctDraft.bankCode}
            options={bankOptions}
            onChange={(value) => setAcctDraft((current) => ({ ...current, bankCode: value }))}
            style={{ width: '100%' }}
          />
          <Input
            placeholder="账户名称，如：基本户 / 收款户"
            value={acctDraft.accountName}
            maxLength={128}
            onChange={(event) => setAcctDraft((current) => ({ ...current, accountName: event.target.value }))}
          />
          <Input
            placeholder="银行账号（8-64 位数字或字母）"
            value={acctDraft.accountNumber}
            maxLength={64}
            onChange={(event) => setAcctDraft((current) => ({ ...current, accountNumber: event.target.value }))}
          />
          <Space.Compact style={{ width: '100%' }}>
            <Input
              value={acctDraft.currency}
              maxLength={3}
              style={{ width: 90 }}
              placeholder="币种"
              onChange={(event) => setAcctDraft((current) => ({ ...current, currency: event.target.value }))}
            />
            <InputNumber
              value={acctDraft.availableBalance}
              min={0}
              precision={2}
              style={{ flex: 1 }}
              placeholder="初始可用余额"
              onChange={(value) => setAcctDraft((current) => ({ ...current, availableBalance: value ?? 0 }))}
            />
            <Select
              value={acctDraft.status}
              options={[
                { value: 'ACTIVE', label: '启用' },
                { value: 'FROZEN', label: '冻结' },
                { value: 'CLOSED', label: '销户' },
              ]}
              onChange={(value) => setAcctDraft((current) => ({ ...current, status: value }))}
              style={{ width: 110 }}
            />
          </Space.Compact>
          <Alert
            type="info"
            showIcon
            message="创建后账户归属你所在的公司主体，可在档案板拖拽改挂；初始余额仅作展示，真实余额以银行同步结果为准。创建后点卡片上的「测试连接」验证银行侧连通。"
          />
          <Input
            className="mono"
            placeholder="金蝶账号编码（可选，如 CN_BANKACNT 档案 FNumber）"
            value={kingdeeCode}
            maxLength={128}
            onChange={(event) => setKingdeeCode(event.target.value)}
          />
          <div style={{ color: '#999', fontSize: 12 }}>
            填写后新账户即可直接参与银行流水制证推送金蝶；留空可稍后在「金蝶账户映射」中补配（未配置的账户制证会被阻断）。
          </div>
        </Space>
      </Modal>
      <Modal
        title={`连接测试 · ${testResult?.accountName ?? ''}`}
        open={Boolean(testResult)}
        footer={null}
        onCancel={() => setTestResult(undefined)}
        width={500}
      >
        {testResult && (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="结果">
              <Tag color={testResult.result.result === 'CONNECTED' ? 'green' : 'red'}>
                {CONNECTION_RESULT_TEXT[testResult.result.result] || testResult.result.result}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="说明">{testResult.result.message}</Descriptions.Item>
            {testResult.result.availableBalance != null && (
              <Descriptions.Item label="银行返回可用余额">
                <strong>{testResult.result.availableBalance}</strong> {testResult.result.currency ?? ''}
              </Descriptions.Item>
            )}
            {testResult.result.statementRows != null && (
              <Descriptions.Item label="首页流水行数">{testResult.result.statementRows}</Descriptions.Item>
            )}
            {testResult.result.operation && (
              <Descriptions.Item label="报文交易码"><span className="mono">{testResult.result.operation}</span></Descriptions.Item>
            )}
            {testResult.result.bankRequestNo && (
              <Descriptions.Item label="银行请求号"><span className="mono">{testResult.result.bankRequestNo}</span></Descriptions.Item>
            )}
            {testResult.result.durationMs != null && (
              <Descriptions.Item label="耗时">{testResult.result.durationMs} ms</Descriptions.Item>
            )}
            {testResult.result.endpoint && (
              <Descriptions.Item label="网关地址"><span className="mono">{testResult.result.endpoint}</span></Descriptions.Item>
            )}
            <Descriptions.Item label="测试时间">{dateTime(testResult.result.testedAt)}</Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
      <Modal
        title="AI 智能归类 · 预览确认"
        open={aiOpen}
        onCancel={() => setAiOpen(false)}
        width={760}
        footer={<Space>
          <span className="muted">已勾选 {aiSelected.length} / {aiSuggestions.length} 行；公司名可直接修改，不存在则自动建档</span>
          <Button onClick={() => setAiOpen(false)}>取消</Button>
          <Button type="primary" loading={aiApplying} disabled={aiSelected.length === 0} onClick={() => void applyAiSuggestions()}>
            应用勾选行
          </Button>
        </Space>}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="AI 只建议、不执行：勾选并确认后才会建档/归类，历史流水与余额将改挂到对应主体。归档落在「账户与主体归档」的公司表，字典中心不受影响。"
        />
        <Table<AiCompanySuggestion>
          rowKey="accountId"
          size="small"
          pagination={false}
          dataSource={aiSuggestions}
          columns={AI_SUGGESTION_COLUMNS}
          rowSelection={{
            selectedRowKeys: aiSelected,
            onChange: (keys) => setAiSelected(keys.map(Number)),
          }}
        />
      </Modal>
      <Modal
        title={`AI 归类应用结果 · 成功 ${aiApplyResult?.assignedAccounts ?? 0} / 新建档案 ${aiApplyResult?.createdCompanies ?? 0} / 失败 ${(aiApplyResult?.rows || []).filter((row) => row.outcome === 'FAILED').length}`}
        open={Boolean(aiApplyResult)}
        footer={<Button type="primary" onClick={() => setAiApplyResult(undefined)}>知道了</Button>}
        onCancel={() => setAiApplyResult(undefined)}
        width={620}
      >
        {aiApplyResult && (
          <Table<AiCompanyApplyRow>
            rowKey={(row) => `${row.accountId}-${row.companyName ?? ''}`}
            size="small"
            pagination={false}
            dataSource={aiApplyResult.rows}
            columns={[
              { title: '账户', dataIndex: 'accountName', ellipsis: true },
              { title: '公司主体', dataIndex: 'companyName', ellipsis: true },
              {
                title: '结果',
                width: 130,
                render: (_, row) => {
                  const outcome = AI_APPLY_OUTCOME_TEXT[row.outcome] || { label: row.outcome, color: 'default' };
                  return <Tag color={outcome.color}>{outcome.label}</Tag>;
                },
              },
              { title: '说明', dataIndex: 'message', ellipsis: true, render: (value: string | null) => value || '--' },
            ]}
          />
        )}
      </Modal>
    </>
  );
}
