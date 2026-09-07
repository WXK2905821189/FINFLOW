/**
 * 全站系统枚举中文映射（重构方案 WP3，2026-09-07）：一处定义、全站复用。
 * 原则：
 *  - 系统自身枚举必须中文展示（任务类型/触发方式/任务状态/日志级别等）；
 *  - 银行原生返回代码（bankStatusCode 等）保持原文直出，不进本字典；
 *  - 未收录的枚举值兜底显示原文，银行新增状态不被吞。
 */

export const JOB_TYPE_TEXT: Record<string, string> = {
  STATEMENT_PULL: '流水拉取（含余额）',
};

export const TRIGGER_TYPE_TEXT: Record<string, string> = {
  MANUAL: '手动',
  SCHEDULED: '自动调度',
};

export const SYNC_STATUS_TEXT: Record<string, string> = {
  PENDING: '进行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  PARTIAL_SUCCESS: '部分成功',
  UNKNOWN: '待核实',
  DUPLICATE: '重复跳过',
  EMPTY: '无新数据',
  // 投影页部署级直联状态（与任务状态共用 StatusTag 渲染）
  REAL: '已连接直联',
  NOT_CONFIGURED: '未连接直联',
};

export const LOG_LEVEL_TEXT: Record<string, string> = {
  INFO: '信息',
  WARN: '警告',
  ERROR: '错误',
};

export const LOG_RESULT_TEXT: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
};

/** 运行日志事件类型（bank_data_sync_log.event_type，真实同步作业事件流）。 */
export const LOG_EVENT_TEXT: Record<string, string> = {
  TASK_CREATED: '任务创建',
  TASK_REUSED: '任务复用（窗口内幂等）',
  BANK_PAGE_COLLECTED: '银行报文页采集',
  RAW_MESSAGE_PERSISTED: '原始报文留存',
  STATEMENT_SAVED: '流水入库',
  STATEMENT_DEDUPLICATED: '流水去重跳过',
  BALANCE_SAVED: '余额快照入库',
  BALANCE_DEDUPLICATED: '余额快照去重',
  SYNC_COMPLETED: '同步完成',
  SYNC_FAILED: '同步失败',
};
/** 日志结果补充：银行采集事件的非成败口径结果。 */
export const LOG_RESULT_EXTENDED_TEXT: Record<string, string> = {
  ...LOG_RESULT_TEXT,
  RECORDED: '已留存',
  DUPLICATE: '重复跳过',
  REUSED: '复用',
  SKIPPED: '跳过',
};

/** StatusTag 用的合并大表：任务/时间线/日志等所有走 StatusTag 的场景一次性中文化。 */
export const STATUS_TAG_TEXT: Record<string, string> = {
  ...SYNC_STATUS_TEXT,
  ...LOG_LEVEL_TEXT,
  ...LOG_RESULT_EXTENDED_TEXT,
};

/** 通用兜底查表：空值返回空串，未收录返回原文。 */
export const dictText = (dict: Record<string, string>, value?: string | null): string =>
  value == null || value === '' ? '' : (dict[value] ?? value);

export const jobTypeText = (value?: string | null): string => dictText(JOB_TYPE_TEXT, value);
export const triggerTypeText = (value?: string | null): string => dictText(TRIGGER_TYPE_TEXT, value);
export const syncStatusText = (value?: string | null): string => dictText(SYNC_STATUS_TEXT, value);
export const logEventText = (value?: string | null): string => dictText(LOG_EVENT_TEXT, value);
export const logResultText = (value?: string | null): string => dictText(LOG_RESULT_EXTENDED_TEXT, value);
export const statusTagText = (value?: string | null): string => dictText(STATUS_TAG_TEXT, value);

/** Select 选项工厂：保持字典定义顺序。 */
const toOptions = (dict: Record<string, string>) =>
  Object.entries(dict).map(([value, label]) => ({ value, label }));

export const jobTypeOptions = toOptions(JOB_TYPE_TEXT);
export const syncStatusOptions = toOptions(SYNC_STATUS_TEXT);
