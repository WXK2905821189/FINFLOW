import { useState, type CSSProperties, type DragEvent } from 'react';
import { Alert, Badge, Button, Descriptions, Drawer, Empty, Input, InputNumber, Modal, Select, Space, Tag, message } from 'antd';
import { ApiOutlined, EditOutlined, FolderAddOutlined, PlusOutlined } from '@ant-design/icons';
import { bankApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime } from '../shared/format';
import { BANK_NAME_TEXT } from './bankQueryTexts';
import type { BankAccountCreatePayload, BankConnectionTestResult, CompanyArchiveAccount, CompanyArchiveCompany, CompanyArchiveView } from '../../types';

/**
 * 拖拽式账户档案管理：公司档案是投放区，账户卡片拖到目标公司上松手完成归类。
 * 原生 HTML5 drag & drop，不引入第三方拖拽库；历史流水/余额由服务端一并迁移。
 * 外层 Drawer destroyOnClose：每次打开重新挂载档案板并加载数据。
 */
export function CompanyArchiveDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Drawer
      title="账户档案管理"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {open ? <ArchiveBoard /> : null}
    </Drawer>
  );
}

function ArchiveBoard() {
  const { data: view, loading, error, reload } = useRemote<CompanyArchiveView>(() => bankApi.archive(), []);
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
  const [createAcctOpen, setCreateAcctOpen] = useState(false);
  const [creatingAcct, setCreatingAcct] = useState(false);
  const emptyAcctDraft: BankAccountCreatePayload = { bankCode: 'CITIC', accountName: '', accountNumber: '', currency: 'CNY', availableBalance: 0, status: 'ACTIVE' };
  const [acctDraft, setAcctDraft] = useState<BankAccountCreatePayload>(emptyAcctDraft);
  const [testingId, setTestingId] = useState<number>();
  const [testResult, setTestResult] = useState<{ accountName: string; result: BankConnectionTestResult }>();

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
      await bankApi.createAccount({ ...acctDraft, accountName: name, accountNumber: number, currency: acctDraft.currency.trim().toUpperCase() || 'CNY' });
      message.success(`账户「${name}」已创建，可在下方档案板拖拽归类到公司主体`);
      setCreateAcctOpen(false);
      setAcctDraft(emptyAcctDraft);
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
        {canTest && (
          <Button
            type="link"
            size="small"
            icon={<ApiOutlined />}
            style={{ padding: 0, flexShrink: 0, height: 'auto' }}
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
      </div>
      <div style={{ color: '#888', fontSize: 12 }}>
        <span className="mono">{account.maskedAccountNumber}</span> · {BANK_NAME_TEXT[account.bankCode] || account.bankCode} · {account.currency}
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
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setRenaming(company);
              setRenameValue(company.name);
            }}
          />
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
        description="归属决定数据查询里的「公司主体」口径：归类后该账户的历史流水与余额会一并改挂到新主体，晚上照常自动同步。银行报文里的户名（accnam）可在余额页与归属互相核对。"
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
            新建档案
          </Button>
        </Space.Compact>
        {canManage && (
          <Button icon={<PlusOutlined />} onClick={() => setCreateAcctOpen(true)}>
            新增银行账户
          </Button>
        )}
      </div>
      {error
        ? <ResourceFailure error={error} onRetry={() => void reload()} />
        : loading && !view
          ? <Empty description="正在加载账户档案…" />
          : (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'flex-start' }}>
              {(view?.companies || []).map(companyCard)}
              {unassigned.length > 0 && (
                <div
                  style={{ ...zoneStyle('unassigned'), width: 280 }}
                  onDragOver={(event) => {
                    event.preventDefault();
                    setDragOverId('unassigned');
                  }}
                  onDragLeave={() => setDragOverId((current) => (current === 'unassigned' ? undefined : current))}
                >
                  <Badge count={unassigned.length} style={{ backgroundColor: '#faad14' }} offset={[-4, 0]}>
                    <span style={{ fontWeight: 600, marginRight: 8 }}>未归类</span>
                  </Badge>
                  <div style={{ height: 8 }} />
                  {unassigned.map(chipFor)}
                </div>
              )}
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
        destroyOnClose
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
        title="新增银行账户"
        open={createAcctOpen}
        onOk={() => void submitAccount()}
        onCancel={() => setCreateAcctOpen(false)}
        okText="创建"
        cancelText="取消"
        confirmLoading={creatingAcct}
        destroyOnClose
        width={460}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={10}>
          <Select
            value={acctDraft.bankCode}
            options={Object.entries(BANK_NAME_TEXT).map(([value, label]) => ({ value, label }))}
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
              <Tag color={testResult.result === 'CONNECTED' ? 'green' : 'red'}>
                {CONNECTION_RESULT_TEXT[testResult.result] || testResult.result}
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
    </>
  );
}
