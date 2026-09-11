import { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
  type TableColumnsType,
} from 'antd';
import { ApiOutlined, ReloadOutlined } from '@ant-design/icons';
import { aiApi } from '../../services/api';
import type { AiCallLogRow, AiSelfTest, AiStatus } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime } from '../shared/format';

/**
 * AI 状态（V27 AI 能力地基，系统管理）：网关/能力开关一目了然，密钥永不回显
 * （只有"是否已配置"布尔位）；持 ai:config 者可跑连通性自检并查看调用审计
 * （哈希+摘要口径）。AI 遵循 fail-closed：开关未开时后端一律 403。
 */
export function AiStatusPage() {
  const { hasPermission } = useAuthStore();
  const canConfig = hasPermission('ai:config');
  const [selfTesting, setSelfTesting] = useState(false);
  const [selfTestResult, setSelfTestResult] = useState<AiSelfTest | null>(null);

  const statusLoader = useCallback(() => aiApi.status(), []);
  const { data: status, loading: statusLoading, error: statusError, reload: reloadStatus } =
    useRemote<AiStatus>(statusLoader, [statusLoader]);

  const logsLoader = useCallback(() => (canConfig ? aiApi.callLogs(50) : Promise.resolve([])), [canConfig]);
  const { data: logs, loading: logsLoading, error: logsError, reload: reloadLogs } =
    useRemote<AiCallLogRow[]>(logsLoader, [logsLoader]);

  const capabilityEntries = useMemo(
    () => Object.entries(status?.capabilities || {}).sort(([a], [b]) => a.localeCompare(b)),
    [status],
  );

  const runSelfTest = async () => {
    setSelfTesting(true);
    setSelfTestResult(null);
    try {
      const result = await aiApi.selfTest();
      setSelfTestResult(result);
      message.success(`自检成功：${result.durationMillis}ms`);
      reloadLogs();
    } catch {
      message.error('自检失败：详见调用日志中的错误信息');
      reloadLogs();
    } finally {
      setSelfTesting(false);
    }
  };

  const columns: TableColumnsType<AiCallLogRow> = [
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 160, render: (v) => (v ? dateTime(v) : '-') },
    { title: '能力', dataIndex: 'capability', key: 'capability', width: 130 },
    { title: '用户', dataIndex: 'userId', key: 'userId', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => (v === 'SUCCEEDED' ? <Tag color="green">成功</Tag> : <Tag color="red">失败</Tag>),
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 90,
      render: (v: number | null) => (v == null ? '-' : `${v} ms`),
    },
    { title: '输入 tok', dataIndex: 'promptTokens', key: 'promptTokens', width: 90, render: (v) => (v == null ? '-' : v) },
    { title: '输出 tok', dataIndex: 'completionTokens', key: 'completionTokens', width: 90, render: (v) => (v == null ? '-' : v) },
    { title: '模型', dataIndex: 'model', key: 'model', width: 140 },
    {
      title: '提示摘要',
      dataIndex: 'promptSummary',
      key: 'promptSummary',
      ellipsis: true,
      render: (v: string | null, row) => (v ? <Tooltip title={`SHA-256: ${row.promptHash}`}>{v}</Tooltip> : '-'),
    },
    {
      title: '错误',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (v: string | null) => (v ? <Typography.Text type="danger">{v}</Typography.Text> : '-'),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card
        title="AI 网关"
        extra={
          <Space>
            {canConfig && (
              <Button type="primary" icon={<ApiOutlined />} loading={selfTesting} onClick={runSelfTest} disabled={!status?.enabled}>
                连通性自检
              </Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={() => { reloadStatus(); reloadLogs(); }}>
              刷新
            </Button>
          </Space>
        }
        loading={statusLoading}
      >
        {statusError ? <ResourceFailure error={statusError} onRetry={reloadStatus} /> : status && (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="总开关">
                {status.enabled ? <Tag color="green">已启用</Tag> : <Tag color="default">未启用（fail-closed）</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="密钥">{status.apiKeyConfigured ? <Tag color="green">已配置</Tag> : <Tag color="warning">未配置</Tag>}</Descriptions.Item>
              <Descriptions.Item label="提供方">{status.provider}</Descriptions.Item>
              <Descriptions.Item label="模型">{status.model}</Descriptions.Item>
              <Descriptions.Item label="接入点">{status.baseUrl}</Descriptions.Item>
              <Descriptions.Item label="日限频">{status.dailyLimitPerUser} 次/用户/能力</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 12 }}>
              <Typography.Text strong>能力开关（未列出的能力一律关闭）</Typography.Text>
              <div style={{ marginTop: 8 }}>
                {capabilityEntries.length === 0 ? (
                  <Typography.Text type="secondary">当前没有任何已登记的能力开关</Typography.Text>
                ) : (
                  capabilityEntries.map(([name, on]) =>
                    on ? <Tag key={name} color="green">{name}</Tag> : <Tag key={name} color="default">{name}（关）</Tag>,
                  )
                )}
              </div>
            </div>
            {!status.enabled && (
              <Alert
                style={{ marginTop: 12 }}
                type="info"
                showIcon
                message="AI 能力未启用"
                description="启用步骤：环境变量 AI_ENABLED=true + AI_API_KEY=密钥，并在 ai.capabilities 中显式开启所需能力。所有 AI 端点在未启用时一律 403。"
              />
            )}
            {status.enabled && !status.apiKeyConfigured && (
              <Alert style={{ marginTop: 12 }} type="error" showIcon message="缺少密钥：AI_ENABLED=true 时 AI_API_KEY 必须注入，否则后端拒绝启动" />
            )}
            {selfTestResult && (
              <Alert
                style={{ marginTop: 12 }}
                type="success"
                showIcon
                message={`自检回信：「${selfTestResult.reply}」（${selfTestResult.model}，${selfTestResult.durationMillis}ms）`}
              />
            )}
          </>
        )}
      </Card>

      {canConfig && (
        <Card title="调用审计（最近 50 条，哈希+摘要口径）" loading={logsLoading}>
          {logsError ? <ResourceFailure error={logsError} onRetry={reloadLogs} /> : (
            <Table<AiCallLogRow>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={logs || []}
              pagination={{ pageSize: 10, showSizeChanger: false }}
              scroll={{ x: 1100 }}
            />
          )}
        </Card>
      )}
    </div>
  );
}
