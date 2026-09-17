import { useCallback, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  InputNumber,
  Popconfirm,
  Skeleton,
  Space,
  Tag,
  Tooltip,
  message,
} from 'antd';
import { DeleteOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons';
import { statementApi } from '../../services/api';
import { useAuthStore } from '../../store/auth';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, money } from '../shared/format';
import type { AiVoucherSuggestion, StatementRecord, VoucherEntry } from '../../types';

/**
 * V33 凭证草稿详情（金蝶式单据页）：点击「凭证草稿与制证」行的「凭证」打开。
 *
 *  - 单据头：收款单/付款单 + 交易信息 + 状态；
 *  - 分录区：AI 预填的借贷分录（摘要/科目编码/科目名称/借方/贷方），逐行置信度徽标，
 *    人工可直接修改、增删行——保存即回写结构化建议并同步主摘要（金蝶单据备注口径）；
 *  - 合计行：借贷合计与平衡校验（不平衡不允许保存，服务端双重校验）；
 *  - AI 参考：业务类别/判断依据/风险点/模型，帮助人工判断是否需要修正；
 *  - 动作：保存修正（voucher:push）、通过/驳回（statement:review）、推送金蝶（voucher:push）、
 *    刷新 AI 建议（ai:use，仅 PENDING）。
 */

type EditableRow = {
  key: number;
  summary: string;
  subjectCode: string;
  subjectName: string;
  debit: string;
  credit: string;
  confidence?: number | null;
  human: boolean;
};

const num = (value: string): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

const toRow = (entry: VoucherEntry, key: number, human: boolean): EditableRow => ({
  key,
  summary: entry.summary || '',
  subjectCode: entry.subjectCode || '',
  subjectName: entry.subjectName || '',
  debit: entry.direction === 'DEBIT' ? String(entry.amount ?? '') : '',
  credit: entry.direction === 'CREDIT' ? String(entry.amount ?? '') : '',
  confidence: entry.confidence ?? null,
  human,
});

/** 逐行置信度徽标：AI 行分级着色（高≥85% 绿 / 中≥60% 橙 / 低红），人工行单独标注。 */
function ConfidenceBadge({ confidence, human }: { confidence?: number | null; human: boolean }) {
  if (human) {
    return <Tag color="gold">人工</Tag>;
  }
  if (confidence == null) {
    return <Tag>--</Tag>;
  }
  const percent = Math.round(confidence * 100);
  const level = confidence >= 0.85
    ? { color: 'green', label: '高' }
    : confidence >= 0.6 ? { color: 'orange', label: '中' } : { color: 'red', label: '低' };
  return <Tooltip title={`AI 置信度 ${percent}%`}><Tag color={level.color}>{level.label} {percent}%</Tag></Tooltip>;
}

function initialRows(statement: StatementRecord, suggestion: AiVoucherSuggestion): EditableRow[] {
  const entries: VoucherEntry[] = suggestion.entries?.length
    ? suggestion.entries
    : [{ summary: statement.summary || '', subjectName: '', direction: statement.direction === 'CREDIT' ? 'CREDIT' : 'DEBIT', amount: statement.amount ?? '' }];
  const base = entries.map((entry, index) => toRow(entry, index, false));
  const hasBankLine = base.some((row) => row.subjectName.includes('银行存款'));
  if (!hasBankLine) {
    // 兜底保证银行存款行存在（收入=借银行存款；支出=贷银行存款）
    const income = statement.direction === 'CREDIT';
    base.unshift({
      key: -1,
      summary: statement.summary || '',
      subjectCode: '',
      subjectName: '银行存款',
      debit: income ? '' : String(statement.amount ?? ''),
      credit: income ? String(statement.amount ?? '') : '',
      confidence: 1,
      human: false,
    });
  }
  return base;
}

