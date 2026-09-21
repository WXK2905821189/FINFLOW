import { useCallback, useState } from 'react';
import { Alert, Button, Card, Descriptions, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, type TableColumnsType, message } from 'antd';
import { ApartmentOutlined, ApiOutlined, LinkOutlined, PlusOutlined } from '@ant-design/icons';
import { bankApi, bankPipelineApi, operationsApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag, DirectStatusTag } from '../shared/components';
import { CompanyArchiveDrawer } from './archive';
import { KingdeeMappingDrawer } from './kingdeeMapping';
import { useBankNames } from './useBankNames';
import { detectBankCode } from '../voucher/voucherTexts';
import type { BankAccount, BankConnectionTestResult, CompanyOption, ConnectionOverview } from '../../types';

/**
 * 银行账户页（V34 ⑧ WP-E 改版）：
 *  - 新增账户：必填降为 2 项（户名 + 账号）；银行按账号位数/前缀特征自动识别（chip 展示），
 *    识别失败强制手选兜底、不阻断保存；币种默认 CNY 可改；初始余额/归属公司主体可选；
 *  - 行内「测试连通」：服务端对适配器发起一次只读调用，弹层展示三态判定 + 失败原因 +
 *    链路说明（招行 = 直连 SDK / 中信 = 云证书 SDK）；不落库。
 */

/** 各银行通道说明（测试连通弹层展示）。 */
const CHANNEL_NOTES: Record<string, string> = {
  CMB: '招商银行：免前置直连 SDK（CloudDC 通道，SM2/SM4 国密）',
  CITIC: '中信银行：云证书 SDK 直连（TSEA 通道，证书与 MAC 绑定）',
};

const CONNECTION_TONE: Record<string, { color: 'success' | 'error' | 'warning' | 'info'; title: string }> = {
  CONNECTED: { color: 'success', title: '连通成功' },
  FAILED: { color: 'error', title: '银行返回失败' },
  DISABLED: { color: 'warning', title: '真实适配器未启用' },
  TIMEOUT: { color: 'warning', title: '连接超时' },
  PENDING: { color: 'info', title: '仍在处理' },
};

