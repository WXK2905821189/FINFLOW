import { useCallback, useState } from 'react';
import { Button, Card, Descriptions, Empty, InputNumber, Skeleton, Space, Table, Tag, Timeline, Tooltip, message, type TableColumnsType } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { CopyOutlined, LockOutlined, PrinterOutlined, ReloadOutlined } from '@ant-design/icons';
import { closingApi, statementApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, money } from '../shared/format';
import { toChineseAmount } from './voucherTexts';
import { diagnosePushFailure } from './pushDiagnosis';
import type { VoucherEntry } from '../statements/types';

/**
 * V34 ⑦ 凭证单据详情（/statements/voucher-doc/:statementId）：
 * 金蝶式凭证排版（独立单据页 + 打印），数据源 = GET /statements/{id}（statement + AI 建议分录 + 追溯）。
 * 分录展示 AI 预填 + 逐行置信度（人工修正行显式标记）；已推送/已驳回不可改（操作入口只保留打印/返回）。
 * 页面自身带 print CSS（@media print 隐藏导航与操作区）。
 * W7（2026-09-18）：所属账期已结账（CLOSED）时显示锁 chip（demo 的 data-lock-blocked 语义）——
 * 服务端对 CLOSED 账期的导入/制证/推送一律 409，此处为可视化提示。
 *
 * 2026-09-21 改造（demo：docs/ui-voucher-doc-demo-20260921.html）：
 *  - 推送失败时渲染「诊断卡」：金蝶原文 + 根因 + 处置步骤 + 判定依据（诉求「报错一定要说明」）；
 *  - 右侧信息栏分区：来源流水 / AI 建议参考 / 操作轨迹（auditTrail 时间轴）；
 *  - 分录「辅助核算」列按数据条件渲染——后端分录尚未产出维度字段（随规则引擎落地），
 *    现在强显示只会是空列，故做条件列，数据一到即自动出现。
 */

const directionTitle = (direction: string | null | undefined) =>
  direction === 'INCOME' ? '收 款 单' : direction === 'EXPENSE' ? '付 款 单' : '记 账 凭 证';

const pushMeta = (status?: string | null) => {
  const value = (status || '').toUpperCase();
  if (value === 'PUSHED' || value === 'GL_PUSHED') return { label: '已推送', color: 'green' as const };
  if (value === 'FAILED' || value === 'GL_FAILED') return { label: '推送失败', color: 'red' as const };
  if (value === 'PUSH_PROCESSING') return { label: '推送中', color: 'blue' as const };
  return { label: '待推送', color: 'orange' as const };
};

const reviewLabel = (status?: string | null) => {
  const value = (status || '').toUpperCase();
  if (value === 'APPROVED') return '已通过';
  if (value === 'REJECTED') return '已驳回';
  if (value === 'WITHDRAWN') return '已撤回';
  return '待复核';
};

const confidenceCell = (confidence?: number | null, edited?: boolean) => {
  if (edited) return <Tag color="gold">人工</Tag>;
  if (confidence == null) return '--';
  const percent = Math.round(confidence * 100);
  const color = percent >= 85 ? 'green' : percent >= 60 ? 'orange' : 'red';
  const level = percent >= 85 ? '高' : percent >= 60 ? '中' : '低';
  return <Tag color={color}>{level} {percent}%</Tag>;
};

const auditColor = (result?: string) =>
  result === 'SUCCESS' ? 'green' : result === 'FAILED' ? 'red' : 'blue';

