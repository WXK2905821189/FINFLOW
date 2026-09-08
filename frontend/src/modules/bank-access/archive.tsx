import { useState, type CSSProperties, type DragEvent } from 'react';
import { Alert, Badge, Button, Drawer, Empty, Input, Modal, Space, Tag, message } from 'antd';
import { EditOutlined, FolderAddOutlined } from '@ant-design/icons';
import { bankApi } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import type { CompanyArchiveAccount, CompanyArchiveCompany, CompanyArchiveView } from '../../types';

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
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const [dragOverId, setDragOverId] = useState<number | 'unassigned'>();
  const [renaming, setRenaming] = useState<CompanyArchiveCompany>();
  const [renameValue, setRenameValue] = useState('');
  const [renamingBusy, setRenamingBusy] = useState(false);

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
      <div style={{ fontWeight: 500 }}>{account.accountName}</div>
      <div style={{ color: '#888', fontSize: 12 }}>
        <span className="mono">{account.maskedAccountNumber}</span> · {account.bankCode} · {account.currency}
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
      <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
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
    </>
  );
}
