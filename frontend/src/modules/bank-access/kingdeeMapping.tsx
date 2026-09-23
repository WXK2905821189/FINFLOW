import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Drawer, Input, Modal, Space, Table, Tag, message } from 'antd';
import type { TableColumnsType } from 'antd';
import { bankApi } from '../../services/api';
import type { KingdeeMappingPreview, KingdeeMappingRow } from './types';

/**
 * 金蝶账户映射抽屉（V41，2026-09-21）。
 *
 * <p>总账凭证里挂「银行账号」必录核算维度的科目（账套维度类型 ZDY0001）必须带 CN_BANKACNT
 * 档案编码，而一家公司有多个银行账户 —— 映射粒度是「账户级」，维度值跟随该笔流水所属账户。</p>
 *
 * <p>交互：预演（只读）→ 一键自动匹配（只写回唯一命中）→ 多义/未命中行人工指定。
 * 「未命中」多为虚拟账户（支付宝 / 微信 / 薪福通 / 分贝通），按公司组织列出账套档案供挑选。</p>
 */
const STATUS_META: Record<string, { text: string; color: string }> = {
  MAPPED: { text: '已映射', color: 'success' },
  AUTO_MATCHABLE: { text: '可自动匹配', color: 'processing' },
  AMBIGUOUS: { text: '需人工选择', color: 'warning' },
  UNMATCHED: { text: '未命中', color: 'warning' },
  NOT_REQUIRED: { text: '无需映射', color: 'default' },
  CATALOG_UNAVAILABLE: { text: '档案不可用', color: 'error' },
};

/** 候选格式「编码 名称（组织 N）」→ 取编码部分。 */
const codeOf = (candidate: string) => candidate.split(' ')[0];

export function KingdeeMappingDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [preview, setPreview] = useState<KingdeeMappingPreview>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [matching, setMatching] = useState(false);
  const [editing, setEditing] = useState<KingdeeMappingRow>();
  const [inputValue, setInputValue] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setPreview(await bankApi.kingdeeMapping());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '金蝶账户映射预演未能完成');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!open) {
      return;
    }
    // 首次打开时拉取预演。放到宏任务里执行，避免在 effect 体内同步 setState
    // 触发级联渲染（react-hooks/set-state-in-effect）。
    const timer = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timer);
  }, [open, load]);

  const autoMatch = async () => {
    setMatching(true);
    try {
      const result = await bankApi.autoMatchKingdee();
      message.success(`已自动匹配 ${result.matched} 个账户；待人工 ${result.ambiguous + result.unmatched} 个`);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '自动匹配未能完成');
    } finally {
      setMatching(false);
    }
  };

  const openEdit = (row: KingdeeMappingRow) => {
    setEditing(row);
    setInputValue(row.kingdeeAccountNumber || '');
  };

  const submit = async (accountId: number, value: string) => {
    setSaving(true);
    try {
      await bankApi.setKingdeeMapping(accountId, value);
      message.success(value.trim() ? '金蝶账户映射已更新' : '已清除映射');
      setEditing(undefined);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '映射未能保存');
    } finally {
      setSaving(false);
    }
  };

  const unresolvedCount = (preview?.rows || []).filter((row) => !['MAPPED', 'NOT_REQUIRED'].includes(row.status)).length;
  const unresolvedRows = (preview?.rows || []).filter((row) => !['MAPPED', 'NOT_REQUIRED'].includes(row.status));

  const columns: TableColumnsType<KingdeeMappingRow> = [
    {
      title: '账户',
      dataIndex: 'accountName',
      ellipsis: true,
      render: (_value, row) => (
        <div>
          <div>{row.accountName}</div>
          <div className="table-sub mono">{row.maskedAccountNumber}</div>
        </div>
      ),
    },
    { title: '公司主体', dataIndex: 'companyName', ellipsis: true, render: (value) => value || '--' },
    {
      title: '金蝶账户编码',
      dataIndex: 'kingdeeAccountNumber',
      width: 230,
      render: (value, row) => value
        ? <span className="mono">{value}</span>
        : <span className="muted-inline">{row.status === 'NOT_REQUIRED' ? '纯人工制证，无需映射' : '未映射'}</span>,
    },
    {
      title: '匹配状态',
      dataIndex: 'status',
      width: 130,
      render: (value: string) => {
        const meta = STATUS_META[value] || { text: value, color: 'default' };
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    {
      title: '操作',
      width: 160,
      fixed: 'right',
      render: (_value, row) => row.status === 'NOT_REQUIRED'
        ? <span className="muted-inline">--</span>
        : (
          <Space size={4}>
            <Button type="link" size="small" onClick={() => openEdit(row)}>
              {row.kingdeeAccountNumber ? '更改' : '指定'}
            </Button>
            {row.kingdeeAccountNumber
              ? <Button type="link" size="small" danger onClick={() => void submit(row.accountId, '')}>清除</Button>
              : null}
          </Space>
        ),
    },
  ];

  return (
    <Drawer
      title="金蝶账户映射"
      width={1000}
      open={open}
      onClose={onClose}
      extra={(
        <Space>
          <Button onClick={() => void load()} loading={loading}>刷新</Button>
          <Button type="primary" loading={matching} onClick={() => void autoMatch()}>一键自动匹配</Button>
        </Space>
      )}
    >
      <p className="muted">
        制证时「银行存款」分录要带上该笔流水所属账户的金蝶档案编码（核算维度「银行账号」）。
        系统按账号自动匹配；同一账号在多个组织各有一个档案时按公司主体限定，仍无法确定或
        账号对不上的（虚拟账户）请人工指定。
      </p>
      {preview?.note ? <Alert type="warning" showIcon message={preview.note} style={{ marginBottom: 12 }} /> : null}
      {unresolvedCount > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={`${unresolvedCount} 个账户尚未映射金蝶编码，制证将被阻断`}
          description="可先一键自动匹配，再对未命中或多义账户人工指定。"
          action={<Button size="small" onClick={() => {
            const first = unresolvedRows[0];
            if (first) openEdit(first);
          }}>处理未映射</Button>}
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {error ? <Alert type="error" showIcon message="加载失败" description={error} style={{ marginBottom: 12 }} /> : null}
      <Table
        rowKey="accountId"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={preview?.rows || []}
        pagination={false}
        scroll={{ x: 900 }}
      />

      <Modal
        title={editing ? `指定金蝶账户 · ${editing.accountName}` : '指定金蝶账户'}
        open={!!editing}
        onCancel={() => setEditing(undefined)}
        onOk={() => editing && void submit(editing.accountId, inputValue)}
        confirmLoading={saving}
        okText="保存"
      >
        <div className="muted-inline" style={{ marginBottom: 4 }}>
          账户尾号 {editing?.maskedAccountNumber}，公司主体 {editing?.companyName || '--'}
        </div>
        {editing?.candidates?.length ? (
          <div style={{ marginBottom: 12 }}>
            <div className="table-sub">账套候选（点击填入）</div>
            {editing.candidates.map((candidate) => (
              <div key={candidate} style={{ marginTop: 4 }}>
                <Button size="small" onClick={() => setInputValue(codeOf(candidate))}>{candidate}</Button>
              </div>
            ))}
          </div>
        ) : null}
        <Input
          value={inputValue}
          onChange={(event) => setInputValue(event.target.value)}
          placeholder="金蝶 CN_BANKACNT 档案编码（FNumber）"
        />
        <div className="muted-inline" style={{ marginTop: 8 }}>
          编码就是金蝶「核算维度 › 银行账号」下拉里看到的账号；留空保存表示清除映射。
        </div>
      </Modal>
    </Drawer>
  );
}
