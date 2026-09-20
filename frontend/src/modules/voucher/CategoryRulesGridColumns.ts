import { esc, type GridColumn, type GridRow } from '../bank-access/grid/kernel';
import { lineSummary } from './voucherTexts';
import type { VoucherRuleRow } from './types';

/**
 * W10（WP-6）：规则中心 Excel 内核列定义。
 *
 * 与 antd 版口径一致（列顺序/标题/取值），额外提供：
 *  · 首列「拖」手柄（`draggable`）—— 交给页面在容器上做事件委托，拖到左侧分组完成换组（WP-7）；
 *    手柄带 `.btn` 类，命中内核 NO_SELECT 放行规则，拖拽时不会误触发整行勾选/选区；
 *  · 操作列的编辑/启停/删除由 `data-row-action` 回投页面（删除的二次确认改由 Modal.confirm 承担）。
 */

const ORG_NAMES: Record<string, string> = {
  '300': '300 主体', '400': '400 主体', '710': '710 主体', '720': '720 主体', '900': '900 主体',
};

const orgText = (orgs: string[]) => {
  if (!orgs || orgs.length === 0) return '<span class="tag tag-muted">全部</span>';
  return esc(orgs.map((org) => ORG_NAMES[org] || org).join('/'));
};

const directionHtml = (direction: string) => direction === 'INCOME'
  ? '<span class="tag tag-ok">收</span>'
  : direction === 'EXPENSE'
    ? '<span class="tag tag-warn">付</span>'
    : '<span class="tag tag-muted">任意</span>';

const action = (name: string, label: string, danger = false) =>
  '<button class="btn btn-sm' + (danger ? ' btn-danger' : '') + '" data-row-action="' + name + '">'
  + esc(label) + '</button>';

export function categoryRulesColumns(): GridColumn[] {
  return [
    {
      k: 'drag', t: '拖', w: 54, on: true, req: true, def: '必需，不可关闭', type: 'text',
      cell: (row) => '<span class="btn btn-sm drag-handle" draggable="true" data-drag-rule="'
        + esc(row.id) + '" title="拖到左侧分组完成换组">⠿</span>',
      text: () => '',
    },
    {
      k: 'ruleNo', t: '规则号', w: 82, on: true, type: 'text', filter: 'num',
      cell: (row) => '<span class="mono">' + esc(row.ruleNo) + '</span>',
      text: (row) => String(row.ruleNo ?? ''),
    },
    {
      k: 'businessType', t: '业务大类', w: 190, on: true, type: 'text', filter: 'text',
      cell: (row) => esc(row.businessType),
      text: (row) => String(row.businessType || ''),
    },
    {
      k: 'category', t: '类别', w: 100, on: true, type: 'text', filter: 'value',
      cell: (row) => esc(row.category || '--'),
      text: (row) => String(row.category || ''),
    },
    {
      k: 'direction', t: '方向', w: 72, on: true, type: 'text', filter: 'value',
      cell: (row) => directionHtml(String(row.direction || 'ANY')),
      text: (row) => String(row.direction || ''),
    },
    {
      k: 'groupName', t: '分组', w: 120, on: true, type: 'text', filter: 'value',
      cell: (row) => (row.groupName
        ? '<span class="tag tag-info">' + esc(row.groupName) + '</span>'
        : '<span class="tag tag-muted">未分组</span>'),
      text: (row) => String(row.groupName || '未分组'),
    },
    {
      k: 'matchKeywords', t: '匹配关键词', w: 190, on: true, type: 'text', filter: 'text',
      cell: (row) => {
        const conditions = (row.match?.conditions as Array<{ values: string[] }> | undefined) || [];
        return conditions.length
          ? esc(conditions.map((condition) => condition.values.join('/')).join('；'))
          : '<span class="mono">--</span>';
      },
      text: (row) => {
        const conditions = (row.match?.conditions as Array<{ values: string[] }> | undefined) || [];
        return conditions.map((condition) => condition.values.join('/')).join('；');
      },
    },
    {
      k: 'debit', t: '默认借方', w: 190, on: true, type: 'text', filter: 'text',
      cell: (row) => {
        const lines = (row.debitLines as VoucherRuleRow['debitLines']) || [];
        return esc(lineSummary(lines[0]) + (lines.length > 1 ? ` 等 ${lines.length} 行` : ''));
      },
      text: (row) => {
        const lines = (row.debitLines as VoucherRuleRow['debitLines']) || [];
        return lineSummary(lines[0]) + (lines.length > 1 ? ` 等 ${lines.length} 行` : '');
      },
    },
    {
      k: 'credit', t: '默认贷方', w: 190, on: true, type: 'text', filter: 'text',
      cell: (row) => {
        const lines = (row.creditLines as VoucherRuleRow['creditLines']) || [];
        return esc(lineSummary(lines[0]) + (lines.length > 1 ? ` 等 ${lines.length} 行` : ''));
      },
      text: (row) => {
        const lines = (row.creditLines as VoucherRuleRow['creditLines']) || [];
        return lineSummary(lines[0]) + (lines.length > 1 ? ` 等 ${lines.length} 行` : '');
      },
    },
    {
      k: 'priority', t: '优先级', w: 84, on: true, type: 'num', align: 'num', filter: 'num',
      cell: (row) => '<span class="mono">' + esc(row.priority) + '</span>',
      text: (row) => String(row.priority ?? ''),
    },
    {
      k: 'scopeOrgs', t: '主体', w: 110, on: true, type: 'text', filter: 'value',
      cell: (row) => orgText((row.scopeOrgs as string[]) || []),
      text: (row) => ((row.scopeOrgs as string[]) || []).join('/'),
    },
    {
      k: 'enabled', t: '状态', w: 96, on: true, type: 'text', filter: 'value',
      cell: (row) => (row.enabled
        ? '<span class="tag tag-ok">启用</span>'
        : '<span class="tag tag-muted">已停用</span>'),
      text: (row) => (row.enabled ? '启用' : '已停用'),
    },
    {
      k: 'actions', t: '操作', w: 200, on: true, req: true, def: '必需，不可关闭', type: 'text',
      cell: (row) => [
        action('edit', '编辑'),
        action('toggle', row.enabled ? '停用' : '启用'),
        action('delete', '删除', true),
      ].join(' '),
      text: () => '',
    },
  ];
}

export function toRuleGridRows(rules: VoucherRuleRow[]): GridRow[] {
  return rules as unknown as GridRow[];
}
