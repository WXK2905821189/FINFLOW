import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Space,
  Steps,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  CopyOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { feishuApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote } from '../shared/components';
import type { FeishuAppConfigView } from '../../types';

const AI_PROMPT = [
  '请一步步引导我在飞书开放平台创建企业自建应用，并把凭证填回 FINFLOW：',
  '1. 打开 https://open.feishu.cn/app 用企业管理员账号登录；',
  '2. 创建「企业自建应用」，名称建议 FINFLOW 财务助手，描述随意；',
  '3. 在「添加应用能力」里开启「机器人」能力；',
  '4. 在「凭证与基础信息」页复制 App ID（cli_ 开头）与 App Secret；',
  '5. 回到 FINFLOW「飞书协同」页的连接向导，把两个值填入并点「保存并验证」；',
  '6. 若验证报错，把错误信息发给我，我帮你逐项排查（应用可用性、IP 白名单等）。',
].join('\n');

/**
 * 飞书连接向导（V29）：四步引导创建自建应用 → 填入凭证 → 真实验证
 * （服务端真实调飞书 tenant_access_token/internal）。
 * 「AI 代办」提供可复制提示词，用户可交给 AI 助手代操作开放平台。
 * 凭证安全红线：Secret 只写不读，明文永不回显（仅尾 4 位 hint）。
 */
export function FeishuConnectWizard({ onChanged }: { onChanged?: () => void }) {
  const { hasPermission } = useAuthStore();
  const canManage = hasPermission('feishu:manage');
  const [form] = Form.useForm<{ appId?: string; appSecret?: string }>();
  // 复用仓库样板 useRemote 拉配置（其内部已处理 effect 加载的 lint 豁免）
  const { data: loaded, reload } = useRemote<FeishuAppConfigView>(() => feishuApi.getAppConfig(), []);
  // 保存/验证成功后服务端即时回包，先落 override 展示（下次 reload 后与 loaded 汇合）
  const [override, setOverride] = useState<FeishuAppConfigView | null>(null);
  const [saving, setSaving] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const config = override ?? loaded ?? null;

  // 首次加载后把 DB 现状铺进表单（Secret 永不回显，不铺）
  useEffect(() => {
    if (!loaded?.appId) return;
    form.setFieldValue('appId', loaded.appId);
  }, [loaded, form]);

  const buildPayload = (values: { appId?: string; appSecret?: string }) => ({
    appId: values.appId?.trim(),
    ...(values.appSecret && values.appSecret.trim() ? { appSecret: values.appSecret.trim() } : {}),
  });

  const save = async () => {
    if (saving) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      const view = await feishuApi.updateAppConfig(buildPayload(values));
      setOverride(view);
      void reload();
      message.success('凭证已保存（加密存储，明文不回显）');
      form.setFieldValue('appSecret', undefined);
      onChanged?.();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存未能完成');
    } finally {
      setSaving(false);
    }
  };

  const verify = async () => {
    if (verifying) return;
    const values = await form.validateFields();
    setVerifying(true);
    try {
      const view = await feishuApi.verifyAppConfig({
        appId: values.appId?.trim(),
        ...(values.appSecret && values.appSecret.trim() ? { appSecret: values.appSecret.trim() } : {}),
      });
      setOverride(view);
      void reload();
      message.success('飞书连接验证通过 ✓');
      form.setFieldValue('appSecret', undefined);
      onChanged?.();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '验证未能完成');
    } finally {
      setVerifying(false);
    }
  };

  const currentStep = !config?.appId && !config?.secretConfigured
    ? 2
    : config?.verified ? 4 : 3;

  return (
    <Card
      title="连接真实飞书（自建应用）"
      size="small"
      extra={
        config?.verified ? (
          <Tag icon={<CheckCircleOutlined />} color="green">
            已验证{config.verifiedTenant ? ` · ${config.verifiedTenant}` : ''}
          </Tag>
        ) : (
          <Tag color="default">未验证</Tag>
        )
      }
    >
      <Steps
        size="small"
        current={currentStep}
        items={[
          { title: '创建自建应用' },
          { title: '开启机器人' },
          { title: '获取凭证' },
          { title: '填入并验证' },
        ]}
        style={{ marginBottom: 16 }}
      />
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          1️⃣ 打开 open.feishu.cn/app 创建企业自建应用；2️⃣ 「添加应用能力」开启机器人；
          3️⃣ 「凭证与基础信息」复制 App ID / App Secret；4️⃣ 填入下方并验证。
        </Typography.Text>
        <Alert
          type="info"
          showIcon
          icon={<RobotOutlined />}
          message="不想手动操作？把下面提示词交给你的 AI 助手代办"
          description={
            <Typography.Paragraph
              copyable={{ text: AI_PROMPT, tooltips: '点击复制提示词' }}
              style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}
            >
              {AI_PROMPT}
            </Typography.Paragraph>
          }
        />
        <Form form={form} layout="vertical" style={{ maxWidth: 520 }}>
          <Form.Item
            label="App ID"
            name="appId"
            rules={[{ required: true, message: '请填写 App ID（cli_ 开头）' }]}
          >
            <Input placeholder="cli_xxxxxxxx" allowClear />
          </Form.Item>
          <Form.Item
            label="App Secret"
            name="appSecret"
            extra={
              config?.secretConfigured
                ? `已配置（尾 4 位 ${config.secretHint?.replace(/\*/g, '') ?? ''}）。留空 = 保持不变；填写新值 = 覆盖。`
                : '加密存储，明文永不回显。'
            }
          >
            <Input.Password
              placeholder={config?.secretConfigured ? '留空保持现有密钥不变' : '请粘贴 App Secret'}
              autoComplete="new-password"
            />
          </Form.Item>
          {canManage ? (
            <Space>
              <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>
                仅保存
              </Button>
              <Button
                type="primary"
                icon={<SafetyCertificateOutlined />}
                loading={verifying}
                onClick={() => void verify()}
              >
                保存并验证（真实调飞书接口）
              </Button>
              <Typography.Text type="secondary" copyable={{ text: 'https://open.feishu.cn/app' }}>
                <CopyOutlined /> 开放平台入口
              </Typography.Text>
            </Space>
          ) : (
            <Typography.Text type="secondary">需要 feishu:manage 权限才能保存与验证凭证。</Typography.Text>
          )}
        </Form>
        {config?.verifiedAt && (
          <Typography.Text type="secondary">
            最近验证：{config.verifiedAt.replace('T', ' ').slice(0, 19)}
            {config.verifiedTenant ? ` · 租户：${config.verifiedTenant}` : ''}
          </Typography.Text>
        )}
      </Space>
    </Card>
  );
}
