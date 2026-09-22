/* ==================================================================
   V35 · 表格内核（Excel 化）——余额查询 / 流水查询
   ------------------------------------------------------------------
   移植自 docs/ui-v34-demo.html 的高保真稿（内核源码 tmp/kernel.js），
   口径按用户 2026-09-17 拍板，**不要擅自改**：
   ① 排序口径 = 「仅当前页排序」（零后端改造）。因此必须在界面上常驻标注
      「本页」，否则用户会把本页排序当成全量排序 —— 财务场景红线。
   ② 「合计」两个口径并存且各自标清：
      · 状态栏 = 本页可见小计（受列头筛选影响）
      · 工具栏 = 服务端全量聚合（只含查询级条件，不含本页列头筛选）
   ③ 视图偏好 = 服务端账号级（跨设备一致），不是本机 localStorage。
      落库走 /api/preferences/{scope}（V35 迁移 account_preference）。
   ④ 导出 = 当前查询条件的全量结果；有选区时另给「仅导出选中行」。
   ------------------------------------------------------------------
   与 demo 的差异（都是落地必需，不改语义）：
   · 宿主节点不再用 document 全局 id，改为在 opts.root 内按 data-grid-role 查找
     —— 余额页与流水页会同时存在，全局 id 会互抢。
   · window.toast / window.closePops 改为 opts 注入（React 侧提供）。
   · 条件格式 cf、行样式 rowClass、行可选中性 isRowSelectable、行内动作
     onRowAction、导出 onExport/onExportRows 全部改为声明式回调，
     让内核与具体业务字段解耦。
   · 新增 setRows / setTotalAgg / destroy，供 React 数据刷新时复用同一实例
     （不销毁重建，避免 document 级监听器累积）。
   ================================================================== */

/** 行模型：内核不解释字段语义，只做排序 / 筛选 / 渲染。 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type GridRow = Record<string, any>;

export type GridCellAlign = 'num' | undefined;
export type GridColumnType = 'text' | 'money' | 'num';
/**
 * 列头筛选的**声明**口径：这一列允许开哪种筛选器。
 * 'value' = 按值勾选、'text' = 文本包含、'num' = 数值区间、'date' = 日期区间、null = 不给筛选入口。
 */
export type GridFilterDecl = 'value' | 'text' | 'num' | 'date' | null;
/**
 * 筛选**状态**口径。注意：按值勾选在状态里叫 'values'（复数），
 * 与声明口径的 'value' 不是一个字面量 —— 两者混用会被 TS 直接判为无重叠比较。
 */
export type GridFilterKind = 'values' | 'text' | 'num' | 'date';

export interface GridColumn {
  /** 字段键（对应行上的属性名）。 */
  k: string;
  /** 列标题。 */
  t: string;
  /** 列宽（px）。 */
  w: number;
  /** 是否可见。 */
  on: boolean;
  /** 列设置里的角标文案（「默认」「必需，不可关闭」）。 */
  def?: string;
  /** 必需列：不允许关闭（藏起来会让未采集账户看起来像正常数据）。 */
  req?: boolean;
  align?: GridCellAlign;
  type: GridColumnType;
  filter?: GridFilterDecl;
  /**
   * V36 筛选服务端化：该列筛选由服务端执行 —— 内核通过 onFilterChange 把筛选集合交给页面，
   * 页面映射成查询参数重新请求；内核本身**不再做本地过滤**（本地再过一遍会与「服务端已过滤」
   * 的行集口径分裂，全量合计与翻页会对不上）。chips 上标【全量】，筛选浮层副标题同步改口径。
   */
  filterServer?: boolean;
  /**
   * W16-B5 排序服务端化：该列排序由服务端执行 —— 内核通过 onSortChange 把排序规格交给页面，
   * 页面映射成查询参数重新请求；内核本身**不再对当前页做本地重排**（对已分页的行集本地排序
   * 只会打乱本页顺序，得不到「全量排序」语义）。表头箭头 / 视图 / 偏好快照照常工作。
   * 当前仅支持单列（交易时间）；多列或混合（一列 server 一列本地）时不生效，回落本地排序。
   */
  sortServer?: boolean;
  /** 列头筛选输入框的占位文案（缺省「包含文本，如 货款」；账号尾号这类后缀语义列必须显式声明）。 */
  filterPlaceholder?: string;
  /** 「无发生额」的 0 视同空值（借贷双轨列）。 */
  emptyZero?: boolean;
  /** 单元格 HTML。 */
  cell: (row: GridRow) => string;
  /**
   * 纯文本取值（TSV 复制 / 导出用）。
   * 必须给「k 不是真实行字段」的列（如账号 / 收付方 / 摘要这类由多字段合成的列）声明，
   * 否则 rawCell 会去读 row[k] 拿到空值，复制进 Excel 就是一整列空白。
   */
  text?: (row: GridRow) => string;
  /** 条件格式：返回附加 class（'' 表示无）。 */
  cf?: (row: GridRow) => string;
}

export interface GridSortSpec {
  k: string;
  dir: 1 | -1;
}

export type GridFilter =
  | { kind: 'values'; set: string[] }
  | { kind: 'text'; q: string }
  | { kind: 'num'; min: string; max: string }
  | { kind: 'date'; from: string; to: string };

export interface GridView {
  name: string;
  desc: string;
  on: string[];
  order?: string[];
  w?: { k: string; w: number }[];
  sort: GridSortSpec[];
  filters: Record<string, GridFilter>;
  density?: GridDensity;
  frozen?: number;
}

export type GridDensity = 'compact' | 'comfortable';

/** 持久化快照（口径③：存服务端账号级）。 */
export interface GridSnapshot {
  on: string[];
  order: string[];
  w: { k: string; w: number }[];
  sort: GridSortSpec[];
  filters: Record<string, GridFilter>;
  density: GridDensity;
  frozen: number;
  view: string | null;
  views: GridView[];
}

export interface GridOptions {
  /** 内核挂载根节点；内部按 data-grid-role 查找各个协作节点。 */
  root: HTMLElement;
  id: string;
  cols: GridColumn[];
  rows: GridRow[];
  /** 分组键（行上的字段名）；不给则平铺。 */
  groupBy?: string;
  groupedDefault?: boolean;
  /** 无行且无本页筛选时的空态文案（由页面按「直连未启用 / 查询失败 / 无匹配」区分）。 */
  emptyText?: string;
  groupMeta?: (group: string, rows: GridRow[]) => string;
  views?: GridView[];
  density?: GridDensity;
  frozen?: number;
  /** 行样式（如未接入直连的降权行）。 */
  rowClass?: (row: GridRow) => string;
  /** 是否渲染行勾选列（余额页不需要；流水页仅在持有 AI 制证权限时为 true）。默认 true。 */
  selectable?: boolean;
  /** 行是否可勾选（如已推送 / 纯人工制证账户禁选）。 */
  isRowSelectable?: (row: GridRow) => boolean;
  /** 点了不可勾选行的提示文案；给函数时按行取（已推送 / 纯人工制证账户原因不同）。 */
  disabledRowHint?: string | ((row: GridRow) => string);
  /** 行内动作按钮（[data-row-action]）与复制标签（[data-copy]）的回调。 */
  onRowAction?: (action: string, row: GridRow) => void;
  /** 勾选行变化（供页面消费，如 AI 制证的「已选 N 条」）。 */
  onSelectionChange?: (rows: GridRow[]) => void;
  onExport?: () => void;
  onExportRows?: (rows: GridRow[]) => void;
  /** 导出按钮默认文案（无选中行时）；有选中行时自动切成「仅导出选中 N 行」。 */
  exportLabel?: string;
  toast?: (message: string) => void;
  onClosePops?: () => void;
  /** 快照变化时回调（口径③：由调用方落服务端账号级偏好）。 */
  onSnapshotChange?: (snapshot: GridSnapshot) => void;
  /**
   * V36 筛选服务端化：筛选集合变化时回调（应用 / 清除 / 全部清除 / 视图与偏好恢复都会触发；
   * 挂载首渲染不触发）。页面把可服务端化的列映射成查询参数重新请求，
   * 不可映射的列仍按「仅本页」在内核本地过滤。
   */
  onFilterChange?: (filters: Record<string, GridFilter>) => void;
  /**
   * W16-B5 排序服务端化：排序规格变化时回调（表头点击 / 清除 / 视图与偏好恢复都会触发；
   * 挂载首渲染不触发）。页面把 sortServer 列映射成查询参数重新请求。
   */
  onSortChange?: (sort: GridSortSpec[]) => void;
}

export interface GridState {
  id: string;
  cols: GridColumn[];
  rows: GridRow[];
  sort: GridSortSpec[];
  filters: Record<string, GridFilter>;
  grouped: boolean;
  density: GridDensity;
  frozen: number;
  views: GridView[];
  view: string | null;
}

