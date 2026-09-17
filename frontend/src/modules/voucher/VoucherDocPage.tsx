import { useCallback } from 'react';
import { Button, Card, Descriptions, Empty, Skeleton, Space, Table, Tag, type TableColumnsType } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PrinterOutlined } from '@ant-design/icons';
import { statementApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { money } from '../shared/format';
import { toChineseAmount } from './voucherTexts';
import type { VoucherEntry } from '../statements/types';

/**
 * V34 ⑦ 凭证单据详情（/statements/voucher-doc/:statementId）：
 * 金蝶式凭证排版（独立单据页 + 打印），数据源 = GET /statements/{id}（statement + AI 建议分录）。
 * 分录展示 AI 预填 + 逐行置信度（人工修正行显式标记）；已推送/已驳回不可改（操作入口只保留打印/返回）。
 * 页面自身带 print CSS（@media print 隐藏导航与操作区）。
 */

const directionTitle = (direction: string | null | undefined) =>
  direction === 'INCOME' ? '收 款 单' : direction === 'EXPENSE' ? '付 款 单' : '记 账 凭 证';

const confidenceCell = (confidence?: number | null, edited?: boolean) => {
  if (edited) return <Tag color="gold">人工</Tag>;
  if (confidence == null) return '--';
  const percent = Math.round(confidence * 100);
  const color = percent >= 85 ? 'green' : percent >= 60 ? 'orange' : 'red';
  const level = percent >= 85 ? '高' : percent >= 60 ? '中' : '低';
  return <Tag color={color}>{level} {percent}%</Tag>;
};

export function VoucherDocPage() {
  const params = useParams();
  const statementId = Number(params.statementId);
  const navigate = useNavigate();
  const loader = useCallback(() => statementApi.get(statementId), [statementId]);
  const { data, loading, error, reload } = useRemote(loader, [loader]);
  const statement = data?.statement;
  const suggestion = data?.aiSuggestion;
  const entries: VoucherEntry[] = suggestion?.entries || [];

  const debitTotal = entries
    .filter((entry) => entry.direction === 'DEBIT')
    .reduce((sum, entry) => sum + Number(entry.amount || 0), 0);
  const creditTotal = entries
    .filter((entry) => entry.direction === 'CREDIT')
    .reduce((sum, entry) => sum + Number(entry.amount || 0), 0);
  const balanced = Math.abs(debitTotal - creditTotal) < 0.01 && debitTotal > 0;

  const columns: TableColumnsType<VoucherEntry & { keyIndex: number }> = [
    { title: '#', width: 40, render: (_, row) => row.keyIndex + 1 },
    { title: '摘要', ellipsis: true, render: (_, row) => row.summary || suggestion?.suggestedSummary || statement?.summary || '--' },
    { title: '科目编码', width: 110, render: (_, row) => row.subjectCode ? <span className="mono">{row.subjectCode}</span> : '--' },
    { title: '科目名称', width: 170, render: (_, row) => row.subjectName || '--' },
    { title: '借方', align: 'right', width: 120, render: (_, row) => row.direction === 'DEBIT' ? money(row.amount) : '--' },
    { title: '贷方', align: 'right', width: 120, render: (_, row) => row.direction === 'CREDIT' ? money(row.amount) : '--' },
    { title: '置信度', width: 100, render: (_, row) => confidenceCell(row.confidence, Boolean(suggestion?.edited && row.confidence == null)) },
  ];

  if (!statementId) {
    return <Card><Empty description="缺少凭证参数" /></Card>;
  }

  return <div className="voucher-doc-page">
    <div className="page-heading no-print">
      <div>
        <span className="section-kicker">凭证中心 / 单据详情</span>
        <h2>{directionTitle(statement?.direction)} · {statement?.voucherNo || statement?.statementNo}</h2>
        <p className="muted">AI 预填科目与逐行置信度，人工可直接改（在凭证草稿工作台操作）；人工主摘要回写流水，保证「人工改了什么、金蝶就收什么」。已推送/已驳回不可改。</p>
      </div>
      <Space wrap>
        <Button onClick={() => navigate(-1)}>返回</Button>
        <Button icon={<PrinterOutlined />} onClick={() => window.print()}>打印</Button>
      </Space>
    </div>
    {error ? <ResourceFailure error={error} onRetry={reload} />
      : loading ? <Card><Skeleton active paragraph={{ rows: 8 }} /></Card>
        : !statement ? <Card><Empty description="凭证不存在或无权查看" /></Card>
          : <>
            <Card className="voucher-doc">
              <div className="voucher-doc-head">
                <div>
                  <h3 className="voucher-doc-title">{directionTitle(statement.direction)}</h3>
                  <div className="table-sub">单据编号 {statement.voucherNo || '待生成'} · 来源流水 {statement.statementNo}</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <StatusTag status={statement.pushStatus || statement.reviewStatus} />
                  <div className="table-sub">账期 {(statement.transactionTime || '').slice(0, 7)}</div>
                </div>
              </div>
              <Descriptions className="voucher-doc-meta" size="small" column={{ xs: 1, sm: 2, xl: 4 }}>
                <Descriptions.Item label="业务日期">{(statement.transactionTime || '').slice(0, 10)}</Descriptions.Item>
                <Descriptions.Item label="对手方">{statement.counterpartyName || '--'}</Descriptions.Item>
                <Descriptions.Item label="结算方式">{suggestion?.settlementMethod || '银行转账'}</Descriptions.Item>
                <Descriptions.Item label="币种">{statement.currency || 'CNY'}</Descriptions.Item>
              </Descriptions>
              {entries.length ? <Table
                className="voucher-doc-entries"
                rowKey={(row) => String(row.keyIndex)}
                size="small"
                pagination={false}
                bordered
                columns={columns}
                dataSource={entries.map((entry, index) => ({ ...entry, keyIndex: index }))}
                footer={() => <div className="voucher-doc-total">
                  <span>合计</span>
                  <span className="mono">借 {money(debitTotal)}</span>
                  <span className="mono">贷 {money(creditTotal)}</span>
                  {balanced ? <Tag color="green">借贷平衡</Tag> : <Tag color="red">借贷不平衡</Tag>}
                </div>}
              /> : <Empty description="尚未生成凭证分录（先在凭证草稿工作台生成 AI 建议）" />}
              <div className="voucher-doc-upper">金额大写：<b>{toChineseAmount(statement.amount)}</b></div>
              <div className="voucher-doc-sign">
                <span>摘要：<b>{suggestion?.suggestedSummary || statement.summary || '--'}</b></span>
                <span>审核：<b>金蝶侧人工</b></span>
                <span>推送状态：<b>{statement.pushStatus === 'PUSHED' || statement.pushStatus === 'GL_PUSHED' ? `已推送${statement.pushedAt ? ' · ' + statement.pushedAt.slice(0, 19).replace('T', ' ') : ''}` : statement.pushStatus === 'FAILED' || statement.pushStatus === 'GL_FAILED' ? '推送失败' : '待推送'}</b></span>
              </div>
              {statement.pushMessage && <div className="table-sub">推送说明：{statement.pushMessage}</div>}
            </Card>
            {suggestion?.rationale && <Card size="small" className="no-print" title="AI 建议参考">
              <div className="table-sub">
                {suggestion.model && <Tag color="geekblue">{suggestion.model}</Tag>}
                {suggestion.businessCategory && <Tag>大类：{suggestion.businessCategory}</Tag>}
              </div>
              <p style={{ marginBottom: 0 }}>{suggestion.rationale}</p>
            </Card>}
          </>}
  </div>;
}
