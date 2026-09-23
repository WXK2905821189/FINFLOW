import { useCallback, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Empty, Input, InputNumber, Select, Skeleton, Space, Tag, message,
} from 'antd';
import { DeleteOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { voucherProblemApi } from '../../services/api';
import { useRemote, ResourceFailure } from '../shared/components';
import { dateTime, money } from '../shared/format';
import { PROBLEM_DIMENSION_OPTIONS, problemTypeText } from './voucherTexts';
import type { VoucherProblemDetail, VoucherProblemDraftLine, VoucherProblemLinePayload } from './types';

/**
 * W16-A2 问题凭证编辑器（/statements/voucher-problems/:statementId，voucher:push）：
 *  - 数据 = GET /vouchers/problems/{id}：流水上下文 + 落桶原因 + 已保存编辑态 + 规则预填；
 *  - 编辑 = 借/贷两侧分录行（科目编码 + 维度 + 维度值 + 金额），主摘要可改；
 *  - 校验口径与后端 400 双保险一致（前端禁「保存」按钮）：
 *      每侧至少一条有效分录（科目编码 + 正数金额）/ 借贷合计 |差| ≤ 0.01 /
 *      给了维度值必须选维度类型 / 维度值含非法字符拦截；
 *  - 保存 = PUT（后端重校验 + 科目必明细，通过写 problem_edit_json，审计 PROBLEM_EDIT）；
 *  - 提交 = POST submit（编辑态优先、无则规则预填），成功 GL_PUSHED 自动出列并返回凭证中心。
 *
 * 金额精度红线：InputNumber value 与合计都走 Number 运算，提交时字符串化交给后端 BigDecimal；
 * 「金额未填」与「金额为 0」都判无效（后端 signum()<=0 400 对齐）。
 */

/** 编辑行内部形态：amount 用 null 表示未填（后端金额必须为正数）。 */
type EditorLine = {
  key: number;
  account: string;
  accountName: string;
  dimension: string;
  dimensionValue: string;
  amount: number | null;
};

const DIMENSION_VALUE_PATTERNS: Record<string, RegExp> = {
  BANK_ACCOUNT: /^[0-9A-Za-z*_-]+$/,
  ORG: /^[0-9A-Za-z._-]+$/,
  EMPLOYEE: /^[0-9A-Za-z._-]+$/,
  SUPPLIER: /^[0-9A-Za-z._-]+$/,
  CUSTOMER: /^[0-9A-Za-z._-]+$/,
  COUNTERPARTY: /^.+$/,
  FIXED: /^.+$/,
};

const lineFromPayload = (payload: Partial<VoucherProblemLinePayload>, key: number): EditorLine => ({
  key,
  account: String(payload.account ?? ''),
  accountName: String(payload.accountName ?? ''),
  dimension: String(payload.dimension ?? 'NONE'),
  dimensionValue: String(payload.dimensionValue ?? ''),
  amount: payload.amount == null || payload.amount === '' ? null : Number(payload.amount),
});

const emptyLine = (key: number): EditorLine => ({
  key, account: '', accountName: '', dimension: 'NONE', dimensionValue: '', amount: null,
});

const sum = (lines: EditorLine[]): number =>
  lines.reduce((total, line) => total + (Number.isFinite(line.amount) ? Number(line.amount) : 0), 0);

const round2 = (value: number): number => Math.round(value * 100) / 100;

export function VoucherProblemEditorPage() {
  const params = useParams();
  const statementId = Number(params.statementId);
  const navigate = useNavigate();
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const loader = useCallback(() => voucherProblemApi.get(statementId), [statementId]);
  const { data, loading, error, reload } = useRemote(loader, [loader]);

  // 编辑态初始化策略：已保存编辑态（editDoc）优先；否则用规则预填（manual 行金额置空）；
  // 都没有则给一侧一行空白行。idRange 与 detail 分离，避免 detail 刷新时覆盖正在编辑的内容。
  const [debitLines, setDebitLines] = useState<EditorLine[]>();
  const [creditLines, setCreditLines] = useState<EditorLine[]>();
  const [summary, setSummary] = useState<string>();
  const [seededFor, setSeededFor] = useState<number>();

  const seedFrom = useCallback((detail: VoucherProblemDetail) => {
    // 行来源二选一：已保存编辑态（editDoc）优先直通；否则规则预填行——
    // manual（金额未定）行金额置空，与后端「预填含人工分摊行禁直推」口径一致。
    const editDocDebits = detail.editDoc?.debitLines || [];
    const editDocCredits = detail.editDoc?.creditLines || [];
    const hasEditDoc = editDocDebits.length > 0 || editDocCredits.length > 0;
    const toPayload = (line: VoucherProblemLinePayload & Partial<VoucherProblemDraftLine>) => ({
      account: line.account,
      accountName: line.accountName,
      dimension: line.dimension,
      dimensionValue: line.dimensionValue,
      // manual 行（金额未定）必须置空，前端与后端一致拒绝直接推送
      amount: !hasEditDoc && line.manual ? null : line.amount,
      share: line.share,
    });
    const debitSource = (hasEditDoc ? editDocDebits : (detail.prefillDebitLines || [])) as Array<VoucherProblemLinePayload & Partial<VoucherProblemDraftLine>>;
    const creditSource = (hasEditDoc ? editDocCredits : (detail.prefillCreditLines || [])) as Array<VoucherProblemLinePayload & Partial<VoucherProblemDraftLine>>;
    const debits = debitSource.map((line, index) => lineFromPayload(toPayload(line), index + 1));
    const credits = creditSource.map((line, index) =>
      lineFromPayload(toPayload(line), debitSource.length + 1 + index));
    const fallbackKey = debitSource.length + creditSource.length + 1;
    setDebitLines(debits.length ? debits : [emptyLine(fallbackKey)]);
    setCreditLines(credits.length ? credits : [emptyLine(fallbackKey + 1)]);
    setSummary(detail.editDoc?.summary ?? detail.row.summary ?? '');
    setSeededFor(detail.row.id);
  }, []);

  // 首次装载后种子化（渲染期 setState 是 React 官方认可的「derived state」模式：
  // 触发立即重渲，不额外请求；statementId 变化时按新 id 重新种子）。
  if (data && seededFor !== data.row.id) {
    seedFrom(data);
  }

  const detail = data;
  const row = detail?.row;

  const balance = useMemo(() => ({
    debit: round2(sum(debitLines || [])),
    credit: round2(sum(creditLines || [])),
  }), [debitLines, creditLines]);
  const diff = round2(Math.abs(balance.debit - balance.credit));

  /** 行有效性：科目编码非空 + 金额为正数（与后端 normalizeSide 一致）。 */
  const lineValid = (line: EditorLine): boolean =>
    Boolean(line.account.trim()) && line.amount != null && Number.isFinite(line.amount) && line.amount > 0;

  /** 维度校验：给了维度值必须选类型；类型值字符集按映射档案口径。 */
  const lineDimensionIssue = (line: EditorLine): string | null => {
    const dimension = line.dimension === 'NONE' ? '' : line.dimension;
    if (line.dimensionValue.trim() && !dimension) return '给了维度值但未选择维度类型';
    if (dimension && line.dimensionValue.trim()) {
      const pattern = DIMENSION_VALUE_PATTERNS[dimension];
      if (pattern && !pattern.test(line.dimensionValue.trim())) return '维度值含档案编码不允许的字符';
    }
    return null;
  };

  const editDocIssues = useMemo(() => {
    const issues: string[] = [];
    const debits = (debitLines || []).filter(lineValid);
    const credits = (creditLines || []).filter(lineValid);
    if (!debits.length) issues.push('借方至少需要一条有效分录（科目编码 + 正数金额）');
    if (!credits.length) issues.push('贷方至少需要一条有效分录（科目编码 + 正数金额）');
    if (diff > 0.01) issues.push(`借贷不平衡：借 ${balance.debit} / 贷 ${balance.credit}（差额 ${diff} 超过 0.01）`);
    for (const line of [...(debitLines || []), ...(creditLines || [])]) {
      const issue = lineDimensionIssue(line);
      if (issue) {
        issues.push(`科目 ${line.account || '(未填科目)'}：${issue}`);
        break;
      }
    }
    return issues;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debitLines, creditLines, balance.debit, balance.credit, diff]);

  const canSave = editDocIssues.length === 0;

  const buildEditDoc = () => ({
    summary: (summary || '').trim() || null,
    debitLines: (debitLines || []).filter(lineValid).map((line) => ({
      account: line.account.trim(),
      accountName: line.accountName.trim() || null,
      dimension: line.dimension === 'NONE' ? null : line.dimension,
      dimensionValue: line.dimension === 'NONE' ? null : line.dimensionValue.trim() || null,
      amount: line.amount,
      share: 'MANUAL',
      note: null,
    })),
    creditLines: (creditLines || []).filter(lineValid).map((line) => ({
      account: line.account.trim(),
      accountName: line.accountName.trim() || null,
      dimension: line.dimension === 'NONE' ? null : line.dimension,
      dimensionValue: line.dimension === 'NONE' ? null : line.dimensionValue.trim() || null,
      amount: line.amount,
      share: 'MANUAL',
      note: null,
    })),
  });

  const save = async () => {
    if (!statementId || saving) return;
    setSaving(true);
    try {
      await voucherProblemApi.save(statementId, buildEditDoc());
      message.success('编辑已保存（后端重校验通过），可提交推送');
      void reload();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存未能完成');
    } finally {
      setSaving(false);
    }
  };

  const submit = async () => {
    if (!statementId || submitting) return;
    setSubmitting(true);
    try {
      const result = await voucherProblemApi.submit(statementId);
      message.success(`推送成功：金蝶凭证号 ${result.voucherNo || '--'}（问题凭证已出列）`);
      navigate('/statements/vouchers');
    } catch (reason) {
      // 失败留桶：后端已刷新 problem_reason，刷新页面让最新原因直接可见
      message.error(reason instanceof Error ? reason.message : '提交推送未能完成');
      void reload();
    } finally {
      setSubmitting(false);
    }
  };

  const updateLine = (side: 'debit' | 'credit', key: number, patch: Partial<EditorLine>) => {
    const setter = side === 'debit' ? setDebitLines : setCreditLines;
    setter((lines) => (lines || []).map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };
  const removeLine = (side: 'debit' | 'credit', key: number) => {
    const setter = side === 'debit' ? setDebitLines : setCreditLines;
    setter((lines) => (lines || []).filter((line) => line.key !== key));
  };
  const addLine = (side: 'debit' | 'credit') => {
    const setter = side === 'debit' ? setDebitLines : setCreditLines;
    setter((lines) => [...(lines || []), emptyLine(Date.now())]);
  };

  const renderLines = (side: 'debit' | 'credit', lines: EditorLine[]) => <Space direction="vertical" size={8} style={{ width: '100%' }}>
    {lines.map((line) => {
      const dimensionIssue = lineDimensionIssue(line);
      return <Space.Compact key={line.key} style={{ width: '100%' }} block>
        <Input
          placeholder="科目编码（必填，须为明细科目）"
          style={{ width: 190 }}
          value={line.account}
          onChange={(event) => updateLine(side, line.key, { account: event.target.value })}
        />
        <Input
          placeholder="科目名称（选填）"
          style={{ width: 150 }}
          value={line.accountName}
          onChange={(event) => updateLine(side, line.key, { accountName: event.target.value })}
        />
        <Select
          style={{ width: 140 }}
          value={line.dimension}
          options={PROBLEM_DIMENSION_OPTIONS}
          onChange={(value) => updateLine(side, line.key, { dimension: String(value) })}
        />
        <Input
          placeholder="维度值（档案编码）"
          style={{ width: 140 }}
          value={line.dimensionValue}
          status={dimensionIssue ? 'error' : undefined}
          onChange={(event) => updateLine(side, line.key, { dimensionValue: event.target.value })}
        />
        <InputNumber
          placeholder="金额"
          min={0}
          precision={2}
          style={{ width: 140 }}
          value={line.amount}
          status={line.amount != null && line.amount <= 0 ? 'error' : undefined}
          onChange={(value) => updateLine(side, line.key, { amount: typeof value === 'number' ? value : null })}
        />
        <Button
          icon={<DeleteOutlined />}
          disabled={(debitLines || []).length <= 1 && side === 'debit' && (creditLines || []).length === 0}
          onClick={() => removeLine(side, line.key)}
        />
      </Space.Compact>;
    })}
    <div>
      <Button size="small" icon={<PlusOutlined />} onClick={() => addLine(side)}>
        添加{side === 'debit' ? '借方' : '贷方'}分录
      </Button>
    </div>
  </Space>;

  if (!statementId) {
    return <Card><Empty description="缺少流水参数" /></Card>;
  }

  return <div>
    <div className="page-heading">
      <div>
        <span className="section-kicker">凭证中心 / 问题凭证编辑器</span>
        <h2>修复流水 {row?.statementNo || `#${statementId}`}</h2>
        <p className="muted">
          修正科目、金额、借贷与辅助核算后保存（后端重校验：借贷平衡 / 科目必明细），再提交推送金蝶；成功自动出列。
        </p>
      </div>
      <Space wrap>
        <Button onClick={() => navigate('/statements/vouchers')}>返回凭证中心</Button>
        <Button onClick={() => navigate('/statements/voucher-problems')}>问题列表</Button>
      </Space>
    </div>
    {error ? <ResourceFailure error={error} onRetry={reload} />
      : loading || !detail || !debitLines ? <Card><Skeleton active paragraph={{ rows: 8 }} /></Card>
        : <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Card size="small" title="流水上下文与落桶原因">
            <Descriptions size="small" column={{ xs: 1, sm: 2, xl: 4 }}>
              <Descriptions.Item label="问题类型">
                <Tag color={detail.row.problemType === 'PROBLEM_PUSH_FAILED' ? 'red' : 'orange'}>
                  {problemTypeText(detail.row.problemType)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="交易时间">{dateTime(detail.transactionTime || undefined)}</Descriptions.Item>
              <Descriptions.Item label="金额">{money(detail.amount ?? undefined)} {detail.currency || ''}</Descriptions.Item>
              <Descriptions.Item label="方向">{detail.direction === 'INCOME' ? '收入' : detail.direction === 'EXPENSE' ? '支出' : '--'}</Descriptions.Item>
              <Descriptions.Item label="对手方">{detail.counterpartyName || '--'}</Descriptions.Item>
              <Descriptions.Item label="复核状态">{detail.reviewStatus || '--'}</Descriptions.Item>
              <Descriptions.Item label="推送状态">{detail.pushStatus || '--'}</Descriptions.Item>
              <Descriptions.Item label="金蝶凭证号">{detail.voucherNo || '--'}</Descriptions.Item>
            </Descriptions>
            {detail.row.problemReason && <Alert
              type="warning"
              showIcon
              message="落桶原因"
              description={detail.row.problemReason}
            />}
            {!detail.editDoc && (detail.prefillDebitLines?.length || detail.prefillCreditLines?.length) ? <Alert
              type="info"
              showIcon
              message={`规则预填已载入下方编辑区（规则 #${detail.prefillRuleNo ?? '--'} · ${detail.prefillBusinessType || '--'}）${detail.prefillNeedManualAmount ? '；预填含人工分摊行，请补齐金额' : ''}`}
            /> : null}
            {detail.editDoc ? <Alert
              type="info"
              showIcon
              message={`已载入保存过的编辑态（${detail.editDoc.debitLines?.length || 0} 借 / ${detail.editDoc.creditLines?.length || 0} 贷），可继续修改或直接提交推送`}
            /> : null}
          </Card>

          <Card size="small" title="分录编辑">
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Input
                addonBefore="主摘要"
                value={summary || ''}
                onChange={(event) => setSummary(event.target.value)}
                placeholder="凭证主摘要（回写金蝶单据备注）"
              />
              <Card type="inner" size="small" title={`借方分录（合计 ¥${balance.debit.toFixed(2)}）`}>
                {renderLines('debit', debitLines)}
              </Card>
              <Card type="inner" size="small" title={`贷方分录（合计 ¥${balance.credit.toFixed(2)}）`}>
                {renderLines('credit', creditLines)}
              </Card>
              <div>
                {diff <= 0.01 && balance.debit > 0
                  ? <Tag color="green">借贷平衡</Tag>
                  : <Tag color="red">借贷差额 ¥{diff.toFixed(2)}</Tag>}
              </div>
              {editDocIssues.length > 0 && <Alert
                type="error"
                showIcon
                message="校验未通过（修正后才能保存）"
                description={<ul style={{ margin: 0, paddingLeft: 18 }}>
                  {editDocIssues.map((issue) => <li key={issue}>{issue}</li>)}
                </ul>}
              />}
            </Space>
          </Card>

          <Card size="small" title="保存与提交">
            <Space wrap>
              <Button type="default" loading={saving} disabled={!canSave} onClick={() => void save()}>
                保存编辑（后端重校验）
              </Button>
              <Button
                type="primary"
                icon={<SendOutlined />}
                loading={submitting}
                onClick={() => void submit()}
              >
                提交推送金蝶
              </Button>
              <span className="table-sub">
                未保存过编辑态时，「提交推送」按规则预填直推（预填含人工分摊行会被拒绝）。
              </span>
            </Space>
          </Card>
        </Space>}
  </div>;
}