export function VoucherDocPage() {
  const params = useParams();
  const statementId = Number(params.statementId);
  const navigate = useNavigate();
  const [retrying, setRetrying] = useState(false);
  const loader = useCallback(() => statementApi.get(statementId), [statementId]);
  const { data, loading, error, reload } = useRemote(loader, [loader]);
  // W7 账期锁提示：拉本公司 CLOSED 账期，命中凭证账期即显示锁 chip。
  const closedLoader = useCallback(() => closingApi.periods({ page: 1, size: 100, status: 'CLOSED' }), []);
  const closed = useRemote(closedLoader, [closedLoader]);
  const statement = data?.statement;
  const suggestion = data?.aiSuggestion;
  const auditTrail = data?.auditTrail || [];
  const entries: VoucherEntry[] = suggestion?.entries || [];

  const debitTotal = entries
    .filter((entry) => entry.direction === 'DEBIT')
    .reduce((sum, entry) => sum + Number(entry.amount || 0), 0);
  const creditTotal = entries
    .filter((entry) => entry.direction === 'CREDIT')
    .reduce((sum, entry) => sum + Number(entry.amount || 0), 0);
  const balanced = Math.abs(debitTotal - creditTotal) < 0.01 && debitTotal > 0;

  // 置信度可手动调（2026-09-21）：本页是 live 的凭证单据详情页（/statements/voucher-doc/:id）。
  // 语义：人工调过 = 已人工确认 —— 推送时不再因「置信度低于阈值」把该行科目换成待确认科目；
  // 反过来调低会触发兜底替换。保存走既有的 PUT /statements/{id}/voucher-draft（只改置信度）。
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canEditDraft = hasPermission('voucher:push');
  const [confidenceEdits, setConfidenceEdits] = useState<Record<number, number>>({});
  const [savingConfidence, setSavingConfidence] = useState(false);
  const editedCount = Object.keys(confidenceEdits).length;

  const saveConfidence = async () => {
    if (!statementId || !editedCount) return;
    setSavingConfidence(true);
    try {
      await statementApi.saveVoucherDraft(statementId, {
        summary: suggestion?.suggestedSummary || undefined,
        entries: entries.map((entry, index) => ({
          summary: entry.summary || undefined,
          subjectCode: entry.subjectCode || undefined,
          subjectName: entry.subjectName,
          direction: entry.direction,
          amount: Number(entry.amount),
          confidence: confidenceEdits[index] == null ? (entry.confidence ?? undefined) : confidenceEdits[index] / 100,
        })),
      });
      message.success('置信度已保存：调高的行视为人工确认，推送时不再替换科目');
      setConfidenceEdits({});
      reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '置信度保存失败');
    } finally {
      setSavingConfidence(false);
    }
  };

  const voucherPeriod = (statement?.transactionTime || '').slice(0, 7);
  const periodLocked = Boolean(voucherPeriod)
    && (closed.data?.records || []).some((row) => row.period === voucherPeriod);
  const lockChip = periodLocked ? (
    <Tooltip title="该账期已结账（CLOSED）：导入、AI 制证与推送被拦截，超管可在结账管理中解锁。">
      <Tag icon={<LockOutlined />} color="red" style={{ marginLeft: 6 }}>账期已结账</Tag>
    </Tooltip>
  ) : null;

  const meta = pushMeta(statement?.pushStatus);
  const diagnosis = meta.label === '推送失败' ? diagnosePushFailure(statement?.pushMessage) : null;
  const hasDimension = entries.some((entry) => Boolean(entry.dimension));

  const retryPush = async () => {
    if (!statementId) return;
    setRetrying(true);
    try {
      await statementApi.pushVoucher(statementId);
      message.success('已重新提交推送，结果将回写本页状态');
      reload();
    } catch (err) {
      message.error(err instanceof Error ? err.message : '重试推送未能完成');
    } finally {
      setRetrying(false);
    }
  };

  const copyDiagnosis = async () => {
    if (!diagnosis) return;
    const text = [diagnosis.headline, diagnosis.cause, `金蝶原文：${statement?.pushMessage || '--'}`,
      ...diagnosis.steps.map((step, index) => `${index + 1}. ${step}`), diagnosis.evidence || ''].join('\n');
    try {
      await navigator.clipboard.writeText(text);
      message.success('诊断已复制');
    } catch {
      message.warning('浏览器未授权剪贴板，请手动选择文本复制');
    }
  };

  const columns: TableColumnsType<VoucherEntry & { keyIndex: number }> = [
    { title: '#', width: 40, render: (_, row) => row.keyIndex + 1 },
    { title: '摘要', ellipsis: true, render: (_, row) => row.summary || suggestion?.suggestedSummary || statement?.summary || '--' },
    { title: '科目编码', width: 100, render: (_, row) => row.subjectCode ? <span className="mono">{row.subjectCode}</span> : '--' },
    { title: '科目名称', width: 170, render: (_, row) => row.subjectName || '--' },
    ...(hasDimension
      ? [{
        title: '辅助核算', width: 170,
        render: (_: unknown, row: VoucherEntry) => row.dimension || '--',
      } as TableColumnsType<VoucherEntry & { keyIndex: number }>[number]]
      : []),
    { title: '借方', align: 'right', width: 110, render: (_, row) => row.direction === 'DEBIT' ? money(row.amount) : '--' },
    { title: '贷方', align: 'right', width: 110, render: (_, row) => row.direction === 'CREDIT' ? money(row.amount) : '--' },
    {
      title: '置信度 %（可改）', width: 150,
      render: (_, row) => {
        if (!canEditDraft) {
          return confidenceCell(row.confidence, Boolean(suggestion?.edited && row.confidence == null));
        }
        const current = confidenceEdits[row.keyIndex];
        const percent = current == null ? (row.confidence == null ? undefined : Math.round(row.confidence * 100)) : current;
        return <Space size={4}>
          <InputNumber size="small" min={0} max={100} step={5} controls={false} style={{ width: 66 }}
            value={percent} placeholder="--"
            onChange={(value) => setConfidenceEdits((prev) => {
              const next = { ...prev };
              if (value == null) {
                delete next[row.keyIndex];
              } else {
                next[row.keyIndex] = Number(value);
              }
              return next;
            })} />
          {current != null && <Tag color="gold">未保存</Tag>}
        </Space>;
      },
    },
  ];

  if (!statementId) {
    return <Card><Empty description="缺少凭证参数" /></Card>;
  }

  return <div className="voucher-doc-page">
    <div className="page-heading no-print">
      <div>
        <span className="section-kicker">凭证中心 / 单据详情</span>
        <h2>{directionTitle(statement?.direction)} · {statement?.voucherNo || statement?.statementNo}</h2>
        <p className="muted">AI 预填科目与逐行置信度，置信度可直接在本页调整并保存；主摘要回写流水，保证「人工改了什么、金蝶就收什么」。</p>
        <p className="muted" style={{ fontSize: 12 }}>推送策略：某行**科目在账套不存在**或**置信度低于 60%** 时，推送会自动把该行科目置为「待确认科目 2241.99 其他应付款-其他」并在结果里提示 —— 目的是先让凭证推到金蝶，再由人工在金蝶侧改正。把置信度调到 60% 以上即视为人工确认，不再替换。</p>
      </div>
      <Space wrap>
        <Button onClick={() => navigate(-1)}>返回</Button>
        {canEditDraft && editedCount > 0 && <Button type="primary" loading={savingConfidence}
          onClick={() => void saveConfidence()}>保存置信度（{editedCount}）</Button>}
        {meta.label === '推送失败' && <Button icon={<ReloadOutlined />} type="primary" loading={retrying} onClick={retryPush}>重试推送</Button>}
        <Button icon={<PrinterOutlined />} onClick={() => window.print()}>打印</Button>
      </Space>
    </div>
    {error ? <ResourceFailure error={error} onRetry={reload} />
      : loading ? <Card><Skeleton active paragraph={{ rows: 8 }} /></Card>
        : !statement ? <Card><Empty description="凭证不存在或无权查看" /></Card>
          : <div className="voucher-doc-layout">
            <main className="voucher-doc-main">
              <Card className="voucher-doc">
                <div className="voucher-doc-head">
                  <div>
                    <h3 className="voucher-doc-title">{directionTitle(statement.direction)}</h3>
                    <div className="table-sub">单据编号 {statement.voucherNo || '待生成'} · 来源流水 {statement.statementNo}</div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <Tag color={meta.color}>{meta.label}</Tag>
                    <div className="table-sub">账期 {voucherPeriod || '--'}{lockChip}</div>
                  </div>
                </div>

                <Descriptions className="voucher-doc-meta" size="small" column={{ xs: 1, sm: 2, xl: 4 }}>
                  <Descriptions.Item label="业务日期">{(statement.transactionTime || '').slice(0, 10) || '--'}</Descriptions.Item>
                  <Descriptions.Item label="对手方">{statement.counterpartyName || '--'}</Descriptions.Item>
                  <Descriptions.Item label="结算方式">{suggestion?.settlementMethod || '银行转账'}</Descriptions.Item>
                  <Descriptions.Item label="币别">{statement.currency || 'CNY'}</Descriptions.Item>
                  <Descriptions.Item label="复核状态">{reviewLabel(statement.reviewStatus)}</Descriptions.Item>
                  <Descriptions.Item label="推送时间">{statement.pushedAt ? dateTime(statement.pushedAt) : '--'}</Descriptions.Item>
                  <Descriptions.Item label="金额">{money(statement.amount)}</Descriptions.Item>
                  <Descriptions.Item label="收支方向">{statement.direction === 'INCOME' ? '收入' : statement.direction === 'EXPENSE' ? '支出' : '--'}</Descriptions.Item>
                </Descriptions>

                {diagnosis && <div className="voucher-doc-diag">
                  <div className="voucher-doc-diag-head">{diagnosis.headline}</div>
                  <div className="voucher-doc-diag-raw">金蝶原文：{statement.pushMessage}</div>
                  <div className="voucher-doc-diag-cause"><b>原因：</b>{diagnosis.cause}</div>
                  <ol className="voucher-doc-diag-steps">{diagnosis.steps.map((step) => <li key={step}>{step}</li>)}</ol>
                  <div className="voucher-doc-diag-actions no-print">
                    {diagnosis.retryable && <Button size="small" type="primary" icon={<ReloadOutlined />} loading={retrying} onClick={retryPush}>重试推送</Button>}
                    <Button size="small" icon={<CopyOutlined />} onClick={copyDiagnosis}>复制诊断</Button>
                  </div>
                  {diagnosis.evidence && <div className="voucher-doc-diag-evi">{diagnosis.evidence}</div>}
                </div>}

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
                  <span>主摘要：<b>{suggestion?.suggestedSummary || statement.summary || '--'}</b></span>
                  <span>审核：<b>金蝶侧人工</b></span>
                  <span>推送状态：<b>{meta.label === '已推送' && statement.pushedAt ? `已推送 · ${dateTime(statement.pushedAt)}` : meta.label}</b></span>
                </div>
              </Card>
            </main>

            <aside className="voucher-doc-side">
              <Card size="small" title="来源流水" className="no-print">
                <Descriptions size="small" column={1} colon={false}>
                  <Descriptions.Item label="流水号"><span className="mono">{statement.statementNo}</span></Descriptions.Item>
                  <Descriptions.Item label="交易时间">{statement.transactionTime ? dateTime(statement.transactionTime) : '--'}</Descriptions.Item>
                  <Descriptions.Item label="对方户名">{statement.counterpartyName || '--'}</Descriptions.Item>
                  <Descriptions.Item label="对方账号"><span className="mono">{statement.maskedCounterpartyAccount || '--'}</span></Descriptions.Item>
                  <Descriptions.Item label="摘要">{statement.summary || '--'}</Descriptions.Item>
                  <Descriptions.Item label="金额">{money(statement.amount)}</Descriptions.Item>
                  <Descriptions.Item label="校验"><StatusTag status={statement.validationStatus} /></Descriptions.Item>
                </Descriptions>
                {statement.validationMessage && <div className="table-sub">校验说明：{statement.validationMessage}</div>}
              </Card>

              {suggestion?.rationale && <Card size="small" className="no-print" title="AI 建议参考">
                <div className="table-sub" style={{ marginBottom: 6 }}>
                  {suggestion.model && <Tag color="geekblue">{suggestion.model}</Tag>}
                  {suggestion.businessCategory && <Tag>大类：{suggestion.businessCategory}</Tag>}
                  {suggestion.edited && <Tag color="gold">人工已修正</Tag>}
                </div>
                <p style={{ marginBottom: 0 }}>{suggestion.rationale}</p>
              </Card>}

              <Card size="small" className="no-print" title="操作轨迹">
                {auditTrail.length ? <Timeline items={auditTrail.map((event) => ({
                  color: auditColor(event.result),
                  children: <div>
                    <div><b>{event.action}</b></div>
                    <div className="table-sub">{event.previousStatus || '--'} → {event.currentStatus || '--'} · 操作人 {event.operatorId || '--'}</div>
                    {event.detail && <div className="timeline-detail">{event.detail}</div>}
                    <div className="table-sub">{dateTime(event.createdAt)}</div>
                  </div>,
                }))} /> : <Empty description="该凭证暂无追溯事件" />}
              </Card>
            </aside>
          </div>}
  </div>;
}
