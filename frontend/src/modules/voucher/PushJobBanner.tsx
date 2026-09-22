import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Progress, Space, Table, Tag, type TableColumnsType } from 'antd';
import { bankPipelineApi } from '../../services/api';
import type { PushJobResult, PushRowResult } from '../../types';
import { diagnosePushFailure } from './pushDiagnosis';

/**
 * 一键推送后台任务条（W16-A1，2026-09-22）：「一键推送至金蝶」已后台化，
 * 提交后页面不再阻塞，本组件在「凭证中心」轮询最近一个推送任务并展示进度与逐行结果。
 *
 * 口径（用户拍板）：
 *  - 摘要行 = 「自动推 X / 问题凭证 Y / 跳过 Z」（幂等行并入跳过口径）；
 *  - 失败行**必须说明原因**：行 message 原样展示 + 命中已知形态时给出根因与处置步骤（pushDiagnosis）；
 *  - 问题凭证行（PROBLEM_*）是 W16-A2 编辑器的入口线索，明细里突出「为什么进问题凭证」；
 *  - 任务级失败（status=FAILED）直接展示服务端 message（已含处置指引）。
 */

const OUTCOME_META: Record<string, { label: string; color: string }> = {
  PUSHED: { label: '自动推送成功', color: 'green' },
  ALREADY_PUSHED: { label: '此前已推送（幂等）', color: 'blue' },
  PROBLEM_CANDIDATES: { label: '问题凭证 · 多候选规则', color: 'orange' },
  PROBLEM_UNMATCHED: { label: '问题凭证 · 未命中规则', color: 'orange' },
  PROBLEM_MANUAL_AMOUNT: { label: '问题凭证 · 需人工定金额', color: 'orange' },
  PROBLEM_ELIGIBLE: { label: '问题凭证 · 流水不满足自动制证条件', color: 'orange' },
  PROBLEM_PUSH_FAILED: { label: '问题凭证 · 金蝶推送失败', color: 'orange' },
  SKIPPED_MANUAL: { label: '跳过（纯人工制证账户）', color: 'default' },
  SKIPPED: { label: '跳过', color: 'default' },
};

const POLL_INTERVAL_MS = 4000;

export function PushJobBanner({ onFinished }: { onFinished?: () => void }) {
  const [job, setJob] = useState<PushJobResult | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [dismissed, setDismissed] = useState<number | null>(null);
  const notified = useRef<number | null>(null);

  const load = useCallback(async () => {
    try {
      const latest = await bankPipelineApi.latestPushJob();
      setJob(latest);
    } catch {
      // 轮询失败不打扰用户（下次轮询或手动刷新会恢复）；无历史任务时端点返回 null 同样静默。
    }
  }, []);

  useEffect(() => {
    // 首次挂载拉一次最近任务。放到宏任务里执行，避免在 effect 体内同步 setState
    // 触发级联渲染（react-hooks/set-state-in-effect）。
    const timer = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    if (job?.status !== 'RUNNING') {
      return undefined;
    }
    const timer = window.setInterval(() => { void load(); }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [job?.status, load]);

  useEffect(() => {
    if (!job || job.status === 'RUNNING' || notified.current === job.id) {
      return;
    }
    notified.current = job.id;
    onFinished?.();
  }, [job, onFinished]);

  if (!job || dismissed === job.id) {
    return null;
  }

  const problemRows = job.rows.filter((row) => row.outcome.startsWith('PROBLEM_'));
  const failedRows = job.rows.filter((row) => row.outcome === 'PROBLEM_PUSH_FAILED');
  const handled = job.pushedCount + job.problemCount + job.skippedCount + job.alreadyCount;
  const running = job.status === 'RUNNING';

  const columns: TableColumnsType<PushRowResult & { keyIndex: number }> = [
    { title: '流水号', width: 200, render: (_, row) => <span className="mono">{row.statementNo}</span> },
    {
      title: '结果', width: 220,
      render: (_, row) => {
        const meta = OUTCOME_META[row.outcome] || { label: row.outcome, color: 'default' };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    { title: '规则号', width: 90, render: (_, row) => (row.ruleNo != null ? <span className="mono">#{row.ruleNo}</span> : '--') },
    { title: '金蝶单号', width: 130, render: (_, row) => (row.voucherNo ? <span className="mono">{row.voucherNo}</span> : '--') },
    {
      title: '说明',
      render: (_, row) => {
        const diagnosis = row.outcome === 'PROBLEM_PUSH_FAILED' ? diagnosePushFailure(row.message) : null;
        return <div>
          <div className="table-sub">{row.message || '--'}</div>
          {diagnosis && <div className="table-sub">建议：{diagnosis.steps[0]}</div>}
        </div>;
      },
    },
  ];

  return <div className="push-job-banner">
    {job.status === 'FAILED'
      ? <Alert
        type="error"
        showIcon
        message={`推送任务 #${job.id} 执行失败`}
        description={job.message || '任务执行失败，原因未记录，请查看服务端日志。'}
        action={<Button size="small" onClick={() => setDismissed(job.id)}>关闭</Button>}
      />
      : <Alert
        type={running ? 'info' : (problemRows.length || failedRows.length) ? 'warning' : 'success'}
        showIcon
        message={running
          ? `推送任务 #${job.id} 处理中（已处理 ${handled} / ${job.totalCount}）`
          : `推送任务 #${job.id} 完成：自动推 ${job.pushedCount} / 问题凭证 ${job.problemCount} / 跳过 ${job.skippedCount + job.alreadyCount}`}
        description={<Space direction="vertical" size={6} style={{ width: '100%' }}>
          {running && <Progress percent={job.totalCount ? Math.round((handled / job.totalCount) * 100) : 0} size="small" />}
          {!running && problemRows.length > 0 && <span>
            {problemRows.length} 条进「问题凭证」，原因见下方明细；请在凭证列表人工处理（科目/金额/借贷可改），处理完自动出列。
          </span>}
          {!running && job.totalCount === 0 && <span>本次没有需要处理的行。</span>}
          {expanded && job.rows.length > 0 && <Table
            size="small"
            rowKey={(row) => String(row.keyIndex)}
            pagination={false}
            columns={columns}
            dataSource={job.rows.map((row, index) => ({ ...row, keyIndex: index }))}
          />}
        </Space>}
        action={<Space>
          {job.rows.length > 0 && <Button size="small" onClick={() => setExpanded((value) => !value)}>
            {expanded ? '收起明细' : `查看明细（${job.rows.length}）`}
          </Button>}
          <Button size="small" onClick={() => setDismissed(job.id)}>关闭</Button>
        </Space>}
      />}
  </div>;
}
