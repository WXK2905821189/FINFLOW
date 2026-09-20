import { useCallback, useMemo, useState } from 'react';
import {
  Button, Card, Drawer, Empty, Form, Input, InputNumber, Modal, Popconfirm, Segmented,
  Select, Space, Steps, Switch, Table, Tag, Tooltip, Upload, message, type TableColumnsType,
} from 'antd';
import { DownloadOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { kingdeeRuleApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { PromptSettingButton } from '../admin/PromptSettingModal';
import { useRemote, ResourceFailure } from '../shared/components';
import { lineSummary } from './voucherTexts';
import type {
  VoucherRuleGroup, VoucherRuleImportPreview, VoucherRuleImportRow,
  VoucherRuleLine, VoucherRuleRow, VoucherRuleUpsertPayload,
} from './types';
import { ValidationEmbedded } from '../statements/ValidationPage';

/**
 * W4 规则中心（2026-09-18，V36 六需求之 ④）：
 *  - 「大类规则」与「科目与往来规则」（/validation）合并为单页双 Tab；
 *  - 凭证规则从只读视图升级为维护面：CRUD + 分组管理 + Excel 导入三步
 *    （上传 → AI 映射预览 → 人工勾选/修正确认入库；AI fail-closed 不阻断）；
 *  - UI 对齐 docs/ui-v34-demo.html category 屏：全宽表格 + 行内编辑抽屉。
 */

const ORG_NAMES: Record<string, string> = {
  '300': '即设',
  '400': '雪云',
  '710': '长沙',
  '720': '广州',
  '900': '海南',
};

const orgText = (orgs: string[]) => {
  if (!orgs || !orgs.length || orgs.includes('ALL')) return '全部主体';
  return orgs.map((org) => ORG_NAMES[org] || org).join(' / ');
};

const directionTag = (direction: string) => direction === 'INCOME'
  ? <Tag color="green">收</Tag>
  : direction === 'EXPENSE' ? <Tag color="orange">付</Tag> : <Tag>{direction}</Tag>;

const prettyJson = (value: unknown) => JSON.stringify(value ?? null, null, 2);

const parseJsonField = (text: string, label: string): unknown => {
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${label} 不是合法 JSON，请修正后再保存`);
  }
};

export function CategoryRulesPage() {
  const [activeTab, setActiveTab] = useState<'rules' | 'validation'>('rules');
  // W9：AI 提示词设置入口（仅超管；rule-import 能力全局生效）。
  const canConfigAi = useAuthStore((state) => state.hasPermission)('ai:config');

  // ---------------- 规则与分组数据 ----------------
  const loader = useCallback(() => kingdeeRuleApi.list(), []);
  const { data, loading, error, reload } = useRemote<VoucherRuleRow[]>(loader, [loader]);
  const groupsLoader = useCallback(() => kingdeeRuleApi.groups(), []);
  const { data: groupsData, reload: reloadGroups } = useRemote<VoucherRuleGroup[]>(groupsLoader, [groupsLoader]);
  const groups = groupsData ?? [];
  const [activeGroupId, setActiveGroupId] = useState<number | null>(null);

  const rules = useMemo(() => {
    const all = data || [];
    return activeGroupId == null ? all : all.filter((rule) => rule.groupId === activeGroupId);
  }, [data, activeGroupId]);

  const withTemplate = rules.filter((rule) => (rule.debitLines?.length || 0) + (rule.creditLines?.length || 0) > 0).length;
  const enabledCount = rules.filter((rule) => rule.enabled).length;

  // ---------------- 规则编辑抽屉 ----------------
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<VoucherRuleRow | null>(null);
  const [form] = Form.useForm();
  const [matchJson, setMatchJson] = useState('');
  const [debitJson, setDebitJson] = useState('');
  const [creditJson, setCreditJson] = useState('');
  const [extraJson, setExtraJson] = useState('');
  const [saving, setSaving] = useState(false);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({
      businessType: '', category: '', direction: 'EXPENSE', priority: 100,
      groupId: activeGroupId ?? undefined, enabled: true, remark: '',
    });
    setMatchJson(prettyJson({ logic: 'ALL', conditions: [{ field: 'SUMMARY', op: 'CONTAINS', values: ['关键词'] }] }));
    setDebitJson(prettyJson([{ account: '', name: '', dimension: 'COUNTERPARTY', value: null, branches: null, share: 'FULL' }]));
    setCreditJson(prettyJson([{ account: '', name: '', dimension: 'BANK_ACCOUNT', value: null, branches: null, share: 'FULL' }]));
    setExtraJson('');
    setEditOpen(true);
  };

  const openEdit = (rule: VoucherRuleRow) => {
    setEditing(rule);
    form.setFieldsValue({
      businessType: rule.businessType, category: rule.category, direction: rule.direction,
      priority: rule.priority, groupId: rule.groupId ?? undefined, enabled: rule.enabled, remark: rule.remark ?? '',
    });
    setMatchJson(prettyJson(rule.match));
    setDebitJson(prettyJson(rule.debitLines));
    setCreditJson(prettyJson(rule.creditLines));
    setExtraJson(rule.extraVoucher ? prettyJson(rule.extraVoucher) : '');
    setEditOpen(true);
  };

  const saveRule = async () => {
    let payload: VoucherRuleUpsertPayload;
    try {
      const values = await form.validateFields();
      payload = {
        ruleNo: editing?.ruleNo ?? null,
        businessType: String(values.businessType).trim(),
        category: String(values.category).trim(),
        direction: String(values.direction),
        priority: Number(values.priority),
        groupId: values.groupId ?? null,
        enabled: Boolean(values.enabled),
        remark: values.remark ? String(values.remark) : null,
        match: parseJsonField(matchJson, '匹配条件') as VoucherRuleUpsertPayload['match'],
        debitLines: parseJsonField(debitJson, '借方分录') as VoucherRuleLine[],
        creditLines: parseJsonField(creditJson, '贷方分录') as VoucherRuleLine[],
        extraVoucher: extraJson.trim() ? parseJsonField(extraJson, '第二张凭证') as NonNullable<VoucherRuleRow['extraVoucher']> : null,
      };
    } catch (reason) {
      if (reason instanceof Error) message.error(reason.message);
      return; // 表单校验失败 antd 已提示
    }
    setSaving(true);
    try {
      if (editing) await kingdeeRuleApi.update(editing.id, payload);
      else await kingdeeRuleApi.create(payload);
      message.success(editing ? '规则已更新' : '规则已创建');
      setEditOpen(false);
      void reload();
      reloadGroups();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const deleteRule = async (rule: VoucherRuleRow) => {
    try {
      await kingdeeRuleApi.remove(rule.id);
      message.success(`规则 ${rule.ruleNo} 已删除`);
      void reload();
      reloadGroups();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '删除失败');
    }
  };

  const toggleEnabled = async (rule: VoucherRuleRow) => {
    try {
      await kingdeeRuleApi.update(rule.id, {
        ruleNo: rule.ruleNo, businessType: rule.businessType, category: rule.category,
        priority: rule.priority, scopeOrgs: rule.scopeOrgs, scopeBankChannels: rule.scopeBankChannels,
        direction: rule.direction, amountMin: rule.amountMin, amountMax: rule.amountMax,
        match: rule.match, debitLines: rule.debitLines, creditLines: rule.creditLines,
        extraVoucher: rule.extraVoucher, enabled: !rule.enabled, remark: rule.remark, groupId: rule.groupId ?? null,
      });
      message.success(!rule.enabled ? '规则已启用' : '规则已停用');
      void reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '操作失败');
    }
  };

  // ---------------- 分组管理 ----------------
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [groupDesc, setGroupDesc] = useState('');
  const [groupEditingId, setGroupEditingId] = useState<number | null>(null);

  const submitGroup = async () => {
    if (!groupName.trim()) {
      message.warning('分组名称必填');
      return;
    }
    try {
      const body = { name: groupName.trim(), description: groupDesc.trim() || null };
      if (groupEditingId != null) await kingdeeRuleApi.updateGroup(groupEditingId, body);
      else await kingdeeRuleApi.createGroup(body);
      message.success(groupEditingId != null ? '分组已更新' : '分组已创建');
      setGroupModalOpen(false);
      reloadGroups();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存失败');
    }
  };

  const deleteGroup = async (group: VoucherRuleGroup) => {
    try {
      await kingdeeRuleApi.removeGroup(group.id);
      message.success('分组已删除');
      if (activeGroupId === group.id) setActiveGroupId(null);
      reloadGroups();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '删除失败（分组下仍有规则时会被拒绝）');
    }
  };

  // ---------------- Excel 导入向导 ----------------
  const [wizardOpen, setWizardOpen] = useState(false);
  const [wizardStep, setWizardStep] = useState(0);
  const [wizardFile, setWizardFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<VoucherRuleImportPreview | null>(null);
  const [selectedRows, setSelectedRows] = useState<number[]>([]);
  const [editedMap, setEditedMap] = useState<Map<number, VoucherRuleUpsertPayload>>(new Map());
  const [importGroupId, setImportGroupId] = useState<number | null>(null);
  const [importing, setImporting] = useState(false);
  const [jsonRowEditing, setJsonRowEditing] = useState<VoucherRuleImportRow | null>(null);
  const [jsonRowText, setJsonRowText] = useState('');

  const openWizard = () => {
    setWizardStep(0);
    setWizardFile(null);
    setPreview(null);
    setSelectedRows([]);
    setEditedMap(new Map());
    setImportGroupId(activeGroupId);
    setWizardOpen(true);
  };

  const runPreview = async () => {
    if (!wizardFile) {
      message.warning('请先选择 .xlsx 规则文件');
      return;
    }
    try {
      const result = await kingdeeRuleApi.importPreview(wizardFile);
      setPreview(result);
      setSelectedRows(result.rows.map((row) => row.rowIndex));
      setWizardStep(1);
      message.info(result.aiSummary);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '解析失败');
    }
  };

  const payloadOf = (row: VoucherRuleImportRow): VoucherRuleUpsertPayload | null =>
    editedMap.get(row.rowIndex) ?? row.mapped ?? null;

  const runConfirm = async () => {
    if (!preview) return;
    const rows = preview.rows
      .filter((row) => selectedRows.includes(row.rowIndex))
      .map((row) => payloadOf(row))
      .filter((payload): payload is VoucherRuleUpsertPayload => payload != null);
    if (rows.length === 0) {
      message.warning('勾选的行都还没有映射结果，请在预览中编辑映射后再导入');
      return;
    }
    if (rows.length < selectedRows.length) {
      message.warning('部分勾选行缺少映射结果，已自动跳过');
    }
    setImporting(true);
    try {
      const created = await kingdeeRuleApi.importConfirm(rows, importGroupId);
      message.success(`已导入 ${created.length} 条规则`);
      setWizardStep(2);
      void reload();
      reloadGroups();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '导入失败');
    } finally {
      setImporting(false);
    }
  };

  const openJsonEditor = (row: VoucherRuleImportRow) => {
    setJsonRowEditing(row);
    setJsonRowText(row.mapped ? prettyJson(row.mapped) : prettyJson({
      ruleNo: null, businessType: row.sourceCells[1] ?? '', category: row.sourceCells[2] ?? '',
      priority: 100, direction: (row.sourceCells[3] ?? 'EXPENSE').toUpperCase(),
      match: { logic: 'ALL', conditions: [] }, debitLines: [], creditLines: [],
      enabled: true, remark: row.sourceCells[10] ?? null, groupId: importGroupId,
    }));
  };

  const saveJsonRow = () => {
    if (!jsonRowEditing) return;
    try {
      const parsed = JSON.parse(jsonRowText) as VoucherRuleUpsertPayload;
      setEditedMap((prev) => new Map(prev).set(jsonRowEditing.rowIndex, parsed));
      setJsonRowEditing(null);
    } catch {
      message.error('不是合法 JSON，请修正');
    }
  };

  // ---------------- 规则表格列 ----------------
  const columns: TableColumnsType<VoucherRuleRow> = [
    { title: '规则号', dataIndex: 'ruleNo', width: 70, render: (value: number) => <span className="mono">{value}</span> },
    { title: '业务大类', dataIndex: 'businessType', ellipsis: true },
    { title: '类别', dataIndex: 'category', width: 100, ellipsis: true },
    { title: '方向', dataIndex: 'direction', width: 64, render: (value: string) => directionTag(value) },
    { title: '分组', dataIndex: 'groupName', width: 110, ellipsis: true, render: (value: string | null) => value || <Tag>未分组</Tag> },
    {
      title: '匹配关键词', ellipsis: true, width: 160,
      render: (_, row) => row.match?.conditions?.length
        ? row.match.conditions.map((condition) => condition.values.join('/')).join('；')
        : '--',
    },
    {
      title: '默认借方', ellipsis: true,
      render: (_, row) => lineSummary(row.debitLines?.[0]) + (row.debitLines?.length > 1 ? ` 等 ${row.debitLines.length} 行` : ''),
    },
    {
      title: '默认贷方', ellipsis: true,
      render: (_, row) => lineSummary(row.creditLines?.[0]) + (row.creditLines?.length > 1 ? ` 等 ${row.creditLines.length} 行` : ''),
    },
    { title: '优先级', dataIndex: 'priority', width: 70 },
    { title: '主体', width: 100, render: (_, row) => orgText(row.scopeOrgs) },
    {
      title: '状态', width: 92, render: (_, row) => row.enabled
        ? <Tag color="green">启用</Tag>
        : <Tooltip title="规则已停用，匹配器自动跳过"><Tag>已停用</Tag></Tooltip>,
    },
    {
      title: '操作', width: 170, fixed: 'right',
      render: (_, row) => <Space size={0}>
        <Button type="link" size="small" onClick={() => openEdit(row)}>编辑</Button>
        <Button type="link" size="small" onClick={() => void toggleEnabled(row)}>{row.enabled ? '停用' : '启用'}</Button>
        <Popconfirm title={`确定删除规则 ${row.ruleNo}（${row.businessType}）？`} onConfirm={() => void deleteRule(row)}>
          <Button type="link" size="small" danger>删除</Button>
        </Popconfirm>
      </Space>,
    },
  ];

  const groupStats = useMemo(() => {
    const byGroup = new Map<number, number>();
    (data || []).forEach((rule) => {
      if (rule.groupId != null) byGroup.set(rule.groupId, (byGroup.get(rule.groupId) || 0) + 1);
    });
    return byGroup;
  }, [data]);

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">凭证与入账 / 规则中心</span>
        <h2>规则中心</h2>
        <p className="muted">
          人工维护「业务大类 + 默认分录模板」，AI 制证按大类套用模板生成分录；模板缺失或冲突时退回人工，不猜测科目。
          支持 Excel 导入：AI 先填映射，人工审阅确认后才入库；支持规则分组管理。
        </p>
      </div>
      <Space wrap>
        <Segmented
          value={activeTab}
          onChange={(value) => setActiveTab(value as 'rules' | 'validation')}
          options={[{ label: '凭证大类规则', value: 'rules' }, { label: '校验与入账映射', value: 'validation' }]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => { void reload(); reloadGroups(); }}>刷新</Button>
      </Space>
    </div>

    {activeTab === 'rules' ? <>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Tag style={{ margin: 0 }}>分组：</Tag>
          <Button
            size="small" type={activeGroupId == null ? 'primary' : 'default'}
            onClick={() => setActiveGroupId(null)}
          >全部（{data?.length || 0}）</Button>
          {groups.map((group) => <Button
            key={group.id}
            size="small"
            type={activeGroupId === group.id ? 'primary' : 'default'}
            onClick={() => setActiveGroupId(group.id)}
          >{group.name}（{groupStats.get(group.id) ?? group.ruleCount}）</Button>)}
          <Button size="small" icon={<PlusOutlined />} onClick={() => {
            setGroupEditingId(null);
            setGroupName('');
            setGroupDesc('');
            setGroupModalOpen(true);
          }}>管理分组</Button>
        </Space>
      </Card>
      <Card title={<>规则清单 <span className="table-sub">共 {rules.length} 条 · {withTemplate} 条已配模板 · 启用 {enabledCount} 条</span></>}
        extra={<Space wrap>
          <Button icon={<DownloadOutlined />} onClick={() => void kingdeeRuleApi.downloadTemplate()}>下载模板</Button>
          <Button icon={<UploadOutlined />} onClick={openWizard}>导入 Excel</Button>
          {/* W9：规则导入 AI 映射的提示词设置入口（仅超管）。 */}
          {canConfigAi && <PromptSettingButton capability="rule-import" hint="设置「规则导入映射」的系统提示词（全局生效，仅超管）" />}
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增规则</Button>
        </Space>}>
        {error ? <ResourceFailure error={error} onRetry={reload} /> : <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rules}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无凭证规则" /> }}
          scroll={{ x: 1400 }}
        />}
      </Card>
    </> : <ValidationEmbedded />}

    {/* 规则编辑抽屉 */}
    <Drawer
      title={editing ? `编辑规则 · ${editing.ruleNo} ${editing.businessType}` : '新增规则'}
      open={editOpen}
      onClose={() => setEditOpen(false)}
      width={640}
      extra={<Space>
        <Button onClick={() => setEditOpen(false)}>取消</Button>
        <Button type="primary" loading={saving} onClick={() => void saveRule()}>保存</Button>
      </Space>}
    >
      <Form form={form} layout="vertical">
        <Space size="middle" style={{ display: 'flex' }}>
          <Form.Item name="businessType" label="业务类型" rules={[{ required: true, message: '必填' }]} style={{ width: 200 }}>
            <Input placeholder="例如 社保 / 租金" />
          </Form.Item>
          <Form.Item name="category" label="大类" rules={[{ required: true, message: '必填' }]} style={{ width: 160 }}>
            <Input placeholder="例如 租赁费" />
          </Form.Item>
          <Form.Item name="direction" label="方向" rules={[{ required: true }]} style={{ width: 120 }}>
            <Select options={[{ value: 'INCOME', label: '收' }, { value: 'EXPENSE', label: '付' }, { value: 'BOTH', label: '收/付' }]} />
          </Form.Item>
        </Space>
        <Space size="middle" style={{ display: 'flex' }}>
          <Form.Item name="priority" label="优先级（小者优先）" rules={[{ required: true }]} style={{ width: 160 }}>
            <InputNumber min={1} max={9999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="groupId" label="所属分组" style={{ width: 200 }}>
            <Select allowClear placeholder="未分组"
              options={groups.map((group) => ({ value: group.id, label: group.name }))} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Space>
        <Form.Item name="remark" label="备注">
          <Input.TextArea rows={2} placeholder="口径说明（可选）" />
        </Form.Item>
      </Form>
      <p style={{ fontWeight: 600, marginBottom: 6 }}>匹配条件 JSON <span className="table-sub">（field: SUMMARY/COUNTERPARTY_NAME；op: CONTAINS/EQ/IN 等）</span></p>
      <Input.TextArea value={matchJson} onChange={(event) => setMatchJson(event.target.value)} rows={5} className="mono" />
      <p style={{ fontWeight: 600, margin: '12px 0 6px' }}>借方分录 JSON <span className="table-sub">（account/name/dimension/value/branches/share）</span></p>
      <Input.TextArea value={debitJson} onChange={(event) => setDebitJson(event.target.value)} rows={6} className="mono" />
      <p style={{ fontWeight: 600, margin: '12px 0 6px' }}>贷方分录 JSON</p>
      <Input.TextArea value={creditJson} onChange={(event) => setCreditJson(event.target.value)} rows={6} className="mono" />
      <p style={{ fontWeight: 600, margin: '12px 0 6px' }}>第二张凭证 JSON <span className="table-sub">（可选，留空表示无）</span></p>
      <Input.TextArea value={extraJson} onChange={(event) => setExtraJson(event.target.value)} rows={4} className="mono" placeholder='{"debitLines": [...], "creditLines": [...]}' />
    </Drawer>

    {/* 分组管理弹窗 */}
    <Modal
      title="规则分组管理"
      open={groupModalOpen}
      onCancel={() => setGroupModalOpen(false)}
      footer={null}
      width={560}
      destroyOnHidden
    >
      <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
        <Input placeholder="分组名称" value={groupName} onChange={(event) => setGroupName(event.target.value)} />
        <Input placeholder="描述（可选）" value={groupDesc} onChange={(event) => setGroupDesc(event.target.value)} />
        <Button type="primary" onClick={() => void submitGroup()}>{groupEditingId != null ? '保存' : '新增'}</Button>
        {groupEditingId != null && <Button onClick={() => { setGroupEditingId(null); setGroupName(''); setGroupDesc(''); }}>取消编辑</Button>}
      </Space.Compact>
      <Table
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={groups}
        locale={{ emptyText: <Empty description="暂无分组" /> }}
        columns={[
          { title: '分组', dataIndex: 'name' },
          { title: '描述', dataIndex: 'description', ellipsis: true, render: (value: string | null) => value || '--' },
          { title: '规则数', dataIndex: 'ruleCount', width: 80 },
          {
            title: '操作', width: 130,
            render: (_, group: VoucherRuleGroup) => <Space size={0}>
              <Button type="link" size="small" onClick={() => {
                setGroupEditingId(group.id);
                setGroupName(group.name);
                setGroupDesc(group.description ?? '');
              }}>改名</Button>
              <Popconfirm title={`删除分组「${group.name}」？分组下有 ${group.ruleCount} 条规则时会被拒绝。`}
                onConfirm={() => void deleteGroup(group)}>
                <Button type="link" size="small" danger>删除</Button>
              </Popconfirm>
            </Space>,
          },
        ] as TableColumnsType<VoucherRuleGroup>}
      />
    </Modal>

    {/* 导入向导 */}
    <Modal
      title="Excel 导入规则"
      open={wizardOpen}
      onCancel={() => setWizardOpen(false)}
      footer={null}
      width={960}
      destroyOnHidden
    >
      <Steps
        size="small"
        current={wizardStep}
        items={[{ title: '上传' }, { title: 'AI 映射预览' }, { title: '完成' }]}
        style={{ marginBottom: 16 }}
      />
      {wizardStep === 0 && <>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Button icon={<DownloadOutlined />} onClick={() => void kingdeeRuleApi.downloadTemplate()}>先下载模板（含示例行与表头）</Button>
          <Upload.Dragger
            accept=".xlsx,.xlsm"
            maxCount={1}
            beforeUpload={(file) => {
              setWizardFile(file);
              return false;
            }}
            onRemove={() => setWizardFile(null)}
          >
            <p className="ant-upload-drag-icon"><UploadOutlined /></p>
            <p className="ant-upload-text">点击或拖拽规则 .xlsx 到此处</p>
            <p className="ant-upload-hint">解析在服务端完成；AI 将尝试自动填入科目映射，全部行先预览、人工审阅后才入库</p>
          </Upload.Dragger>
          <Space>
            <span>导入规则归入分组：</span>
            <Select
              style={{ width: 200 }}
              allowClear
              placeholder="未分组"
              value={importGroupId ?? undefined}
              onChange={(value) => setImportGroupId(value ?? null)}
              options={groups.map((group) => ({ value: group.id, label: group.name }))}
            />
            <Button type="primary" onClick={() => void runPreview()}>解析并预览</Button>
          </Space>
        </Space>
      </>}
      {wizardStep === 1 && preview && <>
        <p className="table-sub" style={{ marginBottom: 8 }}>{preview.aiSummary}</p>
        <Table
          rowKey="rowIndex"
          size="small"
          pagination={false}
          scroll={{ x: 900, y: 360 }}
          dataSource={preview.rows}
          rowSelection={{
            selectedRowKeys: selectedRows,
            onChange: (keys) => setSelectedRows(keys as number[]),
          }}
          columns={[
            { title: '#', dataIndex: 'rowIndex', width: 50 },
            {
              title: '原文', ellipsis: true,
              render: (_, row: VoucherRuleImportRow) => row.sourceCells.filter(Boolean).join(' | ') || '--',
            },
            {
              title: 'AI', width: 110,
              render: (_, row: VoucherRuleImportRow) => row.aiMapped
                ? <Tag color="green">已映射{row.confidence != null ? ` ${(row.confidence * 100).toFixed(0)}%` : ''}</Tag>
                : <Tag color="orange">待人工</Tag>,
            },
            {
              title: '业务类型/大类', width: 150, ellipsis: true,
              render: (_, row: VoucherRuleImportRow) => {
                const payload = payloadOf(row);
                return payload ? `${payload.businessType} / ${payload.category}` : '--';
              },
            },
            {
              title: '方向', width: 60,
              render: (_, row: VoucherRuleImportRow) => {
                const payload = payloadOf(row);
                return payload ? directionTag(payload.direction) : '--';
              },
            },
            {
              title: '备注', ellipsis: true,
              render: (_, row: VoucherRuleImportRow) => row.aiNote || '--',
            },
            {
              title: '操作', width: 100,
              render: (_, row: VoucherRuleImportRow) => <Button type="link" size="small" onClick={() => openJsonEditor(row)}>
                {payloadOf(row) ? '修正' : '填写'}
              </Button>,
            },
          ] as TableColumnsType<VoucherRuleImportRow>}
        />
        <Space style={{ marginTop: 16 }}>
          <Button onClick={() => setWizardStep(0)}>上一步</Button>
          <Button type="primary" loading={importing} onClick={() => void runConfirm()}>
            确认导入（{selectedRows.length} 行）
          </Button>
        </Space>
      </>}
      {wizardStep === 2 && <>
        <p>导入完成。规则清单已刷新，可在上方列表按分组查看。</p>
        <Button type="primary" onClick={() => setWizardOpen(false)}>关闭</Button>
      </>}
    </Modal>

    {/* 预览行 JSON 编辑 */}
    <Modal
      title={jsonRowEditing ? `编辑映射 · 第 ${jsonRowEditing.rowIndex + 1} 行` : '编辑映射'}
      open={Boolean(jsonRowEditing)}
      onCancel={() => setJsonRowEditing(null)}
      onOk={saveJsonRow}
      okText="保存映射"
      width={720}
      destroyOnHidden
    >
      {jsonRowEditing && <p className="table-sub">
        原文：{jsonRowEditing.sourceCells.filter(Boolean).join(' | ')}
      </p>}
      <Input.TextArea value={jsonRowText} onChange={(event) => setJsonRowText(event.target.value)} rows={16} className="mono" />
    </Modal>
  </>;
}