export function BankAccountPage() {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  // 银行中文名统一取字典中心（bank 字典类型），代码常量仅兜底：新增银行零发版。
  const { resolve: resolveBankName, options: bankOptions } = useBankNames();
  const canManageArchive = hasPermission('bank:manage');
  const canTriggerSync = hasPermission('bankdata:sync:trigger');
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [kingdeeOpen, setKingdeeOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [accountNumber, setAccountNumber] = useState('');
  const [creating, setCreating] = useState(false);
  const [testingId, setTestingId] = useState<number>();
  const [testModalOpen, setTestModalOpen] = useState(false);
  const [testResult, setTestResult] = useState<BankConnectionTestResult>();
  const [testError, setTestError] = useState<string>();
  const [testingBankCode, setTestingBankCode] = useState<string>();

  const loader = useCallback(() => bankApi.accounts(), []);
  const { data, loading, error, reload } = useRemote<BankAccount[]>(loader, [loader]);
  const overviewLoader = useCallback(() => operationsApi.connectionOverview(), []);
  const { data: overview } = useRemote<ConnectionOverview>(overviewLoader, [overviewLoader]);
  // V36 需求 2：成功态「已连接真实银行直联」横幅整删；错误态压缩为细条保留（必须报错）。
  const banner = overview && (overview.status !== 'REAL')
    ? <Alert type="error" banner showIcon message={`真实银行直联未连接 · ${overview.message || '服务端未装配真实银行适配器，当前无法获取银行数据。'}`} />
    : null;
  const companyOptionsLoader = useCallback(() => bankPipelineApi.companyOptions(), []);
  const { data: companyOptions } = useRemote<CompanyOption[]>(companyOptionsLoader, [companyOptionsLoader]);

  const detected = detectBankCode(accountNumber);

  const openCreate = () => {
    createForm.resetFields();
    setAccountNumber('');
    setCreateOpen(true);
  };

  const submitCreate = async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      await bankApi.createAccount({
        bankCode: values.bankCode,
        accountName: values.accountName.trim(),
        accountNumber: values.accountNumber.trim(),
        currency: values.currency || undefined,
        availableBalance: values.availableBalance ?? undefined,
        companyId: values.companyId ?? undefined,
      });
      message.success('银行账户已创建');
      setCreateOpen(false);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '新增账户未能完成');
    } finally {
      setCreating(false);
    }
  };

  const runTest = async (row: BankAccount) => {
    setTestingId(row.id);
    setTestResult(undefined);
    setTestError(undefined);
    setTestingBankCode(row.bankCode);
    setTestModalOpen(true);
    try {
      setTestResult(await bankApi.testConnection(row.id));
    } catch (reason) {
      setTestError(reason instanceof Error ? reason.message : '连通测试请求未能完成');
    } finally {
      setTestingId(undefined);
    }
  };

  const columns: TableColumnsType<BankAccount> = [
    { title: '银行', dataIndex: 'bankCode', width: 110, render: (value) => { const name = resolveBankName(value); return name === value ? <span className="mono">{value || '--'}</span> : name; } },
    { title: '账户名称', dataIndex: 'accountName' },
    { title: '账号', dataIndex: 'maskedAccountNumber', width: 170, render: (value) => <span className="mono">{value}</span> },
    { title: '币种', dataIndex: 'currency', width: 70 },
    { title: '账户状态', dataIndex: 'status', width: 100, render: (value) => <StatusTag status={value} /> },
    { title: '直联状态', width: 140, render: (_, row) => <DirectStatusTag status={row.directStatus} lastRealSyncAt={row.lastRealSyncAt} /> },
    {
      title: '金蝶账户',
      dataIndex: 'kingdeeAccountNumber',
      width: 210,
      ellipsis: true,
      render: (value: string | null | undefined, row: BankAccount) => value
        ? <span className="mono">{value}</span>
        : <span className="muted-inline">{row.accountingMode === 'MANUAL' ? '无需映射' : '未映射'}</span>,
    },
    ...(canManageArchive || canTriggerSync ? [{
      title: '操作', width: 120,
      render: (_value: unknown, row: BankAccount) => (
        <Button type="link" size="small" icon={<ApiOutlined />} loading={testingId === row.id} onClick={() => void runTest(row)}>
          测试连通
        </Button>
      ),
    }] : []),
  ];
  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">银行数据 / 账户</span>
        <h2>银行账户</h2>
        <p className="muted">展示当前企业已授权的银行账户；账号仅显示脱敏结果，余额和流水由真实银行直联采集任务更新。{canManageArchive ? '「新增账户」只需户名与账号，银行自动识别；「档案管理」可拖拽调整账户归属的公司主体。' : ''}</p>
      </div>
      <Space wrap>
        {canManageArchive && <Button icon={<ApartmentOutlined />} onClick={() => setArchiveOpen(true)}>档案管理</Button>}
        {canManageArchive && <Button icon={<LinkOutlined />} onClick={() => setKingdeeOpen(true)}>金蝶账户映射</Button>}
        {canManageArchive && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增账户</Button>}
      </Space>
    </div>
    {banner}
    <Card title="企业授权账户">{error ? <ResourceFailure error={error} onRetry={reload} /> : <Table rowKey="id" loading={loading} columns={columns} dataSource={data || []} pagination={false} locale={{ emptyText: <Empty description="当前企业暂无授权银行账户" /> }} scroll={{ x: 880 }} />}</Card>
    <CompanyArchiveDrawer open={archiveOpen} onClose={() => setArchiveOpen(false)} />
    <KingdeeMappingDrawer open={kingdeeOpen} onClose={() => setKingdeeOpen(false)} />

    {/* forceRender：openCreate 先调 createForm.resetFields 再打开弹窗，
        不预渲染 Form 会与 rc-field-form 的挂载校验抢时序，间歇打印 "useForm is not connected"。 */}
    <Modal
      title="新增银行账户"
      open={createOpen}
      onCancel={() => setCreateOpen(false)}
      onOk={() => void submitCreate()}
      okText="创建账户"
      cancelText="取消"
      confirmLoading={creating}
      destroyOnHidden
      forceRender
    >
      <Alert style={{ marginBottom: 14 }} type="info" showIcon
        message="只需填写户名与账号" description="银行按账号特征自动识别；识别不了时手动选择。币种默认人民币，初始余额与归属主体可留空。" />
      <Form form={createForm} layout="vertical" initialValues={{ currency: 'CNY' }}>
        <Form.Item name="accountName" label="户名" rules={[{ required: true, message: '请输入账户名称' }, { max: 128, message: '户名过长' }]}>
          <Input placeholder="如：中信银行基本户" />
        </Form.Item>
        <Form.Item name="accountNumber" label="账号" rules={[
          { required: true, message: '请输入银行账号' },
          { pattern: /^[0-9A-Za-z]{8,64}$/, message: '账号为 8-64 位数字或字母' },
        ]}>
          <Input
            className="mono"
            placeholder="输入账号后自动识别银行"
            value={accountNumber}
            onChange={(event) => {
              const value = event.target.value;
              setAccountNumber(value);
              // 识别命中即自动填入银行（用户可改）；未命中保持手选，不阻断保存。
              const hit = detectBankCode(value);
              if (hit) createForm.setFieldValue('bankCode', hit.code);
            }}
          />
        </Form.Item>
        <Form.Item label="银行" required style={{ marginBottom: 4 }}>
          {detected
            ? <Tag color="green" style={{ fontSize: 13, padding: '3px 10px' }}>已识别：{detected.bankName}</Tag>
            : <Tag color="warning" style={{ fontSize: 13, padding: '3px 10px' }}>未识别 —— 请手动选择</Tag>}
        </Form.Item>
        <Form.Item name="bankCode" rules={[{ required: true, message: '请选择银行（未识别时必选）' }]} extra={detected ? '已按账号特征自动填入，可修改。' : '账号特征未命中识别规则，请手动选择开户银行。'}>
          <Select
            placeholder="选择开户银行"
            options={bankOptions}
          />
        </Form.Item>
        <Space size={12} wrap>
          <Form.Item name="currency" label="币种" style={{ minWidth: 130 }}>
            <Select options={[{ value: 'CNY', label: 'CNY 人民币' }, { value: 'USD', label: 'USD 美元' }, { value: 'HKD', label: 'HKD 港币' }]} />
          </Form.Item>
          <Form.Item name="availableBalance" label="初始余额（可选）">
            <InputNumber min={0} precision={2} placeholder="默认 0.00" style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Form.Item name="companyId" label="归属公司主体（可选）" extra="缺省归属你所在的公司主体；跨公司归属需要跨公司数据权限，也可之后在「档案管理」中调整。">
          <Select
            allowClear
            placeholder="默认：当前公司主体"
            options={(companyOptions || []).map((option) => ({ value: option.id, label: option.name }))}
          />
        </Form.Item>
      </Form>
    </Modal>

    <Modal
      title={`连通测试${testingBankCode ? ' · ' + resolveBankName(testingBankCode) : ''}`}
      open={testModalOpen}
      onCancel={() => setTestModalOpen(false)}
      footer={<Button type="primary" onClick={() => setTestModalOpen(false)}>知道了</Button>}
    >
      {testingId != null ? <div style={{ textAlign: 'center', padding: '24px 0' }}><Spin /><div className="muted" style={{ marginTop: 8 }}>正在发起一次只读探测…</div></div>
        : testError ? <Alert type="error" showIcon message="测试未能完成" description={testError} />
          : testResult ? <>
            <Alert
              showIcon
              type={CONNECTION_TONE[testResult.result]?.color || 'info'}
              message={CONNECTION_TONE[testResult.result]?.title || testResult.result}
              description={testResult.message || '—'}
            />
            <Descriptions size="small" column={1} style={{ marginTop: 12 }}>
              {testResult.availableBalance != null && <Descriptions.Item label="银行反馈可用余额">{testResult.availableBalance} {testResult.currency || ''}</Descriptions.Item>}
              {testResult.bankRequestNo && <Descriptions.Item label="银行请求号"><span className="mono">{testResult.bankRequestNo}</span></Descriptions.Item>}
              {testResult.operation && <Descriptions.Item label="交易名"><span className="mono">{testResult.operation}</span></Descriptions.Item>}
              {testResult.durationMs != null && <Descriptions.Item label="耗时">{testResult.durationMs} ms</Descriptions.Item>}
              <Descriptions.Item label="通道说明">{CHANNEL_NOTES[testingBankCode || ''] || '—'}</Descriptions.Item>
            </Descriptions>
          </> : null}
    </Modal>
  </>;
}
