import { useCallback, useState } from 'react';
import { Button, Card, Empty, Form, Input, InputNumber, Modal, Pagination, Space, Table, message, type TableColumnsType } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { validationApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import type { PageResponse, ValidationRule, AccountingMapping } from '../../types';

/**
 * W4 规则中心（2026-09-18）合并改造：本模块的内容主体是 {@link ValidationEmbedded}，由规则中心
 * （`/voucher-rules`）的「校验与入账映射」Tab 内嵌渲染（去 page-heading 与过时告警条）。
 *
 * 旧路径 `/validation` 的兼容重定向已上移到 `App.tsx` 的「已下线页面」重定向块统一管理；
 * 本文件不再导出页面组件（原先的 `ValidationPage` 只是个 `<Navigate>` 壳，已删除）。
 */
export function ValidationEmbedded() {
  const canManage = useAuthStore((state) => state.hasPermission('validation:manage'));
  const [tab, setTab] = useState<'rules' | 'mappings'>('rules');
  const [page, setPage] = useState(1);
  const [form] = Form.useForm();
  const [open, setOpen] = useState(false);
  const loader = useCallback(() => tab === 'rules' ? validationApi.rules({ page, size: 20 }) : validationApi.mappings({ page, size: 20 }), [tab, page]);
  const { data, loading, error, reload } = useRemote<PageResponse<ValidationRule> | PageResponse<AccountingMapping>>(loader, [loader]);
  const save = async (values: Record<string, string | number>) => {
    try {
      if (tab === 'rules') await validationApi.createRule({ ruleCode: String(values.ruleCode), name: String(values.name), ruleType: String(values.ruleType), expression: String(values.expression), priority: Number(values.priority || 100) });
      else await validationApi.createMapping({ mappingCode: String(values.mappingCode), name: String(values.name), direction: String(values.direction), counterpartyKeyword: values.counterpartyKeyword ? String(values.counterpartyKeyword) : undefined, debitSubject: String(values.debitSubject), creditSubject: String(values.creditSubject), voucherTemplate: String(values.voucherTemplate) });
      message.success('草稿已保存'); setOpen(false); form.resetFields(); void reload();
    } catch (reason) { message.error(reason instanceof Error ? reason.message : '保存失败'); }
  };
  const activate = async (id: number, mapping: boolean) => { try { if (mapping) await validationApi.activateMapping(id); else await validationApi.activateRule(id); message.success('版本已启用'); void reload(); } catch (reason) { message.error(reason instanceof Error ? reason.message : '启用失败'); } };
  const rules = (data as PageResponse<ValidationRule> | undefined)?.records || [];
  const mappings = (data as PageResponse<AccountingMapping> | undefined)?.records || [];
  const columns: TableColumnsType<ValidationRule | AccountingMapping> = tab === 'rules'
    ? [{ title: '规则编码', dataIndex: 'ruleCode', render: (v) => <span className="mono">{v}</span> }, { title: '名称', dataIndex: 'name' }, { title: '类型', dataIndex: 'ruleType' }, { title: '表达式', dataIndex: 'expression', ellipsis: true }, { title: '版本', dataIndex: 'versionNo' }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '操作', render: (_, row) => canManage && row.status !== 'ACTIVE' ? <Button type="link" onClick={() => void activate(row.id, false)}>启用</Button> : null }]
    : [{ title: '映射编码', dataIndex: 'mappingCode', render: (v) => <span className="mono">{v}</span> }, { title: '名称', dataIndex: 'name' }, { title: '方向', dataIndex: 'direction' }, { title: '借方科目', dataIndex: 'debitSubject' }, { title: '贷方科目', dataIndex: 'creditSubject' }, { title: '凭证模板', dataIndex: 'voucherTemplate' }, { title: '版本', dataIndex: 'versionNo' }, { title: '状态', dataIndex: 'status', render: (v) => <StatusTag status={v} /> }, { title: '操作', render: (_, row) => canManage && row.status !== 'ACTIVE' ? <Button type="link" onClick={() => void activate(row.id, true)}>启用</Button> : null }];
  return <>
    <Card><Space className="segmented-tabs">
      <Button type={tab === 'rules' ? 'primary' : 'default'} onClick={() => { setTab('rules'); setPage(1); }}>校验规则</Button>
      <Button type={tab === 'mappings' ? 'primary' : 'default'} onClick={() => { setTab('mappings'); setPage(1); }}>入账映射</Button>
    </Space></Card>
    <Card>{error ? <ResourceFailure error={error} onRetry={reload} /> : <>
      <Space style={{ marginBottom: 12, justifyContent: 'space-between', width: '100%' }}>
        <span className="table-sub">规则和科目映射采用版本化草稿，启用后供后端校验/制证流程使用；原始流水不可在此修改。</span>
        {canManage && <Button type="primary" icon={<SettingOutlined />} onClick={() => setOpen(true)}>新建草稿</Button>}
      </Space>
      <Table rowKey="id" loading={loading} columns={columns} dataSource={tab === 'rules' ? rules : mappings} pagination={false} locale={{ emptyText: <Empty description="暂无规则草稿" /> }} scroll={{ x: 1000 }} />
      {data && data.total > data.size && <Pagination className="table-pagination" current={data.page} pageSize={data.size} total={data.total} onChange={setPage} />}
    </>}</Card>
    <Modal title={tab === 'rules' ? '新建校验规则草稿' : '新建入账映射草稿'} open={open} onCancel={() => { setOpen(false); form.resetFields(); }} onOk={() => void form.submit()} destroyOnHidden><Form form={form} layout="vertical" onFinish={save}>{tab === 'rules' ? <><Form.Item name="ruleCode" label="规则编码" rules={[{ required: true }]}><Input placeholder="例如 AMOUNT_POSITIVE" /></Form.Item><Form.Item name="name" label="规则名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="ruleType" label="规则类型" initialValue="FIELD" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="expression" label="表达式/说明" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item><Form.Item name="priority" label="优先级" initialValue={100}><InputNumber min={1} max={9999} /></Form.Item></> : <><Form.Item name="mappingCode" label="映射编码" rules={[{ required: true }]}><Input placeholder="例如 INCOME-SALES" /></Form.Item><Form.Item name="name" label="映射名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="direction" label="方向" initialValue="BOTH" rules={[{ required: true }]}><Input placeholder="INCOME / EXPENSE / BOTH" /></Form.Item><Form.Item name="counterpartyKeyword" label="对方关键字"><Input /></Form.Item><Form.Item name="debitSubject" label="借方科目" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="creditSubject" label="贷方科目" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="voucherTemplate" label="凭证模板" rules={[{ required: true }]}><Input /></Form.Item></>}</Form></Modal>
  </>;
}
