import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Progress, Space, Table, Tag, type TableColumnsType } from 'antd';
import { bankPipelineApi } from '../../services/api';
import type { AiVoucherJobResult, AiVoucherRowResult } from '../../types';
import { diagnosePushFailure } from './pushDiagnosis';

/**
 * AI 制证后台任务条（2026-09-21，V40）：DRAFT 制证已改为后台任务，
 * 提交后页面不再阻塞，本组件在「凭证中心」轮询最近一个任务并展示进度与逐行结果。
 *
 * 口径：
 *  - 只在任务进行中轮询（4s 一次），完成/失败后停止，避免空转；
 *  - 失败行**必须说明原因**：行 message 原样展示 + 命中已知形态时给出根因与处置步骤（pushDiagnosis）；
 *  - 任务级失败（status=FAILED）直接展示服务端 message（已含处置指引）。
 */

const OUTCOME_META: Record<string, { label: string; color: string }> = {
  DRAFT_CREATED: { label: '草稿已生成', color: 'green' },
  PUSHED: { label: '已推送', color: 'green' },
  ALREADY_APPROVED: { label: '已通过待推送', color: 'blue' },
  ALREADY_PUSHED: { label: '此前已推送', color: 'blue' },
  SKIPPED_MANUAL: { label: '跳过（纯人工制证账户）', color: 'default' },
  SKIPPED_REJECTED: { label: '跳过（已驳回）', color: 'default' },
  FAILED_VALIDATION: { label: '校验未通过', color: 'red' },
  FAILED: { label: '失败', color: 'red' },
};

const POLL_INTERVAL_MS = 4000;

export function AiVoucherJobBanner({ onFinished }: { onFinished?: () => void }) {
  const [job, setJob] = useState<AiVoucherJobResult | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [dismissed, setDismissed] = useState<number | null>(null);
  const notified = useRef<number | null>(null);

  const load = useCallback(async () => {
    try {
      const latest = await bankPipelineApi.latestAiVoucherJob();
      setJob(latest);
    } catch {
      // 轮询失败不打扰用户（下次轮询或手动刷新会恢复）
    }
  }, []);

  useEffect(() => {
    void load();
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

  const failedRows = job.rows.filter((row) => row.outcome === 'FAILED' || row.outcome === 'FAILED_VALIDATION');
  const handled = job.draftCount + job.pushedCount + job.alreadyCount + job.skippedCount + job.failedCount;
  const running = job.status === 'RUNNING';

  const columns: TableColumnsType<AiVoucherRowResult & { keyIndex: number }> = [
    { title: '流水号', width: 200, render: (_, row) => <span className="mono">{row.statementNo}</span> },
    {
      title: '结果', width: 160,
      render: (_, row) => {
        const meta = OUTCOME_META[row.outcome] || { label: row.outcome, color: 'default' };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    { title: '科目', width: 180, render: (_, row) => row.aiSuggestedSubject || '--' },
    {
      title: '说明',
      render: (_, row) => {
        const diagnosis = row.outcome === 'FAILED' || row.outcome === 'FAILED_VALIDATION'
          ? diagnosePushFailure(row.message)
          : null;
        return <div>
          <div className="table-sub">{row.message || '--'}</div>
          {diagnosis && <div className="table-sub">建议：{diagnosis.steps[0]}</div>}
        </div>;
      },
    },
  ];

  return <div className="ai-voucher-job-banner">
    {job.status === 'FAILED'
      ? <Alert
        type="error"
        showIcon
        message={`AI 制证任务 #${job.id} 执行失败`}
        description={job.message || '任务执行失败，原因未记录，请查看服务端日志。'}
        action={<Button size="small" onClick={() => setDismissed(job.id)}>关闭</Button>}
      />
      : <Alert
        type={running ? 'info' : failedRows.length ? 'warning' : 'success'}
        showIcon
        message={running
          ? `AI 制证任务 #${job.id} 处理中（已处理 ${handled} / ${job.totalCount}）`
          : `AI 制证任务 #${job.id} 完成：草稿 ${job.draftCount} · 推送 ${job.pushedCount} · 幂等跳过 ${job.alreadyCount} · 跳过 ${job.skippedCount} · 失败 ${job.failedCount}`}
        description={<Space direction="vertical" size={6} style={{ width: '100%' }}>
          {running && <Progress percent={job.totalCount ? Math.round((handled / job.totalCount) * 100) : 0} size="small" />}
          {!running && failedRows.length > 0 && <span>
            {failedRows.length} 行失败，原因见下方明细（草稿已生成的行可在下方凭证列表人工复核后推送）。
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