function VoucherEditor({ statement, suggestion, onSaved, onClose }: {
  statement: StatementRecord;
  suggestion: AiVoucherSuggestion;
  onSaved: () => void;
  onClose: () => void;
}) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canReview = hasPermission('statement:review');
  const canPush = hasPermission('voucher:push');
  const canAi = hasPermission('ai:use');

  const [rows, setRows] = useState<EditableRow[]>(() => initialRows(statement, suggestion));
  const [mainSummary, setMainSummary] = useState(suggestion.suggestedSummary || statement.summary || '');
  const [busy, setBusy] = useState(false);
  const [nextKey, setNextKey] = useState(1000);

  const updateRow = (key: number, patch: Partial<EditableRow>) => {
    setRows((prev) => prev.map((row) => (row.key === key ? { ...row, ...patch, human: true } : row)));
  };
  const addRow = () => {
    setRows((prev) => [...prev, {
      key: nextKey, summary: mainSummary, subjectCode: '', subjectName: '',
      debit: '', credit: '', confidence: null, human: true,
    }]);
    setNextKey((value) => value + 1);
  };
  const removeRow = (key: number) => setRows((prev) => prev.filter((row) => row.key !== key));

  const debitTotal = rows.reduce((sum, row) => sum + num(row.debit), 0);
  const creditTotal = rows.reduce((sum, row) => sum + num(row.credit), 0);
  const diff = Math.round((debitTotal - creditTotal) * 100) / 100;
  const balanced = Math.abs(diff) <= 0.01;

  const save = async () => {
    if (!canPush) return;
    const payloadRows = rows
      .filter((row) => row.subjectName.trim() && (num(row.debit) > 0 || num(row.credit) > 0))
      .map((row) => ({
        summary: row.summary || mainSummary || undefined,
        subjectCode: row.subjectCode || undefined,
        subjectName: row.subjectName.trim(),
        direction: num(row.debit) > 0 ? ('DEBIT' as const) : ('CREDIT' as const),
        amount: num(row.debit) > 0 ? num(row.debit) : num(row.credit),
        confidence: row.confidence ?? undefined,
      }));
    if (!payloadRows.length) {
      message.warning('至少需要一条有效分录（科目名称 + 借方或贷方金额）');
      return;
    }
    if (!balanced) {
      message.warning(`借贷不平衡（差额 ${diff}），请调整金额后再保存`);
      return;
    }
    setBusy(true);
    try {
      await statementApi.saveVoucherDraft(statement.id, {
        summary: mainSummary.trim() || undefined,
        entries: payloadRows,
      });
      message.success('凭证修正已保存，推送金蝶时将带上修改后的摘要');
      onSaved();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const runReview = async (action: 'APPROVE' | 'REJECT') => {
    if (!canReview) return;
    setBusy(true);
    try {
      await statementApi.batchReview({
        ids: [statement.id],
        action,
        comment: action === 'REJECT' ? '人工驳回（凭证详情）' : undefined,
      });
      message.success(action === 'APPROVE' ? '已通过复核，可推送金蝶' : '已驳回');
      onSaved();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const push = async () => {
    if (!canPush) return;
    setBusy(true);
    try {
      await statementApi.batchPush({ ids: [statement.id] });
      message.success('已推送金蝶（提交未审核，请在金蝶侧完成最终复核）');
      onSaved();
      onClose();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '推送失败');
    } finally {
      setBusy(false);
    }
  };

  const refreshAi = async () => {
    if (!canAi) return;
    setBusy(true);
    try {
      await statementApi.refreshAiSuggestion(statement.id);
      message.success('AI 建议已重新生成');
      onSaved();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : 'AI 建议刷新失败');
    } finally {
      setBusy(false);
    }
  };

  const editable = statement.pushStatus !== 'PUSHED' && statement.reviewStatus !== 'REJECTED';
  const cellStyle = { width: '100%' } as const;

  return <>
    <Descriptions size="small" column={3} bordered>
      <Descriptions.Item label="单据类型"><strong>{statement.direction === 'CREDIT' ? '收款单' : '付款单'}</strong></Descriptions.Item>
      <Descriptions.Item label="流水号"><span className="mono">{statement.statementNo}</span></Descriptions.Item>
      <Descriptions.Item label="状态">
        <Space size={4}>
          <StatusTag status={statement.reviewStatus} />
          <StatusTag status={statement.pushStatus} />
          {suggestion.edited && <Tag color="gold">已人工修正</Tag>}
        </Space>
      </Descriptions.Item>
      <Descriptions.Item label="交易时间">{dateTime(statement.transactionTime)}</Descriptions.Item>
      <Descriptions.Item label="对手方">{statement.counterpartyName || '--'}</Descriptions.Item>
      <Descriptions.Item label="金额（元）">
        <strong style={{ color: statement.direction === 'CREDIT' ? '#cf1322' : '#1d39c4' }}>{money(statement.amount)}</strong>
      </Descriptions.Item>
    </Descriptions>

    <div style={{ margin: '14px 0 8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <span className="section-kicker">{suggestion.entries?.length ? '凭证分录（AI 预填，人工可修改）' : '凭证分录（AI 未提供分录，请人工补全）'}</span>
      {canAi && statement.reviewStatus === 'PENDING' && statement.pushStatus !== 'PUSHED' && (
        <Button size="small" icon={<RobotOutlined />} disabled={busy} onClick={() => void refreshAi()}>刷新 AI 建议</Button>
      )}
    </div>
    <table className="voucher-entry-table">
      <thead>
        <tr>
          <th style={{ width: 170 }}>摘要</th>
          <th style={{ width: 90 }}>科目编码</th>
          <th>科目名称</th>
          <th style={{ width: 120, textAlign: 'right' }}>借方金额</th>
          <th style={{ width: 120, textAlign: 'right' }}>贷方金额</th>
          <th style={{ width: 96 }}>置信度</th>
          <th style={{ width: 40 }} />
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <td><Input size="small" variant="borderless" value={row.summary} maxLength={100}
              onChange={(event) => updateRow(row.key, { summary: event.target.value })} disabled={!editable} /></td>
            <td><Input size="small" variant="borderless" className="mono" value={row.subjectCode} maxLength={32}
              placeholder="选填" onChange={(event) => updateRow(row.key, { subjectCode: event.target.value })} disabled={!editable} /></td>
            <td><Input size="small" variant="borderless" value={row.subjectName} maxLength={64}
              placeholder="科目名称" onChange={(event) => updateRow(row.key, { subjectName: event.target.value })} disabled={!editable} /></td>
            <td><InputNumber size="small" variant="borderless" style={cellStyle} min={0} precision={2} controls={false}
              value={row.debit === '' ? undefined : num(row.debit)}
              onChange={(value) => updateRow(row.key, {
                debit: value == null ? '' : String(value),
                credit: value ? '' : row.credit,
              })}
              disabled={!editable} placeholder="0.00" /></td>
            <td><InputNumber size="small" variant="borderless" style={cellStyle} min={0} precision={2} controls={false}
              value={row.credit === '' ? undefined : num(row.credit)}
              onChange={(value) => updateRow(row.key, {
                credit: value == null ? '' : String(value),
                debit: value ? '' : row.debit,
              })}
              disabled={!editable} placeholder="0.00" /></td>
            <td><ConfidenceBadge confidence={row.confidence} human={row.human} /></td>
            <td>{editable && rows.length > 2
              ? <Button type="text" size="small" icon={<DeleteOutlined />} onClick={() => removeRow(row.key)} />
              : null}</td>
          </tr>
        ))}
        <tr>
          <td colSpan={3}><strong>合计</strong></td>
          <td style={{ textAlign: 'right' }}><strong className="mono">{money(debitTotal)}</strong></td>
          <td style={{ textAlign: 'right' }}><strong className="mono">{money(creditTotal)}</strong></td>
          <td colSpan={2} />
        </tr>
      </tbody>
    </table>
    <div style={{ marginTop: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <Alert showIcon type={balanced ? 'success' : 'error'}
        style={{ flex: 1, marginRight: 8 }}
        message={balanced ? '借贷平衡' : `借贷不平衡：差额 ${diff}`} />
      <Button size="small" icon={<PlusOutlined />} disabled={!editable} onClick={addRow}>添加分录</Button>
    </div>

    <div style={{ margin: '14px 0 8px' }}>
      <span className="section-kicker">主摘要（推送金蝶时写入单据备注，留空沿用 AI 建议）</span>
    </div>
    <Input value={mainSummary} maxLength={100} disabled={!editable}
      onChange={(event) => setMainSummary(event.target.value)} placeholder="入账摘要" />

    <Alert style={{ marginTop: 12 }} type="info" showIcon={false}
      message={<span className="table-sub">
        AI 参考：{suggestion.businessCategory || '--'}
        {suggestion.confidence != null && `（整体置信度 ${Math.round(suggestion.confidence * 100)}%）`}
        {suggestion.rationale && ` ｜ 判断依据：${suggestion.rationale}`}
        {suggestion.riskNotes && suggestion.riskNotes !== '无' && ` ｜ 风险：${suggestion.riskNotes}`}
        {suggestion.model && ` ｜ 模型 ${suggestion.model}`}
        {suggestion.editedAt && ` ｜ 人工修正于 ${dateTime(suggestion.editedAt)}`}
      </span>} />

    <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end' }}>
      <Space wrap>
        {canPush && editable && <Button type="primary" ghost loading={busy} onClick={() => void save()}>保存修正</Button>}
        {canReview && statement.reviewStatus === 'PENDING' && (
          <Popconfirm title="确认通过该草稿的复核？" onConfirm={() => void runReview('APPROVE')}>
            <Button type="primary" disabled={busy}>通过复核</Button>
          </Popconfirm>
        )}
        {canReview && statement.reviewStatus === 'PENDING' && (
          <Popconfirm title="确认驳回该草稿？" onConfirm={() => void runReview('REJECT')}>
            <Button danger disabled={busy}>驳回</Button>
          </Popconfirm>
        )}
        {canPush && statement.reviewStatus === 'APPROVED' && statement.pushStatus !== 'PUSHED' && (
          <Popconfirm title="推送金蝶后单据进入金蝶待审核，确认推送？"
            description="save→submit 提交不审核；最终审核在金蝶侧人工完成。"
            onConfirm={() => void push()}>
            <Button type="primary" loading={busy}>推送金蝶</Button>
          </Popconfirm>
        )}
        {statement.pushStatus === 'PUSHED' && <Tag color="green">已推送金蝶（{statement.voucherNo || '--'}）</Tag>}
      </Space>
    </div>
  </>;
}

export function VoucherDraftDrawer({ statement, onClose, onChanged }: {
  statement?: StatementRecord;
  onClose: () => void;
  /** 任何修改（保存/通过/驳回/推送/刷新建议）后回调父级刷新表格。 */
  onChanged: () => void;
}) {
  const [version, setVersion] = useState(0);
  const loader = useCallback(
    () => (statement ? statementApi.get(statement.id) : Promise.resolve<never>(undefined as never)),
    [statement],
  );
  // version 只用作编辑器 remount 的 key，不进 useRemote 依赖——刷新走显式 reload()。
  const { data, loading, error, reload } = useRemote(loader, [loader]);
  const detail = data?.statement;
  const suggestion = data?.aiSuggestion;
  // 先拉新数据、再 bump version 重挂载编辑器（useState 初始化只在挂载时执行），
  // 保证保存/刷新 AI 建议后编辑器拿到最新分录，而不是残留旧行。
  const bumpAndReload = () => {
    void reload().then(() => setVersion((value) => value + 1));
    onChanged();
  };

  return (
    <Drawer
      title={statement ? `${statement.direction === 'CREDIT' ? '收款单' : '付款单'} · ${statement.statementNo}` : '凭证详情'}
      width={880}
      open={Boolean(statement)}
      onClose={onClose}
      destroyOnClose
    >
      {loading && !detail ? <Skeleton active paragraph={{ rows: 10 }} />
        : error ? <ResourceFailure error={error} onRetry={reload} />
          : detail ? <VoucherEditor
            key={`${detail.id}-${suggestion?.editedAt ?? 'ai'}-${version}`}
            statement={detail}
            suggestion={suggestion ?? { entries: [], balanced: null, edited: false }}
            onSaved={bumpAndReload}
            onClose={onClose}
          />
            : <Empty description="流水不存在" />}
    </Drawer>
  );
}