export interface GridInstance {
  state: GridState;
  setRows: (rows: GridRow[]) => void;
  setCols: (cols: GridColumn[]) => void;
  /** W10：行首复选框列的开关（页面权限异步就绪后调用，补齐挂载时缺失的列）。 */
  setSelectable: (v: boolean) => void;
  /** W10：空态文案实时更新 —— 原为挂载时快照，首屏加载期挂载会把「正在加载……」永久锁死，
      导致关键字无匹配等 0 行场景一直显示加载中文案（用户反馈的误解来源）。 */
  setEmptyText: (text: string) => void;
  /** W10：导出按钮文案实时更新（「导出中…」状态原先同样被挂载快照吞掉）。 */
  setExportLabel: (text: string) => void;
  snapshot: () => GridSnapshot;
  applySnapshot: (snapshot: Partial<GridSnapshot>) => void;
  /** 打开快照回调。必须在「已从服务端载入偏好」之后再调用 —— 否则首次挂载会用
      内置默认值回调一次，把服务端已保存的视图覆盖成默认。 */
  enableSnapshotNotify: () => void;
  destroy: () => void;
}

const COPY_SVG =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/></svg>';

// 2026-09-21 口径统一：删除本文件里那份只认 ISO 码的 CURRENCY_TEXT / currencyText（死代码，无人 import）。
// 币种中文名的唯一来源是 `bankQueryTexts.ts`（屏幕用）与后端 `BankDataExportService`（导出用），两者已对齐。
export const copyChip = (value: string, title = '复制'): string =>
  `<button class="copy-chip" data-copy="${esc(value)}" title="${esc(title)}">${COPY_SVG}复制</button>`;

/**
 * W8（2026-09-20）剪贴板写入（安全上下文感知降级）：
 * navigator.clipboard 只在 HTTPS/localhost 等安全上下文可用——生产 ECS 是 http 裸 IP，
 * clipboard 为 undefined，此前「提示成功但粘贴板为空」就是它静默失败。
 * 非安全上下文走 textarea + execCommand('copy')（同步、无需权限）。
 */
export function copyText(text: string): void {
  if (!text) return;
  if (navigator.clipboard && window.isSecureContext) {
    void navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    return;
  }
  fallbackCopy(text);
}

function fallbackCopy(text: string): void {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    // 定位到可视区域外但避免 iOS 聚焦滚动；opacity 防闪烁。
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    ta.style.top = '0';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length);
    document.execCommand('copy');
    document.body.removeChild(ta);
  } catch {
    // 最终兜底仍失败时静默——调用方的 toast 已给出成功提示，浏览器极端锁定场景无解。
  }
}

/* ---------------- 格式化 ---------------- */
const num2 = (v: unknown) => Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const money = (v: unknown) => (v === null || v === undefined || v === '') ? '<span class="mono">--</span>' : num2(v);
const last4 = (s: unknown) => String(s).slice(-4);
export const esc = (s: unknown): string =>
  String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const isEmpty = (v: unknown) => v === null || v === undefined || v === '';

/* 借贷双轨列「无发生额」的一侧数据值是 0，界面也渲染成「--」。0 在数学上是合法金额，
   但对这类列语义就是「无值」，若按 0 参与排序 / 区间筛选 / 值勾选会同时踩三处口径。
   所以按列声明 emptyZero，统一归入「空值」口径。 */
const isBlank = (col: GridColumn | undefined, v: unknown) => isEmpty(v) || (!!(col && col.emptyZero) && Number(v) === 0);

/* ---------------- 纯文本取值（TSV 复制 / 导出） ---------------- */
/** 单元格的纯文本形态。列声明了 text 就用它，否则回落到 row[k]（金额统一两位小数）。 */
export function rawCell(row: GridRow, col: GridColumn | undefined): string {
  if (!col) return '';
  if (col.text) return col.text(row);
  const v = row[col.k];
  if (isEmpty(v)) return '';
  if (col.type === 'money' || col.type === 'num') return Number(v).toFixed(2);
  return String(v);
}

/* ---------------- 比较 ---------------- */
function cmp(a: unknown, b: unknown, type: GridColumnType) {
  if (type === 'money' || type === 'num') return Number(a) - Number(b);
  return String(a).localeCompare(String(b), 'zh-CN');
}

