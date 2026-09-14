import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd';
import { ApiOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { aiApi } from '../../services/api';
import type { AiConfigView } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime } from '../shared/format';

/**
 * AI 设置（V28，系统管理）：页面上配置 LLM 供应商——接入点 / API 密钥 / 模型 /
 * 能力开关，保存即时生效（DB 在线配置覆盖环境变量，无需重启）。
 * 模型选择：Base URL + 密钥就绪后可自动/手动拉取 OpenAI 兼容 GET /models 列表，
 * 也允许自由输入（部分网关不暴露 /models）。
 * 密钥安全红线：只写不读——已配置的密钥不回显（仅显示尾 4 位 hint），
 * 留空提交 = 保持不变。
 */
export function AiSettingsPage() {
  const [form] = Form.useForm<FormValues>();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [models, setModels] = useState<string[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);

  const loader = useCallback(() => aiApi.getConfig(), []);
  const { data, loading, error, reload } = useRemote<AiConfigView>(loader, [loader]);

  // 首次加载 / 重新拉取后，把 DB 现状铺进表单（密钥永远不铺，只铺占位提示）
  useEffect(() => {
    if (!data) return;
    form.setFieldsValue({
      enabled: data.effective.enabled,
      baseUrl: data.db?.baseUrl ?? data.effective.baseUrl,
      model: data.db?.model ?? data.effective.model,
      timeoutMillis: data.db?.timeoutMillis ?? undefined,
      maxRetries: data.db?.maxRetries ?? undefined,
      capabilitiesSelfTest: data.effective.capabilities['self-test'] === true,
      capabilitiesAccountingSuggestion: data.effective.capabilities['accounting-suggestion'] === true,
    });
  }, [data, form]);

  const buildPayload = (values: FormValues) => ({
    enabled: values.enabled,
    baseUrl: values.baseUrl,
    // 密钥框留空 = 不携带该字段（保持现有密钥）；输入非空 = 换新密钥。
    // 「清除密钥」走后端语义需显式空串，这里不提供（避免误清导致停摆）。
    ...(values.apiKey ? { apiKey: values.apiKey.trim() } : {}),
    model: values.model,
    timeoutMillis: values.timeoutMillis ?? undefined,
    maxRetries: values.maxRetries ?? undefined,
    capabilities: {
      'self-test': values.capabilitiesSelfTest === true,
      'accounting-suggestion': values.capabilitiesAccountingSuggestion === true,
    },
  });

  const save = async () => {
    if (saving) return;
    const values = await form.validateFields();
    setSaving(true);
    setTestResult(null);
    try {
      await aiApi.updateConfig(buildPayload(values));
      // 就绪结论即时判断（reload 数据异步，不依赖它）
      const willBeReady = values.enabled === true
        && (Boolean(values.apiKey && values.apiKey.trim()) || Boolean(db?.apiKeyConfigured))
        && Boolean(values.model && String(values.model).trim());
      message.success(willBeReady
        ? '已保存并即时生效（无需重启）'
        : '已保存。注意：总开关 / 密钥 / 模型尚未全部就绪，AI 调用仍会被拒绝（403）');
      form.setFieldValue('apiKey', undefined);
      await reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存未能完成');
    } finally {
      setSaving(false);
    }
  };

  // 拉模型：用表单当前的 baseUrl + 密钥（密钥留空则用已保存密钥），可未保存
  const fetchModels = async (silent = false) => {
    if (loadingModels) return;
    const baseUrl = form.getFieldValue('baseUrl') as string | undefined;
    const apiKey = form.getFieldValue('apiKey') as string | undefined;
    if (!baseUrl || !baseUrl.trim()) {
      if (!silent) message.warning('请先填写接入点 Base URL');
      return;
    }
    setLoadingModels(true);
    try {
      const list = await aiApi.listModels({
        baseUrl: baseUrl.trim(),
        ...(apiKey && apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
      });
      setModels(list);
      if (!silent) message.success(`已拉取 ${list.length} 个模型`);
    } catch (reason) {
      if (!silent) {
        message.error(reason instanceof Error ? reason.message : '模型拉取失败');
      }
    } finally {
      setLoadingModels(false);
    }
  };

  const testConnection = async () => {
    if (testing) return;
    const values = await form.validateFields();
    setTesting(true);
    setTestResult(null);
    try {
      const result = await aiApi.testConfig({
        baseUrl: values.baseUrl,
        ...(values.apiKey ? { apiKey: values.apiKey.trim() } : {}),
        model: values.model,
      });
      setTestResult(`回信「${result.reply}」· ${result.model} · ${result.durationMillis}ms`);
      message.success('连接成功');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '连接测试未能完成');
    } finally {
      setTesting(false);
    }
  };

  const db = data?.db;
  const effective = data?.effective;
  // 就绪判定（后端 guard fail-closed 同口径）：总开关 + 密钥 + 模型缺一，能力开关开了也不生效
  const readyGaps: string[] = effective
    ? [
        ...(!effective.enabled ? ['AI 总开关未启用'] : []),
        ...(!effective.apiKeyConfigured ? ['API 密钥未配置'] : []),
        ...(!effective.model ? ['模型未选择'] : []),
      ]
    : [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div className="page-heading">
        <div>
          <span className="section-kicker">系统管理 / AI</span>
          <h2>AI 设置</h2>
          <p className="muted">
            配置大模型接入点、密钥与能力开关；保存即时生效。密钥只写不读，明文永不回显。
          </p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
        </Space>
      </div>

      {error ? (
        <Card><ResourceFailure error={error} onRetry={reload} /></Card>
      ) : (
        <>
          {effective && (
            <Card title="当前生效配置" size="small" loading={loading}>
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label="总开关">
                  {effective.enabled ? <Tag color="green">已启用</Tag> : <Tag color="default">未启用（fail-closed）</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="密钥">
                  {effective.apiKeyConfigured ? <Tag color="green">已配置{db?.apiKeyHint ? `（尾 4 位 ${db.apiKeyHint.replace(/\*/g, '')}）` : ''}</Tag> : <Tag color="warning">未配置</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="提供方">{effective.provider}</Descriptions.Item>
                <Descriptions.Item label="配置来源">{effective.configSource}</Descriptions.Item>
                <Descriptions.Item label="接入点">{effective.baseUrl}</Descriptions.Item>
                <Descriptions.Item label="模型">{effective.model}</Descriptions.Item>
                {db?.updatedAt && (
                  <Descriptions.Item label="最近保存">{dateTime(db.updatedAt)}（操作人 {db.updatedBy ?? '-'}）</Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          )}

          <Card title="编辑配置（DB 在线配置，逐项覆盖环境变量）" loading={loading}>
            <Form form={form} layout="vertical" style={{ maxWidth: 720 }}>
              <Form.Item label="AI 总开关" name="enabled" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
              <Form.Item
                label="接入点（OpenAI 兼容 Base URL）"
                name="baseUrl"
                rules={[{ required: true, message: '请填写接入点' }]}
                extra="DeepSeek：https://api.deepseek.com；通义兼容模式 / 本地 vLLM 同理，改这里即换供应商。"
              >
                <Input
                  placeholder="https://api.deepseek.com"
                  onBlur={() => {
                    // 接入点填完失焦：密钥已就绪（表单输入或已保存）则自动拉取模型
                    const apiKey = form.getFieldValue('apiKey') as string | undefined;
                    if ((apiKey && apiKey.trim()) || db?.apiKeyConfigured) {
                      void fetchModels(true);
                    }
                  }}
                />
              </Form.Item>
              <Form.Item
                label="API 密钥"
                name="apiKey"
                extra={
                  db?.apiKeyConfigured
                    ? `已配置（尾 4 位 ${db.apiKeyHint?.replace(/\*/g, '') ?? ''}）。留空 = 保持现有密钥不变；填写新值 = 覆盖。`
                    : '必填才能启用。密钥加密存储，明文永不回显。'
                }
              >
                <Input.Password
                  placeholder={db?.apiKeyConfigured ? '留空保持现有密钥不变' : 'sk-...'}
                  autoComplete="new-password"
                  onBlur={() => {
                    // 密钥填完失焦：接入点已就绪则自动拉取模型
                    const baseUrl = form.getFieldValue('baseUrl') as string | undefined;
                    const apiKey = form.getFieldValue('apiKey') as string | undefined;
                    if (baseUrl && baseUrl.trim() && apiKey && apiKey.trim()) {
                      void fetchModels(true);
                    }
                  }}
                />
              </Form.Item>
              <Form.Item
                label="模型"
                required
                extra="填写 Base URL 与密钥后自动拉取模型列表供选择；也可直接输入任意模型名（部分网关不提供 /models）。"
              >
                <Space.Compact style={{ width: '100%' }}>
                  <Form.Item
                    name="model"
                    rules={[{ required: true, message: '请选择或填写模型名' }]}
                    noStyle
                  >
                    <AutoComplete
                      style={{ width: '100%' }}
                      placeholder="deepseek-chat"
                      options={models.map((m) => ({ value: m }))}
                      filterOption={(input, option) =>
                        (option?.value as string).toLowerCase().includes(input.toLowerCase())
                      }
                    />
                  </Form.Item>
                  <Button
                    icon={<ApiOutlined />}
                    loading={loadingModels}
                    onClick={() => void fetchModels(false)}
                  >
                    拉取模型
                  </Button>
                </Space.Compact>
              </Form.Item>
              <Space size="large" wrap>
                <Form.Item label="超时（毫秒，留空用默认 30000）" name="timeoutMillis">
                  <InputNumber min={1000} max={300000} step={1000} style={{ width: 200 }} />
                </Form.Item>
                <Form.Item label="失败重试次数（留空用默认 1）" name="maxRetries">
                  <InputNumber min={0} max={5} style={{ width: 160 }} />
                </Form.Item>
              </Space>
              <Form.Item label="能力开关（未开启的能力一律 403）">
                <Space direction="vertical">
                  {effective && readyGaps.length > 0 && (
                    <Alert
                      type="warning"
                      showIcon
                      style={{ marginBottom: 4 }}
                      message="能力开关暂不生效"
                      description={`AI 调用需同时满足：总开关启用、密钥已配置、模型已选。当前缺少：${readyGaps.join('、')}——即使打开下方开关，调用仍会被拒绝。`}
                    />
                  )}
                  <Form.Item name="capabilitiesSelfTest" valuePropName="checked" noStyle>
                    <Switch checkedChildren="开" unCheckedChildren="关" /> <Typography.Text>连通性自检（self-test）</Typography.Text>
                  </Form.Item>
                  <Form.Item name="capabilitiesAccountingSuggestion" valuePropName="checked" noStyle>
                    <Switch checkedChildren="开" unCheckedChildren="关" /> <Typography.Text>智能入账建议（accounting-suggestion，A1：AI 只建议不执行）</Typography.Text>
                  </Form.Item>
                </Space>
              </Form.Item>
              <Space>
                <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>
                  保存并生效
                </Button>
                <Button icon={<ApiOutlined />} loading={testing} onClick={() => void testConnection()}>
                  测试连接（用表单当前值，可未保存）
                </Button>
              </Space>
              {testResult && (
                <Alert style={{ marginTop: 12 }} type="success" showIcon message={`连通性测试通过：${testResult}`} />
              )}
              {!effective?.apiKeyConfigured && (
                <Alert
                  style={{ marginTop: 12 }}
                  type="info"
                  showIcon
                  message="密钥尚未配置"
                  description="启用 AI 前必须先配置 API 密钥（此处填写会加密存储；或走环境变量 AI_API_KEY，页面配置优先）。"
                />
              )}
            </Form>
          </Card>
        </>
      )}
    </div>
  );
}

type FormValues = {
  enabled?: boolean;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  timeoutMillis?: number | null;
  maxRetries?: number | null;
  capabilitiesSelfTest?: boolean;
  capabilitiesAccountingSuggestion?: boolean;
};
