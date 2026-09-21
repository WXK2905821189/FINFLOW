import { useCallback, useMemo, useState } from 'react';
import {
  Button, Card, Empty, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Switch,
  Table, Tag, message, type TableColumnsType,
} from 'antd';
import { PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { kingdeeDimensionApi } from '../../services/api';
import type {
  DimensionMappingRow, DimensionMappingUpsertPayload, DimensionSlotRow, DimensionSlotUpsertPayload,
} from '../../types';
import { ResourceFailure, useRemote } from '../shared/components';

/**
 * 核算维度配置面板（V42，2026-09-21）——挂在规则中心第三页签。
 *
 * <p>两块内容：</p>
 * <ul>
 *   <li><b>槽位配置</b>：维度类型 → 金蝶弹性域键（FF100002 这类）。账套级配置，只能报错驱动
 *   试出；未配置时该维度不注入并在推送时报错提示（不猜槽位，避免静默记错账）。</li>
 *   <li><b>值映射</b>：FINFLOW 来源值 → 金蝶档案编码（供应商 VEN00511 这类）。金蝶核算维度
 *   要的是编码不是名称，这层翻译必须能由财务在界面上维护。</li>
 * </ul>
 *
 * <p>用户 2026-09-21 明确要求「可以在系统里直接修改，而不是只能通过代码修改」——因此
 * 两张表都开 CRUD + 批量粘贴导入（供应商/员工动辄数百条）。</p>
 */
const DIMENSION_TYPE_OPTIONS = [
  { value: 'BANK_ACCOUNT', label: 'BANK_ACCOUNT 银行账号' },
  { value: 'SUPPLIER', label: 'SUPPLIER 供应商' },
  { value: 'CUSTOMER', label: 'CUSTOMER 客户' },
  { value: 'EMPLOYEE', label: 'EMPLOYEE 员工' },
  { value: 'BUSINESS_LINE', label: 'BUSINESS_LINE 业务线' },
];

const SOURCE_KIND_OPTIONS = [
  { value: 'NAME', label: 'NAME 精确匹配' },
  { value: 'KEYWORD', label: 'KEYWORD 包含匹配' },
  { value: 'ACCOUNT', label: 'ACCOUNT 我方账号' },
  { value: 'CODE', label: 'CODE 编码精确' },
];

type SlotFormValues = {
  dimensionType: string;
  dimensionName?: string;
  dimensionCode?: string;
  slot?: string;
  dimensionKind?: string;
  enabled: boolean;
  remark?: string;
};

type MappingFormValues = {
  dimensionType: string;
  sourceKey: string;
  sourceKind: string;
  kingdeeValue: string;
  kingdeeName?: string;
  orgCode?: string;
  enabled: boolean;
  remark?: string;
};

/** 批量粘贴解析：制表符分隔（Excel 直接复制），列序 维度类型/来源值/金蝶编码/金蝶名称/组织。 */
export function parseBatchRows(text: string): DimensionMappingUpsertPayload[] {
  const rows: DimensionMappingUpsertPayload[] = [];
  text.split(/\r?\n/).forEach((raw, index) => {
    const line = raw.trim();
    if (!line) {
      return;
    }
    const cells = line.split('\t').map((cell) => cell.trim());
    if (cells.length < 3 || !cells[0] || !cells[1] || !cells[2]) {
      throw new Error(`第 ${index + 1} 行格式不对：需要「维度类型 / 来源值 / 金蝶编码」（制表符分隔）`);
    }
    rows.push({
      dimensionType: cells[0].toUpperCase(),
      sourceKey: cells[1],
      kingdeeValue: cells[2],
      kingdeeName: cells[3] || null,
      orgCode: cells[4] || '',
      sourceKind: 'NAME',
      enabled: true,
    });
  });
  if (!rows.length) {
    throw new Error('没有解析到任何数据行');
  }
  return rows;
}

export function DimensionMappingPanel() {
  const [tab, setTab] = useState<'slots' | 'values'>('slots');

  // ---------------- 槽位配置 ----------------
  const slotsLoader = useCallback(() => kingdeeDimensionApi.slots(), []);
  const { data: slots, loading: slotsLoading, error: slotsError, reload: reloadSlots } = useRemote(slotsLoader, [slotsLoader]);
  const [slotOpen, setSlotOpen] = useState(false);
  const [slotEditing, setSlotEditing] = useState<DimensionSlotRow>();
  const [slotSaving, setSlotSaving] = useState(false);
  const [slotForm] = Form.useForm<SlotFormValues>();

  // ---------------- 值映射 ----------------
  const [dimFilter, setDimFilter] = useState<string>();
  const mappingsLoader = useCallback(() => kingdeeDimensionApi.mappings(dimFilter), [dimFilter]);
  const { data: mappings, loading: mappingsLoading, error: mappingsError, reload: reloadMappings } = useRemote(mappingsLoader, [mappingsLoader]);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [mappingEditing, setMappingEditing] = useState<DimensionMappingRow>();
  const [mappingSaving, setMappingSaving] = useState(false);
  const [mappingForm] = Form.useForm<MappingFormValues>();

  const [batchOpen, setBatchOpen] = useState(false);
  const [batchText, setBatchText] = useState('');
  const [batchSaving, setBatchSaving] = useState(false);

  const typeOptions = useMemo(() => {
    const known = new Map(DIMENSION_TYPE_OPTIONS.map((item) => [item.value, item.label]));
    (slots ?? []).forEach((slot) => {
      if (!known.has(slot.dimensionType)) {
        known.set(slot.dimensionType, slot.dimensionType);
      }
    });
    return Array.from(known, ([value, label]) => ({ value, label }));
  }, [slots]);

  const openSlotCreate = () => {
    setSlotEditing(undefined);
    slotForm.setFieldsValue({ dimensionType: '', dimensionName: '', dimensionCode: '', slot: '', dimensionKind: 'BASE_DATA', enabled: true, remark: '' });
    setSlotOpen(true);
  };

  const openSlotEdit = (row: DimensionSlotRow) => {
    setSlotEditing(row);
    slotForm.setFieldsValue({
      dimensionType: row.dimensionType,
      dimensionName: row.dimensionName ?? '',
      dimensionCode: row.dimensionCode ?? '',
      slot: row.slot ?? '',
      dimensionKind: row.dimensionKind ?? 'BASE_DATA',
      enabled: row.enabled !== false,
      remark: row.remark ?? '',
    });
    setSlotOpen(true);
  };

  const submitSlot = async () => {
    const values = await slotForm.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    setSlotSaving(true);
    try {
      if (slotEditing) {
        await kingdeeDimensionApi.updateSlot(slotEditing.id, values);
        message.success('槽位配置已更新');
      } else {
        await kingdeeDimensionApi.createSlot(values);
        message.success('槽位配置已创建');
      }
      setSlotOpen(false);
      await reloadSlots();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '槽位保存失败');
    } finally {
      setSlotSaving(false);
    }
  };

  const openMappingCreate = () => {
    setMappingEditing(undefined);
    mappingForm.setFieldsValue({
      dimensionType: dimFilter ?? 'SUPPLIER', sourceKey: '', sourceKind: 'NAME',
      kingdeeValue: '', kingdeeName: '', orgCode: '', enabled: true, remark: '',
    });
    setMappingOpen(true);
  };

  const openMappingEdit = (row: DimensionMappingRow) => {
    setMappingEditing(row);
    mappingForm.setFieldsValue({
      dimensionType: row.dimensionType,
      sourceKey: row.sourceKey,
      sourceKind: row.sourceKind ?? 'NAME',
      kingdeeValue: row.kingdeeValue,
      kingdeeName: row.kingdeeName ?? '',
      orgCode: row.orgCode ?? '',
      enabled: row.enabled !== false,
      remark: row.remark ?? '',
    });
    setMappingOpen(true);
  };

  const submitMapping = async () => {
    const values = await mappingForm.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    setMappingSaving(true);
    try {
      if (mappingEditing) {
        await kingdeeDimensionApi.updateMapping(mappingEditing.id, values);
        message.success('维度映射已更新');
      } else {
        await kingdeeDimensionApi.createMapping(values);
        message.success('维度映射已创建');
      }
      setMappingOpen(false);
      await reloadMappings();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '维度映射保存失败');
    } finally {
      setMappingSaving(false);
    }
  };

  const submitBatch = async () => {
    let rows: DimensionMappingUpsertPayload[];
    try {
      rows = parseBatchRows(batchText);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '粘贴内容解析失败');
      return;
    }
    setBatchSaving(true);
    try {
      const affected = await kingdeeDimensionApi.batchUpsert(rows);
      message.success(`已导入 ${affected} 条维度映射（已存在的按更新处理）`);
      setBatchOpen(false);
      setBatchText('');
      await reloadMappings();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '批量导入失败');
    } finally {
      setBatchSaving(false);
    }
  };

  const slotColumns: TableColumnsType<DimensionSlotRow> = [
    { title: '维度类型', dataIndex: 'dimensionType', width: 170 },
    { title: '金蝶维度名称', dataIndex: 'dimensionName', width: 150, render: (value: string | null) => value || '--' },
    { title: '维度编码', dataIndex: 'dimensionCode', width: 120, render: (value: string | null) => value || '--' },
    {
      title: '弹性域槽位', dataIndex: 'slot', width: 160,
      render: (value: string | null) => value
        ? <Tag color="green">{value}</Tag>
        : <Tag color="orange">待试出</Tag>,
    },
    { title: '种类', dataIndex: 'dimensionKind', width: 110, render: (value: string | null) => value || '--' },
    {
      title: '状态', dataIndex: 'enabled', width: 90,
      render: (value: boolean | null) => (value === false ? <Tag>停用</Tag> : <Tag color="blue">启用</Tag>),
    },
    { title: '备注', dataIndex: 'remark', render: (value: string | null) => value || '--' },
    {
      title: '操作', width: 130,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => openSlotEdit(row)}>编辑</Button>
          <Popconfirm title="删除该槽位配置？" onConfirm={() => void kingdeeDimensionApi.removeSlot(row.id).then(reloadSlots).then(() => message.success('已删除'))}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const mappingColumns: TableColumnsType<DimensionMappingRow> = [
    { title: '维度类型', dataIndex: 'dimensionType', width: 150 },
    { title: '来源值（FINFLOW 侧）', dataIndex: 'sourceKey', width: 220 },
    { title: '比较方式', dataIndex: 'sourceKind', width: 110 },
    { title: '金蝶档案编码', dataIndex: 'kingdeeValue', width: 160, render: (value: string) => <Tag color="green">{value}</Tag> },
    { title: '金蝶档案名称', dataIndex: 'kingdeeName', width: 180, render: (value: string | null) => value || '--' },
    {
      title: '限定组织', dataIndex: 'orgCode', width: 110,
      render: (value: string) => value ? value : <span className="muted">通用</span>,
    },
    {
      title: '状态', dataIndex: 'enabled', width: 90,
      render: (value: boolean | null) => (value === false ? <Tag>停用</Tag> : <Tag color="blue">启用</Tag>),
    },
    {
      title: '操作', width: 130,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => openMappingEdit(row)}>编辑</Button>
          <Popconfirm title="删除该映射？" onConfirm={() => void kingdeeDimensionApi.removeMapping(row.id).then(reloadMappings).then(() => message.success('已删除'))}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Card
        title={<>核算维度配置 <span className="table-sub">槽位是账套级配置（报错驱动试出）；值映射把 FINFLOW 名称翻译成金蝶档案编码</span></>}
        extra={<Space wrap>
          <Segmented
            value={tab}
            onChange={(value) => setTab(value as 'slots' | 'values')}
            options={[{ label: '槽位配置', value: 'slots' }, { label: '值映射', value: 'values' }]}
          />
          {tab === 'slots'
            ? <Button icon={<ReloadOutlined />} onClick={() => void reloadSlots()}>刷新</Button>
            : <>
              <Select
                allowClear
                placeholder="按维度类型筛选"
                style={{ width: 200 }}
                value={dimFilter}
                onChange={(value) => setDimFilter(value)}
                options={typeOptions}
              />
              <Button icon={<ReloadOutlined />} onClick={() => void reloadMappings()}>刷新</Button>
              <Button icon={<UploadOutlined />} onClick={() => setBatchOpen(true)}>批量粘贴导入</Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={openMappingCreate}>新增映射</Button>
            </>}
        </Space>}
      >
        {tab === 'slots' ? (
          slotsError ? <ResourceFailure error={slotsError} onRetry={reloadSlots} /> : (
            <Table
              rowKey="id"
              size="small"
              loading={slotsLoading}
              columns={slotColumns}
              dataSource={slots || []}
              pagination={false}
              locale={{ emptyText: <Empty description="暂无槽位配置" /> }}
            />
          )
        ) : (
          mappingsError ? <ResourceFailure error={mappingsError} onRetry={reloadMappings} /> : (
            <Table
              rowKey="id"
              size="small"
              loading={mappingsLoading}
              columns={mappingColumns}
              dataSource={mappings || []}
              pagination={{ pageSize: 20, showSizeChanger: false }}
              locale={{ emptyText: <Empty description="暂无值映射（推送时未命中的维度会阻断并在此提示补齐）" /> }}
            />
          )
        )}
      </Card>

      <Modal
        open={slotOpen}
        title={slotEditing ? `编辑槽位：${slotEditing.dimensionType}` : '新增槽位配置'}
        onCancel={() => setSlotOpen(false)}
        onOk={() => void submitSlot()}
        confirmLoading={slotSaving}
        okText="保存"
        destroyOnClose
      >
        <Form form={slotForm} layout="vertical">
          <Form.Item name="dimensionType" label="维度类型" rules={[{ required: true, message: '必填' }]} tooltip="与规则模板 dimension/dimensions.type 同字面量">
            <Select disabled={!!slotEditing} options={typeOptions} showSearch />
          </Form.Item>
          <Form.Item name="dimensionName" label="金蝶维度名称" tooltip="与账套「核算维度」界面的名称逐字对齐，便于核对">
            <Input placeholder="如 银行账号 / 供应商 / 业务线" />
          </Form.Item>
          <Form.Item name="dimensionCode" label="金蝶维度编码" tooltip="自定义维度形如 ZDY0001；账套预置维度可留空">
            <Input placeholder="如 ZDY0001" />
          </Form.Item>
          <Form.Item name="slot" label="弹性域槽位" tooltip="形如 FF100002；未试出前留空，推送时该维度不注入并提示">
            <Input placeholder="如 FF100002（待报错驱动试出后可填）" />
          </Form.Item>
          <Form.Item name="dimensionKind" label="维度种类">
            <Select options={[
              { value: 'BASE_DATA', label: 'BASE_DATA 基础资料' },
              { value: 'ASSIST', label: 'ASSIST 辅助资料' },
              { value: 'CUSTOM', label: 'CUSTOM 自定义维度' },
            ]} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={mappingOpen}
        title={mappingEditing ? '编辑维度映射' : '新增维度映射'}
        onCancel={() => setMappingOpen(false)}
        onOk={() => void submitMapping()}
        confirmLoading={mappingSaving}
        okText="保存"
        destroyOnClose
      >
        <Form form={mappingForm} layout="vertical">
          <Form.Item name="dimensionType" label="维度类型" rules={[{ required: true, message: '必填' }]}>
            <Select disabled={!!mappingEditing} options={typeOptions} showSearch />
          </Form.Item>
          <Form.Item
            name="sourceKey"
            label="来源值（FINFLOW 侧）"
            rules={[{ required: true, message: '必填' }]}
            tooltip="对手方名称 / 员工姓名 / 业务线关键词；与流水字段逐字比对"
          >
            <Input disabled={!!mappingEditing} placeholder="如 某某科技有限公司" />
          </Form.Item>
          <Form.Item name="sourceKind" label="比较方式">
            <Select options={SOURCE_KIND_OPTIONS} />
          </Form.Item>
          <Form.Item
            name="kingdeeValue"
            label="金蝶档案编码"
            rules={[{ required: true, message: '必填' }]}
            tooltip="金蝶核算维度要的是编码（如 VEN00511），不是名称"
          >
            <Input placeholder="如 VEN00511" />
          </Form.Item>
          <Form.Item name="kingdeeName" label="金蝶档案名称">
            <Input />
          </Form.Item>
          <Form.Item name="orgCode" label="限定组织" tooltip="留空 = 通用；同一来源值在不同主体对应不同档案时填组织编码消歧">
            <Input placeholder="如 410；留空表示通用" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={batchOpen}
        title="批量粘贴导入维度映射"
        onCancel={() => setBatchOpen(false)}
        onOk={() => void submitBatch()}
        confirmLoading={batchSaving}
        okText="导入"
        width={640}
        destroyOnClose
      >
        <p className="table-sub" style={{ marginBottom: 8 }}>
          每行一条，制表符分隔（Excel 直接复制粘贴即可）：维度类型 / 来源值 / 金蝶档案编码 / 金蝶档案名称 / 限定组织（后两列可空）。
          同「维度类型 + 来源值 + 组织」已存在的行按更新处理，可反复导入修订表。
        </p>
        <Input.TextArea
          rows={10}
          value={batchText}
          onChange={(event) => setBatchText(event.target.value)}
          placeholder={'SUPPLIER\t某某科技有限公司\tVEN00511\t某某科技有限公司\t410\nEMPLOYEE\t张三\tEMP01023\t张三\t410'}
        />
      </Modal>
    </>
  );
}