/* ---------------- 网格工厂 ---------------- */
export function createGrid(opts: GridOptions): GridInstance | null {
  const { root } = opts;
  const el = (role: string) => root.querySelector<HTMLElement>(`[data-grid-role="${role}"]`);
  const host = el('host');
  const table = host?.querySelector('table');
  const thead = table?.querySelector('thead');
  const tbody = table?.querySelector('tbody');
  if (!host || !table || !thead || !tbody) return null;

  const toast = opts.toast || (() => {});
  const closePops = opts.onClosePops || (() => {});

  const st: GridState & {
    rowSel: Set<number>;
    range: { r1: number; c1: number; r2: number; c2: number } | null;
    active: { r: number; ri: number; ci: number } | null;
    find: string;
    findHits: { ri: number; ci: number }[];
    findIdx: number;
  } = {
    id: opts.id,
    cols: opts.cols.map((c) => ({ ...c })),
    rows: opts.rows.slice(),
    sort: [],
    filters: {},
    grouped: opts.groupedDefault !== false,
    density: opts.density || 'compact',
    frozen: opts.frozen || 0,
    views: (opts.views || []).map((v) => JSON.parse(JSON.stringify(v)) as GridView),
    view: null,
    rowSel: new Set<number>(),
    range: null,
    active: null,
    find: '',
    findHits: [],
    findIdx: -1,
  };

  /* 勾选列：余额页没有制证语义，整列不渲染（否则会多出一个永远用不上的复选框列）。 */
  /* W10：selectable 改为实时求值（原为 createGrid 时的布尔快照）。
     页面传入的 selectable 派生自权限（canAiVoucher），权限异步就绪时 React 会重渲染，
     但内核不重建 → 快照值永远是 false → 行首复选框列永久缺失，用户须 Ctrl+F5（权限已缓存、
     首帧即真）才能看见。改为函数后由 setSelectable() 触发重渲染即可补齐该列。 */
  const hasCheck = () => opts.selectable !== false;
  /* 「恢复默认」的基准：setCols 会随权限变化增删列（如跨公司主体列），必须同步，
     否则恢复默认会把新增列一并抹掉。 */
  let baseCols = opts.cols.map((c) => ({ ...c }));
  const visCols = () => st.cols.filter((c) => c.on);
  const ri = (r: GridRow) => st.rows.indexOf(r);
  let bodyOrder: number[] = [];
  const notifySelection = () => {
    if (opts.onSelectionChange) opts.onSelectionChange(st.rows.filter((_, i) => st.rowSel.has(i)));
  };

  /* ---------- 过滤 / 排序（作用域＝本页） ---------- */
  function filtered(): GridRow[] {
    // V36：filterServer 列的筛选已随请求参数在服务端生效，本地再过一遍会把服务端
    // 放行的行（如 CNY 展开 {CNY,10,01}）二次过滤掉，全量合计与本页行数对不上。
    const keys = Object.keys(st.filters).filter((k) => {
      const col = st.cols.find((c) => c.k === k);
      return !(col && col.filterServer);
    });
    if (!keys.length) return st.rows.slice();
    return st.rows.filter((r) => keys.every((k) => {
      const f = st.filters[k];
      const col = st.cols.find((c) => c.k === k);
      const v = r[k];
      if (f.kind === 'values') {
        const s = isBlank(col, v) ? '(空)' : String(v);
        return f.set.indexOf(s) >= 0;
      }
      if (f.kind === 'text') return String(isBlank(col, v) ? '' : v).toLowerCase().indexOf(String(f.q).toLowerCase()) >= 0;
      if (f.kind === 'num') {
        if (isBlank(col, v)) return false;
        const x = Number(v);
        if (f.min !== '' && f.min !== undefined && x < Number(f.min)) return false;
        if (f.max !== '' && f.max !== undefined && x > Number(f.max)) return false;
        return true;
      }
      if (f.kind === 'date') {
        const x = String(v || '');
        if (f.from && x.slice(0, 10) < f.from) return false;
        if (f.to && x.slice(0, 10) > f.to) return false;
        return true;
      }
      return true;
    }));
  }

  function sorted(rows: GridRow[]): GridRow[] {
    if (!st.sort.length) return rows.slice();
    // W16-B5：排序集合中存在 sortServer 列时，本地不再重排（服务端已按该列全量排序，
    // 本页行序就是服务端返回序；本地重排只会打乱当前页、得不到「全量排序」语义）。
    // 混合场景（server 列 + 本地列并存）保持谨慎：只要出现 server 列就整体放行服务端序，
    // 避免「半本地半服务端」产生没人能解释的顺序。
    if (st.sort.some((s) => st.cols.find((c) => c.k === s.k)?.sortServer)) return rows.slice();
    const out = rows.slice();
    out.sort((a, b) => {
      for (let i = 0; i < st.sort.length; i++) {
        const s = st.sort[i];
        const col = st.cols.find((c) => c.k === s.k);
        const av = a[s.k];
        const bv = b[s.k];
        const an = isBlank(col, av);
        const bn = isBlank(col, bv);
        if (an && bn) continue;
        if (an) return 1;    // 空值恒排最后，不随升降序翻转——不让「未采集」伪装成有值
        if (bn) return -1;
        const r = cmp(av, bv, col?.type || 'text');
        if (r !== 0) return r * s.dir;
      }
      return 0;
    });
    return out;
  }

  const shown = () => sorted(filtered());
  /* W10：勾选口径统一 —— 「本页可选行」= 当前展示行中通过 isRowSelectable 的行。
     禁选行（已转入标准流水 / 纯人工制证账户）不参与全选判定：旧口径用 shown().length 比较，
     页内存在禁选行时 rowSel.size 永远小于它，导致 ①表头全选框不显示勾选态
     ②第二次点击无法清空全选（用户反馈「点第二次无法清除」的真因）。 */
  const selectableShown = () => {
    const rows = shown();
    return opts.isRowSelectable ? rows.filter((r) => opts.isRowSelectable!(r)) : rows;
  };
  const allSelectableSelected = () => {
    const idx = selectableShown().map((r) => ri(r));
    return idx.length > 0 && idx.every((i) => st.rowSel.has(i));
  };

  /* ---------- 冻结偏移 ---------- */
  function frozenOffsets() {
    const cols = visCols();
    let left = hasCheck() ? 40 : 0;
    const map: Record<string, number> = {};
    cols.forEach((c, i) => { if (i < st.frozen) { map[c.k] = left; left += c.w; } });
    return map;
  }

  /* ---------- 渲染：表头 ---------- */
  function renderHead() {
    const cols = visCols();
    const off = frozenOffsets();
    const allSel = allSelectableSelected();
    let h = '<tr>';
    if (hasCheck()) {
      h += '<th class="col-check' + (st.frozen > 0 ? ' is-frozen' : '') + '"'
        + (st.frozen > 0 ? ' style="left:0"' : '') + ' title="全选 / 清空本页可选行；点行首方框勾选单行">'
        + '<span class="box' + (allSel ? ' on' : '') + '">' + (allSel ? '✓' : '') + '</span></th>';
    }
    cols.forEach((c, i) => {
      const s = st.sort.find((x) => x.k === c.k);
      const cls = ['th-sortable'];
      if (s) cls.push(s.dir === 1 ? 'sort-asc' : 'sort-desc');
      if (st.sort.length > 1 && s) cls.push('has-rank');   // 只给参与排序的列挂序号，否则全列表头都是空胶囊
      if (i < st.frozen) cls.push('is-frozen');
      if (c.align === 'num') cls.push('num');
      h += '<th class="' + cls.join(' ') + '" data-k="' + c.k + '" data-ci="' + i + '"'
        + ' style="width:' + c.w + 'px;min-width:' + c.w + 'px;' + (i < st.frozen ? 'left:' + off[c.k] + 'px' : '') + '"'
        + ' title="点击排序（Shift+点击多列）；拖动调整列序；仅本页排序">'
        + '<span class="th-inner"><span class="th-t">' + esc(c.t) + '</span>'
        + '<span class="sort-ind"><i></i><i></i></span>'
        + '<span class="sort-rank">' + (st.sort.length > 1 && s ? st.sort.indexOf(s) + 1 : '') + '</span>'
        + (c.filter ? '<button class="filter-btn' + (st.filters[c.k] ? ' is-on' : '') + '" data-filter="' + c.k + '" title="筛选「' + esc(c.t) + '」（' + (c.filterServer ? '全量' : '仅本页') + '）">'
          + '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M3 5h18M6 12h12M10 19h4"/></svg></button>' : '')
        + '</span><span class="col-resize" data-resize="' + c.k + '" title="拖动调宽 / 双击自适应"></span></th>';
    });
    h += '</tr>';
    thead!.innerHTML = h;
  }

  /* ---------- 渲染：表体 ---------- */
  function renderBody() {
    const cols = visCols();
    const off = frozenOffsets();
    const rows = shown();
    let h = '';

    if (!rows.length) {
      h += '<tr class="empty-row"><td colspan="' + (cols.length + (hasCheck() ? 1 : 0)) + '">'
        + '<div class="grid-empty">'
        + (Object.keys(st.filters).length
          ? '当前本页列头筛选没有命中任何行。已生效筛选见表格上方 chips，可逐个移除。'
          : (opts.emptyText || '本页没有数据。'))
        + '</div></td></tr>';
    }

    const blocks: { g: string | null; list: GridRow[] }[] = [];
    if (opts.groupBy && st.grouped) {
      const seen: string[] = [];
      const by: Record<string, GridRow[]> = {};
      rows.forEach((r) => {
        const g = String(r[opts.groupBy as string] || '(未分组)');
        if (!by[g]) { by[g] = []; seen.push(g); }
        by[g].push(r);
      });
      seen.forEach((g) => blocks.push({ g, list: by[g] }));
    } else {
      blocks.push({ g: null, list: rows });
    }

    /* 分组视图下排序必须先作用于组内，否则「组内有序、组间无序」看起来就是乱的。
       组顺序改用该列「合计」——与分组行上印出来的合计数同一口径，用户能对上。 */
    if (opts.groupBy && st.grouped && st.sort.length && blocks.length > 1 && blocks[0].g !== null) {
      const key = st.sort[0].k;
      const col = st.cols.find((c) => c.k === key);
      if (col && (col.type === 'money' || col.type === 'num')) {
        const dir = st.sort[0].dir;
        blocks.sort((a, b) => {
          const sa = a.list.reduce((x, r) => x + (Number(r[key]) || 0), 0);
          const sb = b.list.reduce((x, r) => x + (Number(r[key]) || 0), 0);
          return (sa - sb) * dir;
        });
      }
    }

    blocks.forEach((blk) => {
      if (blk.g !== null) {
        const meta = opts.groupMeta ? opts.groupMeta(blk.g, blk.list) : (blk.list.length + ' 行');
        h += '<tr class="group-row"><td colspan="' + (cols.length + (hasCheck() ? 1 : 0)) + '">' + esc(blk.g)
          + '<span class="gmeta">' + meta + '</span></td></tr>';
      }
      blk.list.forEach((r) => {
        const rowIdx = ri(r);
        const rr = bodyOrder.indexOf(rowIdx);
        const rsel = st.rowSel.has(rowIdx);
        const selectable = opts.isRowSelectable ? opts.isRowSelectable(r) : true;
        const trCls: string[] = [];
        if (rsel) trCls.push('row-sel');
        const extra = opts.rowClass ? opts.rowClass(r) : '';
        if (extra) trCls.push(extra);
        h += '<tr' + (trCls.length ? ' class="' + trCls.join(' ') + '"' : '') + ' data-ri="' + rowIdx + '">';
        if (hasCheck()) {
          h += '<td class="col-check' + (rsel ? ' cell-rowsel' : '') + (selectable ? '' : ' is-disabled') + (st.frozen > 0 ? ' is-frozen' : '') + '"'
            + (st.frozen > 0 ? ' style="left:0"' : '') + '><span class="box' + (rsel ? ' on' : '') + '"'
            + (selectable ? '' : ' data-rowsel-disabled="1"') + '>' + (rsel ? '✓' : '') + '</span></td>';
        }
        cols.forEach((c, ci) => {
          let sel = false;
          if (st.range) {
            const r1 = Math.min(st.range.r1, st.range.r2), r2 = Math.max(st.range.r1, st.range.r2);
            const c1 = Math.min(st.range.c1, st.range.c2), c2 = Math.max(st.range.c1, st.range.c2);
            sel = rr >= r1 && rr <= r2 && ci >= c1 && ci <= c2;
          }
          const act = !!st.active && st.active.ri === rowIdx && st.active.ci === ci;
          const hit = !!st.find && String(isEmpty(r[c.k]) ? '' : r[c.k]).toLowerCase().indexOf(st.find) >= 0;
          const hitActive = st.findIdx >= 0 && !!st.findHits[st.findIdx]
            && st.findHits[st.findIdx].ri === rowIdx && st.findHits[st.findIdx].ci === ci;
          const cls: string[] = [];
          if (c.align === 'num') cls.push('num');
          const cfc = c.cf ? c.cf(r) : '';
          if (cfc) cls.push(cfc);
          if (sel) cls.push('cell-sel');
          if (act) cls.push('cell-active');
          if (hit) cls.push('cell-hit');
          if (hitActive) cls.push('cell-hit-active');
          if (ci < st.frozen) cls.push('is-frozen');
          h += '<td class="' + cls.join(' ') + '" data-ri="' + rowIdx + '" data-ci="' + ci + '" data-k="' + c.k + '"'
            + (ci < st.frozen ? ' style="left:' + off[c.k] + 'px"' : '') + '>' + c.cell(r) + '</td>';
        });
        h += '</tr>';
      });
    });
    tbody!.innerHTML = h;
  }

  /* ---------- 筛选 chips ---------- */
  function chipText(k: string, f: GridFilter) {
    const col = st.cols.find((c) => c.k === k);
    const title = (col && col.filterServer ? '【全量】' : '') + (col ? col.t : k);
    if (f.kind === 'values') return title + ' ∈ ' + f.set.join(' / ');
    if (f.kind === 'text') return title + ' 包含「' + f.q + '」';
    if (f.kind === 'num') return title + ' ' + (f.min === '' || f.min === undefined ? '不限' : f.min) + ' ~ ' + (f.max === '' || f.max === undefined ? '不限' : f.max);
    return title + ' ' + (f.from || '不限') + ' ~ ' + (f.to || '不限');
  }
  const chipsEl = el('chips');
  function renderChips() {
    if (!chipsEl) return;
    const keys = Object.keys(st.filters);
    const hasServer = keys.some((k) => st.cols.find((c) => c.k === k)?.filterServer);
    const hasLocal = keys.length > keys.filter((k) => st.cols.find((c) => c.k === k)?.filterServer).length;
    const label = hasServer && hasLocal ? '筛选生效（【全量】＝服务端口径，其余仅本页）：'
      : hasServer ? '全量生效（服务端口径，翻页 / 导出同口径）：' : '仅本页生效：';
    chipsEl.classList.toggle('is-on', keys.length > 0);
    chipsEl.innerHTML = !keys.length ? ''
      : '<span class="fchip-none">' + label + '</span>'
      + keys.map((k) => '<span class="fchip" data-chip="' + k + '">' + esc(chipText(k, st.filters[k]))
        + '<button data-unfilter="' + k + '" title="移除此筛选">✕</button></span>').join('')
      + '<button class="btn btn-sm" data-unfilter-all>全部清除</button>';
  }

  /* ---------- 状态栏 ---------- */
  function aggregate() {
    const out = { cells: 0, rows: 0, num: 0, sum: 0, avg: 0 };
    const cols = visCols();
    const order = shown().map((r) => ri(r));
    const set = new Set<number>();
    if (st.range) {
      const r1 = Math.min(st.range.r1, st.range.r2), r2 = Math.max(st.range.r1, st.range.r2);
      const c1 = Math.min(st.range.c1, st.range.c2), c2 = Math.max(st.range.c1, st.range.c2);
      for (let r = r1; r <= r2; r++) {
        const rowIdx = order[r];
        if (rowIdx === undefined) continue;
        set.add(rowIdx);
        for (let c = c1; c <= c2; c++) {
          const col = cols[c];
          if (!col) continue;
          out.cells++;
          if (col.type === 'money' || col.type === 'num') {
            const v = st.rows[rowIdx][col.k];
            if (!isEmpty(v)) { out.num++; out.sum += Number(v); }
          }
        }
      }
    } else if (st.rowSel.size) {
      st.rowSel.forEach((rowIdx) => {
        set.add(rowIdx);
        out.cells += cols.length;
        cols.forEach((col) => {
          if (col.type !== 'money' && col.type !== 'num') return;
          const v = st.rows[rowIdx][col.k];
          if (!isEmpty(v)) { out.num++; out.sum += Number(v); }
        });
      });
    }
    out.rows = set.size;
    out.avg = out.num ? out.sum / out.num : 0;
    return out;
  }

  const statusEl = el('status');
  function renderStatus() {
    if (!statusEl) return;
    const rows = shown();
    const agg = aggregate();
    let h = '';
    h += '<span class="gs-item">本页显示 <b>' + rows.length + '</b> / ' + st.rows.length + ' 行</span>';
    // W8（2026-09-20）：移除「本页可见小计」——财务要的是全量命中数据的合计（工具栏全量合计），
    // 当前页的金额小计容易被误当成全量数。
    if (agg.cells > 0) {
      h += '<span class="gs-sep"></span><span class="gs-item">已选 <b>' + agg.cells + '</b> 单元格 / <b>' + agg.rows + '</b> 行</span>';
      if (agg.num) {
        h += '<span class="gs-sep"></span><span class="gs-item">求和 <b>¥ ' + num2(agg.sum) + '</b></span>';
        h += '<span class="gs-sep"></span><span class="gs-item">平均 <b>¥ ' + num2(agg.avg) + '</b></span>';
        h += '<span class="gs-sep"></span><span class="gs-item">数字单元格 <b>' + agg.num + '</b></span>';
      }
      h += '<button class="btn btn-sm" data-copy-sel>复制选区（TSV）</button>';
      h += '<button class="btn btn-sm" data-export-sel>仅导出选中 ' + agg.rows + ' 行</button>';
    }
    h += '<span class="gs-scope">口径：本页排序 / 选区汇总只作用于本页 '
      + st.rows.length + ' 行；列头筛选按标注生效（【全量】＝服务端全量、翻页导出同口径，其余＝仅本页）。</span>';
    statusEl.innerHTML = h;
  }

  /* ---------- 复制 TSV ---------- */
  function raw(r: GridRow, col: GridColumn | undefined) {
    return rawCell(r, col);
  }
  function copyTSV() {
    const cols = visCols();
    let tsv = '';
    if (st.range) {
      const r1 = Math.min(st.range.r1, st.range.r2), r2 = Math.max(st.range.r1, st.range.r2);
      const c1 = Math.min(st.range.c1, st.range.c2), c2 = Math.max(st.range.c1, st.range.c2);
      const order = shown().map((r) => ri(r));
      for (let r = r1; r <= r2; r++) {
        const rowIdx = order[r];
        if (rowIdx === undefined) continue;
        const cells: string[] = [];
        for (let c = c1; c <= c2; c++) cells.push(raw(st.rows[rowIdx], cols[c]));
        tsv += cells.join('\t') + '\r\n';
      }
    } else if (st.rowSel.size) {
      tsv = cols.map((c) => c.t).join('\t') + '\r\n';
      st.rows.forEach((r, i) => { if (st.rowSel.has(i)) tsv += cols.map((c) => raw(r, c)).join('\t') + '\r\n'; });
    }
    copyText(tsv);
    const lines = tsv ? tsv.trim().split('\r\n').length : 0;
    if (lines) toast('已复制 ' + lines + ' 行 × ' + (tsv.split('\r\n')[0].split('\t').length) + ' 列（TSV），可直接粘贴进 Excel');
    return tsv;
  }

  /* ---------- 列设置浮层 ---------- */
  const colPanelEl = el('col-panel');
  function renderColPanel() {
    if (!colPanelEl) return;
    let h = '<div class="pop-head"><div><h3>列 · 顺序 · 冻结</h3>'
      + '<div class="sub">拖动 ⋮⋮ 调列序，勾选即生效；偏好随账号保存（服务端，跨设备一致）</div></div>'
      + '<button class="btn btn-sm" data-col-reset>恢复默认</button></div>';
    h += '<div class="pop-body">';
    h += '<div class="hint" style="padding:6px 8px 10px">冻结前 <span class="seg">'
      + [0, 1, 2].map((n) => '<button data-freeze="' + n + '"' + (st.frozen === n ? ' class="is-on"' : '') + '>' + n + '</button>').join('')
      + '</span> 列<span class="hint" style="margin-left:8px">冻结列横向滚动时保持可见</span></div>';
    st.cols.forEach((c) => {
      h += '<div class="colrow" data-colrow="' + c.k + '">'
        + '<span class="drag-handle" data-coldrag="' + c.k + '" title="拖动调整列序">⋮⋮</span>'
        + '<span class="box' + (c.on ? ' on' : '') + '" data-coltoggle="' + c.k + '">' + (c.on ? '✓' : '') + '</span>'
        + esc(c.t)
        + (c.req ? '<span class="badge-default">必需，不可关闭</span>' : (c.def ? '<span class="badge-default">' + c.def + '</span>' : ''))
        + '<span style="margin-left:auto;display:flex;gap:3px">'
        + '<button class="btn btn-sm" data-colup="' + c.k + '" title="上移">↑</button>'
        + '<button class="btn btn-sm" data-coldown="' + c.k + '" title="下移">↓</button>'
        + '<button class="btn btn-sm" data-colauto="' + c.k + '" title="按内容自适应列宽">⇔</button>'
        + '</span></div>';
    });
    h += '</div>';
    h += '<div class="pop-foot"><span class="hint">列宽 / 列序 / 冻结随账号保存，换电脑登录同一个账号也一样</span>'
      + '<button class="btn btn-primary btn-sm" data-pop-close>完成</button></div>';
    colPanelEl.innerHTML = h;
  }

  /* ---------- 视图 ---------- */
  const viewsEl = el('views');
  function snap(): GridSnapshot {
    return {
      on: visCols().map((c) => c.k),
      order: st.cols.map((c) => c.k),
      w: st.cols.map((c) => ({ k: c.k, w: c.w })),
      sort: st.sort.slice(),
      filters: JSON.parse(JSON.stringify(st.filters)) as Record<string, GridFilter>,
      density: st.density,
      frozen: st.frozen,
      view: st.view,
      views: JSON.parse(JSON.stringify(st.views)) as GridView[],
    };
  }
  function applyState(v: Partial<GridView> & Partial<GridSnapshot>) {
    if (v.order && v.order.length) st.cols.sort((a, b) => v.order!.indexOf(a.k) - v.order!.indexOf(b.k));
    if (v.on) st.cols.forEach((c) => { c.on = c.req ? true : v.on!.indexOf(c.k) >= 0; });
    (v.w || []).forEach((x) => { const c = st.cols.find((y) => y.k === x.k); if (c) c.w = x.w; });
    if (v.sort) st.sort = v.sort.slice();
    if (v.filters) st.filters = JSON.parse(JSON.stringify(v.filters)) as Record<string, GridFilter>;
    st.density = v.density || 'compact';
    st.frozen = v.frozen || 0;
  }
  function applyView(v: GridView) {
    applyState(v);
    st.view = v.name;
    renderAll();
    toast('已应用视图「' + v.name + '」：' + visCols().length + ' 列 · ' + (st.sort.length ? st.sort.length + ' 列排序' : '默认序'));
  }
  function renderViews() {
    if (!viewsEl) return;
    let h = '<div class="pop-head"><div><h3>我的视图</h3>'
      + '<div class="sub">列 + 列序 + 列宽 + 排序 + 本页筛选 + 密度 的组合，随账号保存</div></div></div>';
    h += '<div class="pop-body">';
    if (!st.views.length) h += '<div class="hint" style="padding:8px">还没有保存的视图。调好列与排序后点下方按钮保存。</div>';
    st.views.forEach((v, i) => {
      h += '<div class="viewrow' + (st.view === v.name ? ' is-on' : '') + '" data-view="' + i + '">'
        + '<span><span class="vn">' + esc(v.name) + '</span><span class="vd"> · ' + esc(v.desc) + '</span></span>'
        + '<span class="va"><button data-view-del="' + i + '">删除</button></span></div>';
    });
    h += '</div>';
    h += '<div class="pop-foot"><button class="btn btn-sm" data-view-save>保存当前为视图</button>'
      + '<button class="btn btn-primary btn-sm" data-pop-close>完成</button></div>';
    viewsEl.innerHTML = h;
  }

  /* ---------- 自适应列宽 ---------- */
  function autoFit(k: string) {
    const col = st.cols.find((c) => c.k === k);
    if (!col) return;
    const idx = visCols().indexOf(col);
    let max = col.t.length * 13 + 46;
    tbody!.querySelectorAll<HTMLTableRowElement>('tr[data-ri]').forEach((tr) => {
      const td = tr.querySelectorAll('td')[idx + 1];
      if (td) max = Math.max(max, (td as HTMLElement).scrollWidth + 26);
    });
    col.w = Math.min(Math.max(Math.round(max), 74), 420);
    renderAll();
  }

  /* ---------- 全量渲染 ---------- */
  let notifySnapshot = false;
  /* 筛选集合的通知走 renderAll 的 diff：apply / 清除 / chips 移除 / 视图与偏好恢复
     全部经 renderAll 落地，在这里统一拦截不会漏。初值与首渲染对齐 → 挂载不回调。 */
  let lastFiltersJson = JSON.stringify(st.filters);
  function renderAll() {
    bodyOrder = shown().map((r) => ri(r));
    renderHead();
    renderBody();
    renderChips();
    renderStatus();
    renderColPanel();
    renderViews();
    table!.classList.toggle('is-dense', st.density === 'compact');
    table!.classList.toggle('is-grouped', !!(opts.groupBy && st.grouped));
    const sw = el('group-switch');
    if (sw) sw.classList.toggle('is-on', st.grouped);
    const ds = el('density-seg');
    if (ds) ds.querySelectorAll('button').forEach((b, i) => b.classList.toggle('is-on', i === (st.density === 'compact' ? 0 : 1)));
    const fl = el('freeze-label');
    if (fl) fl.textContent = st.frozen ? '冻结 ' + st.frozen + ' 列' : '未冻结';
    const vl = el('view-label');
    if (vl) vl.textContent = st.view || '默认视图';
    renderExportLabel();
    const filtersJson = JSON.stringify(st.filters);
    if (filtersJson !== lastFiltersJson) {
      lastFiltersJson = filtersJson;
      if (opts.onFilterChange) opts.onFilterChange(JSON.parse(filtersJson) as Record<string, GridFilter>);
    }
    // W16-B5：排序变化通知（仅当存在 sortServer 列时才有意义，但无论有无都回报 ——
    // 页面自己决定是否消费；避免「先点了 server 列再切回本地列」时页面残留旧参数）。
    const sortJson = JSON.stringify(st.sort);
    if (sortJson !== lastSortJson) {
      lastSortJson = sortJson;
      if (opts.onSortChange) opts.onSortChange(JSON.parse(sortJson) as GridSortSpec[]);
    }
    if (notifySnapshot && opts.onSnapshotChange) opts.onSnapshotChange(snap());
  }

  /* ---------- 排序 ---------- */
  function toggleSort(k: string, additive: boolean) {
    const cur = st.sort.find((s) => s.k === k);
    if (!additive) {
      // 与 Excel 一致的三态循环：升 → 降 → 取消
      if (cur && cur.dir === 1) st.sort = [{ k, dir: -1 }];
      else if (cur && cur.dir === -1) st.sort = [];
      else st.sort = [{ k, dir: 1 }];
    } else if (cur) {
      if (cur.dir === 1) cur.dir = -1;
      else st.sort = st.sort.filter((s) => s.k !== k);
    } else st.sort.push({ k, dir: 1 });
    renderAll();
  }

  /* W16-B5：排序变化通知 —— 与筛选通知（lastFiltersJson diff）同一套手法：
     renderAll 统一出口拦截，值没变不回调；挂载首渲染初值对齐 → 不触发。
     视图应用 / 偏好恢复 / 表头点击全部经 renderAll 落地，在这拦不会漏。 */
  let lastSortJson = JSON.stringify(st.sort);

  /* ---------- 筛选浮层 ---------- */
  const filterHost = el('filter-pop');
  function openFilter(k: string, trigger: HTMLElement | null) {
    if (!filterHost) return;
    const col = st.cols.find((c) => c.k === k);
    if (!col) return;
    const cur = st.filters[k];
    const distinct: string[] = [];
    st.rows.forEach((r) => {
      const s = isBlank(col, r[k]) ? '(空)' : String(r[k]);
      if (distinct.indexOf(s) < 0) distinct.push(s);
    });
    distinct.sort();
    let kinds: GridFilterKind[];
    if (col.filter === 'value') kinds = ['values'];
    else if (col.filter === 'text') kinds = ['values', 'text'];
    else if (col.filter === 'num') kinds = ['num'];
    else if (col.filter === 'date') kinds = ['date'];
    else kinds = [];
    let kind: GridFilterKind = (cur && cur.kind) || kinds[0];

    const serverScoped = !!col.filterServer;
    let h = '<div class="pop-head"><div><h3>筛选「' + esc(col.t) + '」</h3>'
      + '<div class="sub">' + (serverScoped
        ? '服务端全量口径：作用于全部数据，翻页 / 导出同口径'
        : '仅作用于本页 ' + st.rows.length + ' 行（服务端分页口径）') + '</div></div>'
      + '<button class="btn btn-sm" data-fclear>清除</button></div>';
    h += '<div class="fp-body">';
    if (kinds.length > 1) {
      h += '<div class="fp-kind">' + kinds.map((x) => '<button data-fkind="' + x + '"' + (x === kind ? ' class="is-on"' : '') + '>'
        + ({ values: '按值勾选', text: '文本包含', num: '数值区间', date: '日期区间' } as Record<string, string>)[x] + '</button>').join('') + '</div>';
    }
    h += '<div class="fp-stage"></div></div>';
    h += '<div class="pop-foot"><span class="hint">Esc 关闭</span><button class="btn btn-primary btn-sm" data-fapply>应用</button></div>';
    filterHost.innerHTML = h;
    // 列头会随排序/筛选重排，所以筛选浮层用 fixed 定位，按触发器实时算位置
    if (trigger) {
      const r = trigger.getBoundingClientRect();
      const w = filterHost.offsetWidth || 268;
      filterHost.style.left = Math.max(8, Math.min(r.right - w, window.innerWidth - w - 12)) + 'px';
      filterHost.style.top = Math.min(r.bottom + 6, window.innerHeight - 320) + 'px';
    }
    function renderStage() {
      const stage = filterHost!.querySelector('.fp-stage');
      if (!stage) return;
      if (kind === 'values') {
        const set = (cur && cur.kind === 'values' && cur.set) || null;
        stage.innerHTML = distinct.map((v) => {
          const on = !set || set.indexOf(v) >= 0;
          return '<div class="fp-val" data-fval="' + esc(v) + '"><span class="box' + (on ? ' on' : '') + '">' + (on ? '✓' : '') + '</span>' + esc(v) + '</div>';
        }).join('');
        stage.querySelectorAll('.fp-val').forEach((node) => {
          node.addEventListener('click', () => {
            const box = node.querySelector('.box');
            if (!box) return;
            box.classList.toggle('on');
            box.textContent = box.classList.contains('on') ? '✓' : '';
          });
        });
      } else if (kind === 'text') {
        stage.innerHTML = '<div class="fp-text"><input type="text" placeholder="'
          + esc(col?.filterPlaceholder || '包含文本，如 货款') + '" value="'
          + esc(cur && cur.kind === 'text' ? cur.q : '') + '"></div>';
      } else if (kind === 'num') {
        stage.innerHTML = '<div class="fp-range"><input type="number" placeholder="最小值" value="'
          + esc(cur && cur.kind === 'num' ? cur.min : '') + '"><span>~</span><input type="number" placeholder="最大值" value="'
          + esc(cur && cur.kind === 'num' ? cur.max : '') + '"></div>';
      } else {
        stage.innerHTML = '<div class="fp-range"><input type="date" value="'
          + esc(cur && cur.kind === 'date' ? cur.from : '') + '"><span>~</span><input type="date" value="'
          + esc(cur && cur.kind === 'date' ? cur.to : '') + '"></div>';
      }
    }
    renderStage();

    filterHost.querySelectorAll<HTMLElement>('[data-fkind]').forEach((b) => {
      b.addEventListener('click', () => {
        kind = b.dataset.fkind as Exclude<GridFilterKind, null>;
        filterHost!.querySelectorAll('[data-fkind]').forEach((x) => x.classList.toggle('is-on', x === b));
        renderStage();
      });
    });
    filterHost.querySelector('[data-fclear]')?.addEventListener('click', () => {
      delete st.filters[k];
      closePops();
      renderAll();
      toast('已清除「' + col.t + '」的本页筛选');
    });
    filterHost.querySelector('[data-fapply]')?.addEventListener('click', () => {
      const stage = filterHost.querySelector('.fp-stage');
      if (!stage) return;
      if (kind === 'values') {
        const set = Array.from(stage.querySelectorAll<HTMLElement>('.fp-val'))
          .filter((node) => node.querySelector('.box')?.classList.contains('on')).map((node) => node.dataset.fval || '');
        if (set.length === distinct.length) delete st.filters[k];
        else st.filters[k] = { kind: 'values', set };
      } else if (kind === 'text') {
        const input = stage.querySelector('input');
        const q = (input?.value || '').trim();
        if (!q) delete st.filters[k]; else st.filters[k] = { kind: 'text', q };
      } else if (kind === 'num') {
        const ins = stage.querySelectorAll('input');
        if (ins[0].value === '' && ins[1].value === '') delete st.filters[k];
        else st.filters[k] = { kind: 'num', min: ins[0].value, max: ins[1].value };
      } else {
        const ins = stage.querySelectorAll('input');
        if (!ins[0].value && !ins[1].value) delete st.filters[k];
        else st.filters[k] = { kind: 'date', from: ins[0].value, to: ins[1].value };
      }
      closePops();
      renderAll();
      toast('已应用本页筛选 → 本页 ' + shown().length + ' 行' + (shown().length ? '' : '（无命中）'));
    });
  }

  /* ---------- 表头事件 ---------- */
  let resized = false;
  const onHeadMouseDown = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    const handle = target.closest<HTMLElement>('[data-resize]');
    if (!handle) return;
    e.preventDefault();
    resized = true;
    const col = st.cols.find((c) => c.k === handle.dataset.resize);
    if (!col) return;
    const x0 = e.clientX;
    const w0 = col.w;
    setSelecting(true);
    const move = (ev: MouseEvent) => { col.w = Math.max(74, Math.round(w0 + (ev.clientX - x0))); renderAll(); };
    const up = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      setSelecting(false);
      setTimeout(() => { resized = false; }, 0);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
  };
  const onHeadDblClick = (e: MouseEvent) => {
    const handle = (e.target as HTMLElement).closest<HTMLElement>('[data-resize]');
    if (handle?.dataset.resize) { autoFit(handle.dataset.resize); toast('已按内容自适应列宽'); }
  };
  const onHeadClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    if (resized || colDragMoved || target.closest('[data-resize]')) return;
    const fb = target.closest<HTMLElement>('[data-filter]');
    if (fb) {
      // 必须 stopPropagation：document 级的「点外部关浮层」在冒泡末端，否则刚开的浮层会被立刻关掉
      e.stopPropagation();
      const k = fb.dataset.filter as string;
      const wasOn = !!filterHost && filterHost.classList.contains('is-on') && filterHost.dataset.k === k;
      closePops();
      if (!wasOn && filterHost) {
        openFilter(k, fb);
        filterHost.dataset.k = k;
        filterHost.classList.add('is-on');
      }
      return;
    }
    if (target.closest('.col-check')) {
      // W10：toggle 判定改用「可选行」口径（旧 size===shown().length 在含禁选行的页永不成立，
      // 导致第二次点击无法清空）。全选也只选可选行。
      if (allSelectableSelected()) st.rowSel.clear();
      else st.rowSel = new Set(selectableShown().map((r) => ri(r)));
      st.range = null;
      renderAll();
      notifySelection();
      return;
    }
    const th = target.closest<HTMLElement>('th[data-k]');
    if (!th?.dataset.k) return;
    toggleSort(th.dataset.k, e.shiftKey);
  };
  thead.addEventListener('mousedown', onHeadMouseDown);
  thead.addEventListener('dblclick', onHeadDblClick);
  thead.addEventListener('click', onHeadClick);

  /* ---------- 表头拖拽列序（W16-B1） ----------
     与表头已有三种交互共存的手法：mousedown 挂起，水平位移超过阈值（6px）才升级为列拖拽，
     未移动原样松开仍走 click 排序 —— 与列宽拖拽用 resized 抑制 click 同一套约定。
     约束：必需列（req）可拖动但不可拖入冻结区；冻结区（前 st.frozen 列）整体有序，
     普通列不可拖入冻结区、冻结列不可拖出，避免 sticky 偏移表错位。 */
  const COL_DRAG_THRESHOLD = 6;
  let colDrag: { k: string; x0: number; armed: boolean } | null = null;
  let colDragMoved = false;
  const colIsFrozen = (k: string) => {
    const vis = visCols();
    const i = vis.findIndex((c) => c.k === k);
    return i >= 0 && i < st.frozen;
  };
  const onHeadColDragDown = (e: MouseEvent) => {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement;
    if (target.closest('[data-resize]') || target.closest('[data-filter]')) return;
    const th = target.closest<HTMLElement>('th[data-k]');
    if (!th?.dataset.k) return;
    e.preventDefault();   // 阻止表头文字原生选取（拖拽期间视觉更干净；不影响 click 排序）
    colDrag = { k: th.dataset.k, x0: e.clientX, armed: false };
    colDragMoved = false;
  };
  const onDocColDragMove = (e: MouseEvent) => {
    if (!colDrag) return;
    if (!colDrag.armed) {
      if (Math.abs(e.clientX - colDrag.x0) < COL_DRAG_THRESHOLD) return;
      colDrag.armed = true;
      colDragMoved = true;
      document.body.style.cursor = 'grabbing';
      setSelecting(true);
      toast('松开鼠标完成列移动（列设置里也可微调）');
    }
  };
  const onDocColDragUp = (e: MouseEvent) => {
    if (!colDrag) return;
    const drag = colDrag;
    colDrag = null;
    document.body.style.cursor = '';
    if (!drag.armed) return;   // 未升级为拖拽 → click 正常走排序
    setSelecting(false);
    const th = (e.target as HTMLElement).closest<HTMLElement>('th[data-k]');
    const targetKey = th?.dataset.k;
    if (!targetKey || targetKey === drag.k) return;
    // 冻结归属判定必须在 splice 之前 —— 移除后 findIndex 拿不到该列，判定会失效
    const dragFrozen = colIsFrozen(drag.k);
    const targetFrozen = colIsFrozen(targetKey);
    const from = st.cols.findIndex((c) => c.k === drag.k);
    if (from < 0) return;
    const to = st.cols.findIndex((c) => c.k === targetKey);
    if (to < 0) return;   // 目标列已被藏（理论上不可能：th 来自当前可见表头）
    if (dragFrozen !== targetFrozen) {
      toast(dragFrozen ? '冻结列不可拖出冻结区（可在列设置里调整冻结数）' : '普通列不可拖入冻结区');
      return;
    }
    const moved = st.cols.splice(from, 1)[0];
    // 松手位置在目标列左半 → 插到目标前；右半 → 插到目标后（to 是移除后的下标，直接可用）
    const box = th!.getBoundingClientRect();
    const insertAt = e.clientX >= box.left + box.width / 2 ? to + 1 : to;
    st.cols.splice(insertAt, 0, moved);
    renderAll();
    notifySnapshot = true;
    renderAll();
    toast('已调整列序');
  };
  thead.addEventListener('mousedown', onHeadColDragDown);
  document.addEventListener('mousemove', onDocColDragMove);
  document.addEventListener('mouseup', onDocColDragUp);

  /* ---------- 选区 ---------- */
  /* 单元格内可能放交互控件（复制标签、行内按钮）。若在 mousedown 就重渲染，
     被点元素会被换掉 → click 事件根本不派发，控件静默失效。必须先在 mousedown 放行。 */
  const NO_SELECT = 'button, a, input, select, textarea, label, .btn, .copy-chip, [data-row-action]';
  let dragging = false;
  /* V36：拖选 / 拖列宽生命周期内给根容器 toggle .xgrid-selecting（CSS 禁文字选取），
     再用 selectstart 兜底拦截 —— 之前数据区拖动选区会同时触发浏览器文字选取变蓝。 */
  const setSelecting = (on: boolean) => root.classList.toggle('xgrid-selecting', on);
  const onDocSelectStart = (e: Event) => { if (dragging) e.preventDefault(); };
  document.addEventListener('selectstart', onDocSelectStart);
  /* W10：单击勾选 / 拖动选区。
     用户诉求「点行内任意单元格即勾选该行」与原有「单击=选区起点、拖动=框选复制」冲突。
     方案：mousedown 挂起 pendingClick 并照旧建立单格选区（保留点击反馈），期间若发生移动
     则升级为拖动选区（moved=true，不再触发勾选）；未移动则在 mouseup 时 toggle 该行勾选。
     拖动不再清空已勾选行 —— 避免「只想复制一段文本，勾选却被清掉」。 */
  let pendingClick: { ri: number; moved: boolean } | null = null;
  const onBodyMouseDown = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    if (target.closest(NO_SELECT)) return;
    const td = target.closest<HTMLElement>('td[data-ri]');
    if (!td || target.closest('.col-check')) return;
    const ci = Number(td.dataset.ci);
    const rowIdx = Number(td.dataset.ri);
    const rr = bodyOrder.indexOf(rowIdx);
    if (e.shiftKey && st.active) {
      st.range = { r1: st.active.r, c1: st.active.ci, r2: rr, c2: ci };
      pendingClick = null;
    } else {
      st.active = { r: rr, ri: rowIdx, ci };
      st.range = { r1: rr, c1: ci, r2: rr, c2: ci };
      dragging = true;
      pendingClick = { ri: rowIdx, moved: false };
    }
    setSelecting(true);
    renderAll();
  };
  const onBodyMouseMove = (e: MouseEvent) => {
    if (!dragging) return;
    const td = (e.target as HTMLElement).closest<HTMLElement>('td[data-ri]');
    if (!td || !st.range) return;
    const rr = bodyOrder.indexOf(Number(td.dataset.ri));
    const ci = Number(td.dataset.ci);
    if (st.range.r2 === rr && st.range.c2 === ci) return;
    st.range.r2 = rr;
    st.range.c2 = ci;
    if (pendingClick) pendingClick.moved = true;
    renderAll();
  };
  const onBodyClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    const copy = target.closest<HTMLElement>('[data-copy]');
    if (copy?.dataset.copy) {
      copyText(copy.dataset.copy);
      toast('已复制：' + copy.dataset.copy);
      return;
    }
    const action = target.closest<HTMLElement>('[data-row-action]');
    if (action) {
      const tr = action.closest<HTMLTableRowElement>('tr[data-ri]');
      const rowIdx = tr ? Number(tr.dataset.ri) : -1;
      if (rowIdx >= 0 && opts.onRowAction) opts.onRowAction(action.dataset.rowAction as string, st.rows[rowIdx]);
      return;
    }
    const box = target.closest<HTMLElement>('.col-check .box');
    if (!box) return;
    if (box.dataset.rowselDisabled) {
      const tr0 = box.closest<HTMLTableRowElement>('tr[data-ri]');
      const r0 = tr0 ? st.rows[Number(tr0.dataset.ri)] : undefined;
      const hint = opts.disabledRowHint;
      toast(typeof hint === 'function' ? hint((r0 || {}) as GridRow) : (hint || '该行不可勾选'));
      return;
    }
    const tr = box.closest<HTMLTableRowElement>('tr[data-ri]');
    if (!tr) return;
    const rowIdx = Number(tr.dataset.ri);
    if (st.rowSel.has(rowIdx)) st.rowSel.delete(rowIdx); else st.rowSel.add(rowIdx);
    st.range = null;
    renderAll();
    notifySelection();
  };
  const onDocMouseUp = () => {
    const pending = pendingClick;
    pendingClick = null;
    dragging = false;
    setSelecting(false);
    if (!pending || pending.moved) return;   // 拖动结束 → 只保留选区，不勾选
    // W10：单击（未拖动）→ 勾选/取消勾选该行
    const row = st.rows[pending.ri];
    const selectable = !opts.isRowSelectable || opts.isRowSelectable(row);
    if (!selectable) {
      const hint = opts.disabledRowHint;
      toast(typeof hint === 'function' ? hint(row) : (hint || '该行不可勾选'));
      st.range = null;
      renderAll();
      return;
    }
    if (st.rowSel.has(pending.ri)) st.rowSel.delete(pending.ri); else st.rowSel.add(pending.ri);
    st.range = null;   // 单击语义是「勾选该行」，收起单格选区高亮
    renderAll();
    notifySelection();
  };
  tbody.addEventListener('mousedown', onBodyMouseDown);
  tbody.addEventListener('mousemove', onBodyMouseMove);
  tbody.addEventListener('click', onBodyClick);
  document.addEventListener('mouseup', onDocMouseUp);

  /* ---------- 列设置事件（含拖拽列序） ---------- */
  let dragKey: string | null = null;
  const onColDragStart = (e: DragEvent) => {
    const row = (e.target as HTMLElement).closest<HTMLElement>('.colrow');
    if (!row) return;
    dragKey = row.dataset.colrow || null;
    row.classList.add('is-dragging');
  };
  const onColDragOver = (e: DragEvent) => {
    const row = (e.target as HTMLElement).closest<HTMLElement>('.colrow');
    if (!row || !dragKey) return;
    e.preventDefault();
    colPanelEl!.querySelectorAll('.colrow').forEach((r) => r.classList.remove('drop-before', 'drop-after'));
    const box = row.getBoundingClientRect();
    row.classList.add(e.clientY < box.top + box.height / 2 ? 'drop-before' : 'drop-after');
  };
  const onColDrop = (e: DragEvent) => {
    const row = (e.target as HTMLElement).closest<HTMLElement>('.colrow');
    if (!row || !dragKey) return;
    e.preventDefault();
    const targetKey = row.dataset.colrow;
    const before = row.classList.contains('drop-before');
    if (targetKey === dragKey) { dragKey = null; return; }
    const from = st.cols.findIndex((c) => c.k === dragKey);
    const moved = st.cols.splice(from, 1)[0];
    let to = st.cols.findIndex((c) => c.k === targetKey);
    if (!before) to += 1;
    st.cols.splice(to, 0, moved);
    dragKey = null;
    renderAll();
    toast('已调整列序');
  };
  const onColDragEnd = () => {
    dragKey = null;
    colPanelEl?.querySelectorAll('.colrow').forEach((r) => r.classList.remove('is-dragging', 'drop-before', 'drop-after'));
  };
  const onColPanelClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    const t = target.closest<HTMLElement>('[data-coltoggle]');
    if (t) {
      const c = st.cols.find((x) => x.k === t.dataset.coltoggle);
      if (!c) return;
      if (c.req && c.on) { toast('「' + c.t + '」是必需列：藏起来会让未采集账户看起来像正常数据'); return; }
      c.on = !c.on;
      if (!st.cols.some((x) => x.on)) { c.on = true; toast('至少要保留 1 列'); }
      renderAll();
      return;
    }
    const up = target.closest<HTMLElement>('[data-colup]');
    if (up?.dataset.colup) { moveCol(up.dataset.colup, -1); return; }
    const dn = target.closest<HTMLElement>('[data-coldown]');
    if (dn?.dataset.coldown) { moveCol(dn.dataset.coldown, 1); return; }
    const au = target.closest<HTMLElement>('[data-colauto]');
    if (au?.dataset.colauto) { autoFit(au.dataset.colauto); return; }
    const fz = target.closest<HTMLElement>('[data-freeze]');
    if (fz) {
      st.frozen = Number(fz.dataset.freeze);
      renderAll();
      toast(st.frozen ? '已冻结前 ' + st.frozen + ' 列（横向滚动时保持可见）' : '已取消列冻结');
      return;
    }
    if (target.closest('[data-col-reset]')) {
      st.cols = baseCols.map((c) => ({ ...c }));
      st.frozen = 0;
      renderAll();
      toast('列已恢复默认');
    }
  };
  function moveCol(k: string, d: number) {
    const i = st.cols.findIndex((c) => c.k === k);
    const j = i + d;
    if (i < 0 || j < 0 || j >= st.cols.length) return;
    const tmp = st.cols[i];
    st.cols[i] = st.cols[j];
    st.cols[j] = tmp;
    renderAll();
  }
  if (colPanelEl) {
    colPanelEl.addEventListener('dragstart', onColDragStart);
    colPanelEl.addEventListener('dragover', onColDragOver);
    colPanelEl.addEventListener('drop', onColDrop);
    colPanelEl.addEventListener('dragend', onColDragEnd);
    colPanelEl.addEventListener('click', onColPanelClick);
  }

  /* ---------- 视图事件 ---------- */
  const onViewsClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    const del = target.closest<HTMLElement>('[data-view-del]');
    if (del) {
      const idx = Number(del.dataset.viewDel);
      const v = st.views[idx];
      st.views.splice(idx, 1);
      if (st.view === v.name) st.view = null;
      renderAll();
      toast('已删除视图「' + v.name + '」');
      return;
    }
    if (target.closest('[data-view-save]')) {
      const name = '自定义视图 ' + (st.views.length + 1);
      st.views.push({
        name,
        desc: visCols().length + ' 列 · ' + (st.sort.length ? st.sort.length + ' 列排序' : '默认序'),
        on: visCols().map((c) => c.k),
        order: st.cols.map((c) => c.k),
        w: st.cols.map((c) => ({ k: c.k, w: c.w })),
        sort: st.sort.slice(),
        filters: JSON.parse(JSON.stringify(st.filters)) as Record<string, GridFilter>,
        density: st.density,
        frozen: st.frozen,
      });
      st.view = name;
      renderAll();
      toast('已保存为「' + name + '」，随账号同步');
      return;
    }
    const row = target.closest<HTMLElement>('[data-view]');
    if (row) {
      const v = st.views[Number(row.dataset.view)];
      if (v) applyView(v);
    }
  };
  if (viewsEl) viewsEl.addEventListener('click', onViewsClick);

  /* ---------- 状态栏事件 ---------- */
  const onStatusClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    if (target.closest('[data-copy-sel]')) copyTSV();
    if (target.closest('[data-export-sel]')) {
      const agg = aggregate();
      const picked = st.rows.filter((_, i) => st.rowSel.has(i));
      if (opts.onExportRows && picked.length) opts.onExportRows(picked);
      else toast('导出选中 ' + agg.rows + ' 行 / ' + agg.cells + ' 个单元格为 CSV');
    }
  };
  if (statusEl) statusEl.addEventListener('click', onStatusClick);

  /* ---------- chips 事件（就近委托，不用 document 级，避免多实例互相干扰） ---------- */
  const onChipsClick = (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    const one = target.closest<HTMLElement>('[data-unfilter]');
    if (one?.dataset.unfilter) { delete st.filters[one.dataset.unfilter]; renderAll(); return; }
    if (target.closest('[data-unfilter-all]')) {
      st.filters = {};
      renderAll();
      toast('已清除全部本页筛选');
    }
  };
  if (chipsEl) chipsEl.addEventListener('click', onChipsClick);

  /* ---------- 视图开关 ---------- */
  const densitySegEl = el('density-seg');
  const onDensityClick = (e: MouseEvent) => {
    const b = (e.target as HTMLElement).closest('button');
    if (!b || !densitySegEl) return;
    const btns = densitySegEl.querySelectorAll('button');
    st.density = b === btns[0] ? 'compact' : 'comfortable';
    renderAll();
  };
  if (densitySegEl) densitySegEl.addEventListener('click', onDensityClick);

  const groupSwitchEl = el('group-switch');
  const onGroupSwitch = () => { st.grouped = !st.grouped; renderAll(); };
  if (groupSwitchEl) groupSwitchEl.addEventListener('click', onGroupSwitch);

  /* ---------- 查找 ---------- */
  const findInput = el('find') as HTMLInputElement | null;
  function next() {
    if (!st.findHits.length) return;
    st.findIdx = (st.findIdx + 1) % st.findHits.length;
    renderAll();
  }
  function runFind(q: string) {
    st.find = String(q || '').toLowerCase();
    st.findHits = [];
    if (st.find) {
      shown().forEach((r) => {
        const rowIdx = ri(r);
        visCols().forEach((c, ci) => {
          if (String(isEmpty(r[c.k]) ? '' : r[c.k]).toLowerCase().indexOf(st.find) >= 0) st.findHits.push({ ri: rowIdx, ci });
        });
      });
    }
    st.findIdx = st.findHits.length ? 0 : -1;
    renderAll();
  }
  const onFindInput = () => runFind(findInput!.value);
  const onFindKeyDown = (e: KeyboardEvent) => { if (e.key === 'Enter') { e.preventDefault(); next(); } };
  if (findInput) {
    findInput.addEventListener('input', onFindInput);
    findInput.addEventListener('keydown', onFindKeyDown);
  }
  const findNextBtn = el('find-next');
  const onFindNext = () => next();
  if (findNextBtn) findNextBtn.addEventListener('click', onFindNext);

  /* ---------- 导出（口径④：默认当前查询条件全量；有选中行时切「仅导出选中行」） ---------- */
  const exportBtn = el('export');
  const exportLabelEl = el('export-label');
  const pickedRows = () => st.rows.filter((_, i) => st.rowSel.has(i));
  const onExport = () => {
    const picked = pickedRows();
    if (picked.length) {
      if (opts.onExportRows) opts.onExportRows(picked);
      else toast('导出选中 ' + picked.length + ' 行（选区优先于全量）');
      return;
    }
    if (opts.onExport) opts.onExport();
    else toast('导出 CSV：当前查询条件全量（服务端流式导出，不是本页 ' + st.rows.length + ' 行）');
  };
  if (exportBtn) exportBtn.addEventListener('click', onExport);
  function renderExportLabel() {
    if (!exportLabelEl) return;
    const n = st.rowSel.size;
    exportLabelEl.textContent = n
      ? '仅导出选中 ' + n + ' 行'
      : (opts.exportLabel || '导出 CSV');
  }

  /* ---------- 浮层关闭 ---------- */
  const onPopClose = (e: MouseEvent) => {
    if ((e.target as HTMLElement).closest('[data-pop-close]')) closePops();
  };
  colPanelEl?.addEventListener('click', onPopClose);
  viewsEl?.addEventListener('click', onPopClose);

  renderAll();

  return {
    state: st,
    setRows(rows: GridRow[]) {
      st.rows = rows.slice();
      st.rowSel.clear();
      st.range = null;
      st.active = null;
      // 行变了，旧的 selection 索引全部失效；列头筛选保留（它按值匹配，与行序无关）
      renderAll();
      notifySelection();
    },
    setEmptyText(text: string) {
      if (opts.emptyText === text) return;
      opts.emptyText = text;
      renderAll();
    },
    setExportLabel(text: string) {
      if (opts.exportLabel === text) return;
      opts.exportLabel = text;
      renderAll();
    },
    setSelectable(v: boolean) {
      // W10：权限就绪后补齐行首复选框列。仅当开关真的变化时才重渲染（React 会多次重渲染）。
      if ((opts.selectable !== false) === v) return;
      opts.selectable = v;
      if (!v) { st.rowSel.clear(); notifySelection(); }
      renderAll();
    },
    setCols(cols: GridColumn[]) {
      // 保留用户已有的可见性 / 列宽（同 key 搬迁），新增列取声明默认值 ——
      // 页面在跨公司权限变化时会增删「公司主体」列，不能把用户调好的列序冲掉。
      const previous = new Map(st.cols.map((c) => [c.k, c]));
      st.cols = cols.map((c) => {
        const old = previous.get(c.k);
        return old ? { ...c, on: c.req ? true : old.on, w: old.w } : { ...c };
      });
      baseCols = cols.map((c) => ({ ...c }));
      renderAll();
    },
    snapshot: snap,
    enableSnapshotNotify() { notifySnapshot = true; },
    applySnapshot(snapshot: Partial<GridSnapshot>) {
      applyState(snapshot);
      if (snapshot.views) st.views = JSON.parse(JSON.stringify(snapshot.views)) as GridView[];
      st.view = snapshot.view ?? null;
      renderAll();
    },
    destroy() {
      thead.removeEventListener('mousedown', onHeadMouseDown);
      thead.removeEventListener('dblclick', onHeadDblClick);
      thead.removeEventListener('click', onHeadClick);
      tbody.removeEventListener('mousedown', onBodyMouseDown);
      tbody.removeEventListener('mousemove', onBodyMouseMove);
      tbody.removeEventListener('click', onBodyClick);
      document.removeEventListener('mouseup', onDocMouseUp);
      document.removeEventListener('selectstart', onDocSelectStart);
      if (colPanelEl) {
        colPanelEl.removeEventListener('dragstart', onColDragStart);
        colPanelEl.removeEventListener('dragover', onColDragOver);
        colPanelEl.removeEventListener('drop', onColDrop);
        colPanelEl.removeEventListener('dragend', onColDragEnd);
        colPanelEl.removeEventListener('click', onColPanelClick);
        colPanelEl.removeEventListener('click', onPopClose);
      }
      if (viewsEl) {
        viewsEl.removeEventListener('click', onViewsClick);
        viewsEl.removeEventListener('click', onPopClose);
      }
      if (statusEl) statusEl.removeEventListener('click', onStatusClick);
      if (chipsEl) chipsEl.removeEventListener('click', onChipsClick);
      if (densitySegEl) densitySegEl.removeEventListener('click', onDensityClick);
      if (groupSwitchEl) groupSwitchEl.removeEventListener('click', onGroupSwitch);
      if (findInput) {
        findInput.removeEventListener('input', onFindInput);
        findInput.removeEventListener('keydown', onFindKeyDown);
      }
      if (findNextBtn) findNextBtn.removeEventListener('click', onFindNext);
      if (exportBtn) exportBtn.removeEventListener('click', onExport);
    },
  };
}

export { money, num2, last4, isEmpty };
