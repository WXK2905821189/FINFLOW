import { useCallback, useMemo, useState } from 'react';
import { Button, Card, Empty, Modal, Space, Table, Tag, Tooltip, type TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { kingdeeRuleApi } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import { dimensionText, lineSummary, shareText } from './voucherTexts';
import type { VoucherRuleLine, VoucherRuleRow } from './types';

/**
 * V34 ② 大类规则（规则引擎数据源只读视图）：
 *  - 数据源 = GET /kingdee/voucher-rules（kingdee_voucher_rule 表，财务 22 条规则 seed 导入）；
 *  - 「人工定义大类 + 默认分录模板，AI 只做归类与填充」——本页是规则的透明化展示面，
 *    规则维护走版本迁移（无编辑 UI，与后端契约一致）；
 *  - 行内「模板」弹层展示借/贷分录明细（科目 + 维度来源 + 分摊策略），第二张凭证（extraVoucher）一并展示。
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

const channelText = (channels: string[]) => {
  if (!channels || !channels.length) return '全部渠道';
  return channels.join(' / ');
};

const directionTag = (direction: string) => direction === 'INCOME'
  ? <Tag color="green">收</Tag>
  : direction === 'EXPENSE' ? <Tag color="orange">付</Tag> : <Tag>{direction}</Tag>;

function LineBlock({ title, lines }: { title: string; lines: VoucherRuleLine[] }) {
  if (!lines || !lines.length) return null;
  return <div style={{ marginBottom: 12 }}>
    <div style={{ fontWeight: 600, marginBottom: 6 }}>{title}</div>
    <Table
      rowKey={(row) => `${row.account}-${row.dimension ?? ''}-${row.value ?? ''}`}
      size="small"
      pagination={false}
      dataSource={lines}
      columns={[
        { title: '科目编码', dataIndex: 'account', width: 100, render: (value: string) => <span className="mono">{value}</span> },
        { title: '科目名称', dataIndex: 'name', render: (value: string | null) => value || '--' },
        { title: '维度来源', dataIndex: 'dimension', width: 110, render: (value: string | null) => dimensionText(value) },
        { title: '固定值 / 分支', dataIndex: 'value', ellipsis: true, render: (_: unknown, row: VoucherRuleLine) => row.value || (row.branches && row.branches.length ? row.branches.map((branch) => `${branch.keyword}→${branch.supplier}`).join('；') : '--') },
        { title: '金额分摊', dataIndex: 'share', width: 100, render: (value: string) => shareText(value) },
      ] as TableColumnsType<VoucherRuleLine>}
    />
  </div>;
}

export function CategoryRulesPage() {
  const loader = useCallback(() => kingdeeRuleApi.list(), []);
  const { data, loading, error, reload } = useRemote<VoucherRuleRow[]>(loader, [loader]);
  const [detail, setDetail] = useState<VoucherRuleRow>();

  const rules = data || [];
  const withTemplate = rules.filter((rule) => (rule.debitLines?.length || 0) + (rule.creditLines?.length || 0) > 0).length;
  const enabledCount = rules.filter((rule) => rule.enabled).length;

  const columns: TableColumnsType<VoucherRuleRow> = [
    { title: '规则号', dataIndex: 'ruleNo', width: 70, render: (value: number) => <span className="mono">{value}</span> },
    { title: '业务大类', dataIndex: 'businessType', ellipsis: true },
    { title: '类别', dataIndex: 'category', width: 100, ellipsis: true },
    { title: '方向', dataIndex: 'direction', width: 64, render: (value: string) => directionTag(value) },
    { title: '适用主体', width: 120, render: (_, row) => orgText(row.scopeOrgs) },
    { title: '渠道', dataIndex: 'scopeBankChannels', width: 90, render: (value: string[]) => channelText(value) },
    { title: '优先级', dataIndex: 'priority', width: 70 },
    {
      title: '默认借方', ellipsis: true,
      render: (_, row) => lineSummary(row.debitLines?.[0]) + (row.debitLines?.length > 1 ? ` 等 ${row.debitLines.length} 行` : ''),
    },
    {
      title: '默认贷方', ellipsis: true,
      render: (_, row) => lineSummary(row.creditLines?.[0]) + (row.creditLines?.length > 1 ? ` 等 ${row.creditLines.length} 行` : ''),
    },
    {
      title: '状态', width: 92, render: (_, row) => row.enabled
        ? <Tag color="green">启用</Tag>
        : <Tooltip title="规则已停用，匹配器自动跳过"><Tag>已停用</Tag></Tooltip>,
    },
    { title: '操作', width: 90, render: (_, row) => <Button type="link" size="small" onClick={() => setDetail(row)}>模板</Button> },
  ];

  const categoryStats = useMemo(() => {
    const byType = new Map<string, number>();
    rules.forEach((rule) => byType.set(rule.businessType, (byType.get(rule.businessType) || 0) + 1));
    return byType;
  }, [rules]);

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">凭证与入账 / 大类规则</span>
        <h2>大类规则</h2>
        <p className="muted">
          人工定义「业务大类 + 默认分录模板」，AI 制证按大类套用模板生成分录；模板缺失或冲突时退回人工，不猜测科目。
          规则由财务维护、随版本迁移发布（本页只读）；匹配顺序 = 优先级升序。
        </p>
      </div>
      <Space wrap>
        <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
      </Space>
    </div>
    <Card title={<>业务规则清单 <span className="table-sub">共 {rules.length} 条 · {withTemplate} 条已配模板 · 启用 {enabledCount} 条 · 覆盖 {categoryStats.size} 个业务大类</span></>}>
      {error ? <ResourceFailure error={error} onRetry={reload} /> : <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rules}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无凭证规则" /> }}
        scroll={{ x: 1280 }}
      />}
    </Card>
    <Modal
      title={detail ? `模板详情 · 规则 ${detail.ruleNo} ${detail.businessType}` : '模板详情'}
      open={Boolean(detail)}
      onCancel={() => setDetail(undefined)}
      footer={<Button type="primary" onClick={() => setDetail(undefined)}>知道了</Button>}
      width={860}
    >
      {detail && <>
        {detail.remark && <p className="table-sub">{detail.remark}</p>}
        {detail.match && detail.match.conditions?.length > 0 && <p className="table-sub">
          匹配条件（{detail.match.logic === 'ANY' ? '任一满足' : '全部满足'}）：
          {detail.match.conditions.map((condition, index) => (
            <Tag key={index} style={{ marginBottom: 4 }}>
              {condition.field === 'SUMMARY' ? '摘要' : condition.field === 'COUNTERPARTY_NAME' ? '对手方' : condition.field}
              {' '}{condition.op}{' '}{condition.values.join(' / ')}
            </Tag>
          ))}
        </p>}
        {detail.amountMin != null || detail.amountMax != null ? <p className="table-sub">
          金额门槛：{detail.amountMin != null ? `≥ ${detail.amountMin}` : ''}{detail.amountMin != null && detail.amountMax != null ? ' 且 ' : ''}{detail.amountMax != null ? `≤ ${detail.amountMax}` : ''}
        </p> : <p className="table-sub">金额门槛：未设置（财务补充后随迁移发布）</p>}
        <LineBlock title="借方分录" lines={detail.debitLines} />
        <LineBlock title="贷方分录" lines={detail.creditLines} />
        {detail.extraVoucher && <>
          <p style={{ fontWeight: 600 }}>第二张凭证（费用确认单）</p>
          <LineBlock title="借方分录" lines={detail.extraVoucher.debitLines} />
          <LineBlock title="贷方分录" lines={detail.extraVoucher.creditLines} />
        </>}
      </>}
    </Modal>
  </>;
}
