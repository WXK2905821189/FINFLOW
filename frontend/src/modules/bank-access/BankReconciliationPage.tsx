import { useCallback, useState } from 'react';
import { Alert, Card, Pagination, Table, Tag, Tooltip, type TableColumnsType } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { bankPipelineApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import { dateTime, displayValue, money } from '../shared/format';
import type { PageResponse } from '../shared/api';
import type { BankTaskReconciliationRow } from './types';

const consistentTag = (value?: boolean | null) => {
  if (value === null || value === undefined) {
    return (
      <Tooltip title="该窗口银行未回传 Z1 合计（null = 银行没说），无法核对——既不是一致也不是不一致。">
        <Tag>银行未回传合计<QuestionCircleOutlined /></Tag>
      </Tooltip>
    );
  }
  return value ? <Tag color="green">一致</Tag> : <Tag color="red">不一致</Tag>;
};

/**
 * 任务级对账核对：银行自己声明的 Z1 借贷笔数/合计 vs 平台实际入库的流水行。
 * 数据同源（同步任务行 + 流水表聚合），页面只做勾稽展示，不做任何业务翻译。
 */
export function BankReconciliationPage() {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);

  const loader = useCallback(() => bankPipelineApi.taskReconciliation({ page, size }), [page, size]);
  const { data, loading, error, reload } = useRemote<PageResponse<BankTaskReconciliationRow>>(loader, [loader]);

  const columns: TableColumnsType<BankTaskReconciliationRow> = [
    { title: '任务号', dataIndex: 'taskNo', width: 180, render: (value) => <span className="mono">{displayValue(value)}</span> },
    { title: '适配器', dataIndex: 'adapterCode', width: 90, render: (value) => <span className="mono">{displayValue(value)}</span> },
    { title: '任务状态', dataIndex: 'status', width: 110, render: (value) => <StatusTag status={value} /> },
    {
      title: '同步窗口',
      width: 280,
      render: (_, row) => <span className="mono">{dateTime(row.windowStart)} ~ {dateTime(row.windowEnd)}</span>,
    },
    {
      title: '银行 Z1 · 借方（付）',
      width: 170,
      align: 'right',
      render: (_, row) => (
        <span className="mono">
          {row.bankDebitNums === null || row.bankDebitNums === undefined ? '--' : `${row.bankDebitNums} 笔 / ${money(row.bankDebitAmount ?? 0)}`}
        </span>
      ),
    },
    {
      title: '银行 Z1 · 贷方（收）',
      width: 170,
      align: 'right',
      render: (_, row) => (
        <span className="mono">
          {row.bankCreditNums === null || row.bankCreditNums === undefined ? '--' : `${row.bankCreditNums} 笔 / ${money(row.bankCreditAmount ?? 0)}`}
        </span>
      ),
    },
    {
      title: '平台 · 借方（付）',
      width: 170,
      align: 'right',
      render: (_, row) => <span className="mono">{row.platformExpenseCount} 笔 / {money(row.platformExpenseAmount)}</span>,
    },
    {
      title: '平台 · 贷方（收）',
      width: 170,
      align: 'right',
      render: (_, row) => <span className="mono">{row.platformIncomeCount} 笔 / {money(row.platformIncomeAmount)}</span>,
    },
    {
      title: '笔数勾稽',
      width: 120,
      render: (_, row) => consistentTag(row.countConsistent),
    },
    {
      title: '金额勾稽',
      width: 120,
      render: (_, row) => consistentTag(row.amountConsistent),
    },
  ];

  return <>
    <div className="page-heading">
      <div>
        <span className="section-kicker">银行数据 / 对账核对</span>
        <h2>对账核对</h2>
        <p className="muted">银行自己声明的窗口合计（Z1）与平台实际入库流水的勾稽视图：银行说收了 N 笔，库里就得有 N 笔，金额分毫不差。</p>
      </div>
    </div>
    <Alert
      className="phase-one-notice"
      type="info"
      showIcon
      message="勾稽口径"
      description="银行 Z1 为银行对该窗口的借/贷笔数与金额合计（借方=付，贷方=收，金额为无符号合计）；平台侧按同一任务入库的流水行聚合。银行未回传 Z1 的窗口不参与核对。"
    />
    <Card title="同步任务对账">
      {error ? <ResourceFailure error={error} onRetry={reload} /> : (
        <>
          <Table rowKey="taskId" loading={loading} columns={columns} dataSource={data?.records || []}
            pagination={false}
            locale={{ emptyText: '暂无同步任务。创建并执行一次银行数据同步后，这里会出现勾稽结果。' }}
            scroll={{ x: 1500 }} />
          {data && <Pagination className="table-pagination" current={data.page} pageSize={data.size || size}
            total={data.total} showSizeChanger pageSizeOptions={[10, 20, 50]}
            onChange={(next, nextSize) => { setPage(next); setSize(nextSize); }} />}
        </>
      )}
    </Card>
  </>;
}
