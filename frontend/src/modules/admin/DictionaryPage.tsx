import { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { dictApi } from '../../services/api';
import type { DictItemRow, DictItemPayload, DictTypeRow, DictTypePayload } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' },
];

function StatusTag({ status }: { status: string }) {
  return status === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>;
}

/** 校验并规范化扩展属性输入：空 → null，否则必须是 JSON 对象。返回格式化后的字符串。 */
function normalizeExtraJson(raw: string | undefined): string | null {
  const trimmed = (raw || '').trim();
  if (!trimmed) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    throw new Error('扩展属性不是合法 JSON');
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('扩展属性必须是 JSON 对象（键值对），如 {"税号":"91..."}');
  }
  return JSON.stringify(parsed);
}

function tryParseObject(raw: string | null): Record<string, unknown> | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function DictionaryPage() {
  const [selectedTypeId, setSelectedTypeId] = useState<number | null>(null);
  const [typeModalOpen, setTypeModalOpen] = useState(false);
  const [editingType, setEditingType] = useState<DictTypeRow | null>(null);
  const [itemModalOpen, setItemModalOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<DictItemRow | null>(null);
  const [detailItem, setDetailItem] = useState<DictItemRow | null>(null);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [typeForm] = Form.useForm();
  const [itemForm] = Form.useForm();

  const typesLoader = useCallback(() => dictApi.listTypes(), []);
  const { data: types, loading: typesLoading, error: typesError, reload: reloadTypes } =
    useRemote<DictTypeRow[]>(typesLoader, [typesLoader]);

  const selectedType = useMemo(
    () => (types || []).find((type) => type.id === selectedTypeId) || null,
    [types, selectedTypeId],
  );

  const itemsLoader = useCallback(
    () => (selectedTypeId == null ? Promise.resolve([]) : dictApi.listItems(selectedTypeId)),
    [selectedTypeId],
  );
  const { data: items, loading: itemsLoading, error: itemsError, reload: reloadItems } =
    useRemote<DictItemRow[]>(itemsLoader, [itemsLoader]);

  const openCreateType = () => {
    typeForm.resetFields();
    typeForm.setFieldsValue({ status: 'ACTIVE' });
    setEditingType(null);
    setTypeModalOpen(true);
  };

  const openEditType = (type: DictTypeRow) => {
    typeForm.resetFields();
    typeForm.setFieldsValue({ name: type.name, description: type.description, status: type.status });
    setEditingType(type);
    setTypeModalOpen(true);
  };

  const submitType = async () => {
    const values = await typeForm.validateFields();
    setConfirmLoading(true);
    try {
      if (editingType) {
        const payload: DictTypePayload = { name: values.name, description: values.description, status: values.status };
        await dictApi.updateType(editingType.id, payload);
        message.success('字典类型已更新');
      } else {
        const payload: DictTypePayload = { typeCode: values.typeCode, name: values.name, description: values.description, status: values.status };
        const created = await dictApi.createType(payload);
        message.success('字典类型已创建');
        setSelectedTypeId(created.id);
      }
      setTypeModalOpen(false);
      reloadTypes();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '保存失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const removeType = async (type: DictTypeRow, force: boolean) => {
    try {
      await dictApi.deleteType(type.id, force);
      message.success('字典类型已删除');
      if (selectedTypeId === type.id) setSelectedTypeId(null);
      reloadTypes();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '删除失败');
    }
  };

  const openCreateItem = () => {
    if (!selectedType) return;
    itemForm.resetFields();
    itemForm.setFieldsValue({ status: 'ACTIVE', sortNo: 0 });
    setEditingItem(null);
    setItemModalOpen(true);
  };

  const openEditItem = (item: DictItemRow) => {
    itemForm.resetFields();
    itemForm.setFieldsValue({
      itemCode: item.itemCode,
      label: item.label,
      extraJson: item.extraJson ? JSON.stringify(tryParseObject(item.extraJson) ?? item.extraJson, null, 2) : '',
      sortNo: item.sortNo,
      status: item.status,
      remark: item.remark,
    });
    setEditingItem(item);
    setItemModalOpen(true);
  };

  const submitItem = async () => {
    if (!selectedType) return;
    const values = await itemForm.validateFields();
    let extraJson: string | null;
    try {
      extraJson = normalizeExtraJson(values.extraJson);
    } catch (normalizeError) {
      message.error(normalizeError instanceof Error ? normalizeError.message : '扩展属性不合法');
      return;
    }
    setConfirmLoading(true);
    try {
      const payload: DictItemPayload = {
        itemCode: values.itemCode,
        label: values.label,
        extraJson,
        sortNo: values.sortNo,
        status: values.status,
        remark: values.remark,
      };
      if (editingItem) {
        await dictApi.updateItem(editingItem.id, payload);
        message.success('字典项已更新');
      } else {
        await dictApi.createItem(selectedType.id, payload);
        message.success('字典项已创建');
      }
      setItemModalOpen(false);
      reloadItems();
      reloadTypes();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '保存失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const removeItem = async (item: DictItemRow) => {
    try {
      await dictApi.deleteItem(item.id);
      message.success('字典项已删除');
      reloadItems();
      reloadTypes();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '删除失败');
    }
  };

  const typeColumns: TableColumnsType<DictTypeRow> = [
    {
      title: '字典类型',
      dataIndex: 'name',
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <span>{row.name}</span>
          <span style={{ fontFamily: 'monospace', color: 'var(--ant-color-text-tertiary, #999)' }}>{row.typeCode}</span>
        </Space>
      ),
    },
    { title: '项数', dataIndex: 'itemCount', width: 70, align: 'right' },
    { title: '状态', dataIndex: 'status', width: 80, render: (status: string) => <StatusTag status={status} /> },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      render: (_, row) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => setSelectedTypeId(row.id)}>查看项</Button>
          <Button type="link" size="small" onClick={() => openEditType(row)}>编辑</Button>
          <Popconfirm
            title={row.itemCount > 0 ? `该字典下有 ${row.itemCount} 个字典项，将一并删除，确定？` : '确定删除该字典类型？'}
            onConfirm={() => void removeType(row, row.itemCount > 0)}
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const itemColumns: TableColumnsType<DictItemRow> = [
    {
      title: '项标识',
      dataIndex: 'itemCode',
      width: 140,
      render: (value: string) => <span style={{ fontFamily: 'monospace' }}>{value}</span>,
    },
    { title: '显示名称', dataIndex: 'label' },
    {
      title: '扩展属性',
      dataIndex: 'extraJson',
      render: (raw: string | null) => {
        const object = tryParseObject(raw);
        if (!object) return <span style={{ color: '#999' }}>--</span>;
        const keys = Object.keys(object);
        return (
          <span>
            {keys.slice(0, 3).map((key) => (
              <Tag key={key} style={{ marginBottom: 2 }}>{key}</Tag>
            ))}
            {keys.length > 3 && <Tag style={{ marginBottom: 2 }}>+{keys.length - 3}</Tag>}
          </span>
        );
      },
    },
    { title: '排序', dataIndex: 'sortNo', width: 70, align: 'right' },
    { title: '状态', dataIndex: 'status', width: 80, render: (status: string) => <StatusTag status={status} /> },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, row) => (
        <Space size="small">
          {row.extraJson && (
            <Button type="link" size="small" onClick={() => setDetailItem(row)}>属性</Button>
          )}
          <Button type="link" size="small" onClick={() => openEditItem(row)}>编辑</Button>
          <Popconfirm title="确定删除该字典项？" onConfirm={() => void removeItem(row)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {typesError ? <ResourceFailure error={typesError} onRetry={reloadTypes} /> : null}
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <Card
          title="字典类型"
          extra={
            <Space>
              <Button icon={<ReloadOutlined />} size="small" onClick={() => reloadTypes()} />
              <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openCreateType}>新建类型</Button>
            </Space>
          }
          style={{ width: 420, flexShrink: 0 }}
        >
          <Table<DictTypeRow>
            rowKey="id"
            size="small"
            loading={typesLoading}
            dataSource={types || []}
            columns={typeColumns}
            pagination={false}
            rowClassName={(row) => (row.id === selectedTypeId ? 'ant-table-row-selected' : '')}
            locale={{ emptyText: <Empty description="暂无字典类型" /> }}
          />
        </Card>

        <Card
          title={selectedType ? `字典项 · ${selectedType.name}` : '字典项'}
          extra={
            <Button type="primary" size="small" icon={<PlusOutlined />} disabled={!selectedType} onClick={openCreateItem}>
              新建字典项
            </Button>
          }
          style={{ flex: 1, minWidth: 0 }}
        >
          {!selectedType && <Empty description="左侧选择一个字典类型后管理其字典项" />}
          {selectedType && (
            <>
              {itemsError && <ResourceFailure error={itemsError} onRetry={reloadItems} />}
              {selectedType.description && (
                <Alert type="info" showIcon message={selectedType.description} style={{ marginBottom: 12 }} />
              )}
              <Table<DictItemRow>
                rowKey="id"
                size="small"
                loading={itemsLoading}
                dataSource={items || []}
                columns={itemColumns}
                pagination={false}
                locale={{ emptyText: <Empty description="该字典暂无字典项" /> }}
              />
            </>
          )}
        </Card>
      </div>

      <Modal
        title={editingType ? '编辑字典类型' : '新建字典类型'}
        open={typeModalOpen}
        onOk={() => void submitType()}
        onCancel={() => setTypeModalOpen(false)}
        confirmLoading={confirmLoading}
        destroyOnClose
      >
        <Form form={typeForm} layout="vertical">
          <Form.Item
            name="typeCode"
            label="字典标识（typeCode，创建后不可修改）"
            rules={editingType ? [] : [
              { required: true, message: '请输入字典标识' },
              { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '小写字母开头，仅字母/数字/下划线，2~64 位' },
            ]}
            extra="消费方按此标识取数，如 company_entity"
          >
            <Input placeholder="company_entity" disabled={!!editingType} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="公司主体" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="用途说明，供使用方理解" />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={STATUS_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingItem ? '编辑字典项' : '新建字典项'}
        open={itemModalOpen}
        onOk={() => void submitItem()}
        onCancel={() => setItemModalOpen(false)}
        confirmLoading={confirmLoading}
        destroyOnClose
        width={560}
      >
        <Form form={itemForm} layout="vertical">
          <Form.Item
            name="itemCode"
            label="项标识"
            rules={[
              { required: true, message: '请输入项标识' },
              { pattern: /^[A-Za-z0-9_-]{1,64}$/, message: '仅字母/数字/中划线/下划线，1~64 位' },
            ]}
          >
            <Input placeholder="xyrc" />
          </Form.Item>
          <Form.Item name="label" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}>
            <Input placeholder="北京雪云锐创科技有限公司" />
          </Form.Item>
          <Form.Item
            name="extraJson"
            label="扩展属性（JSON 对象，可选）"
            extra='自定义键值，如 {"税号":"91...","开户行":"招商银行","银行账号":"12..."}；新增字段无需改代码'
          >
            <Input.TextArea rows={4} placeholder='{"税号":"91...","开户行":"..."}' style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Space size="large" style={{ display: 'flex' }}>
            <Form.Item name="sortNo" label="排序值" initialValue={0}>
              <Input type="number" style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="status" label="状态" initialValue="ACTIVE" rules={[{ required: true }]}>
              <Select options={STATUS_OPTIONS} style={{ width: 120 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input placeholder="仅管理端可见" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={detailItem ? `扩展属性 · ${detailItem.label}` : '扩展属性'}
        open={detailItem != null}
        onClose={() => setDetailItem(null)}
        width={480}
      >
        {detailItem && (
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontFamily: 'monospace' }}>
            {JSON.stringify(tryParseObject(detailItem.extraJson) ?? detailItem.extraJson, null, 2)}
          </pre>
        )}
      </Drawer>
    </div>
  );
}
