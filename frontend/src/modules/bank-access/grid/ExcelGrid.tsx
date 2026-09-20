import { useCallback, useEffect, useRef, useState, type CSSProperties, type MutableRefObject, type ReactNode } from 'react';
import {
  createGrid,
  type GridColumn,
  type GridDensity,
  type GridFilter,
  type GridInstance,
  type GridRow,
  type GridSnapshot,
  type GridView,
} from './kernel';
import './grid.css';

/**
 * V35 表格内核的 React 包装层。
 *
 * 设计要点（都是踩过的坑，改之前先读）：
 * 1. 内核自管 DOM（thead/tbody/浮层内容一律 innerHTML），React 只负责给出**空容器**与
 *    `data-grid-role` 锚点。所以下面这些节点一律不写 children，React 也绝不会去覆盖内核写的内容。
 * 2. 实例只在挂载时创建一次，之后靠 setCols / setRows / setTotalAgg 增量更新。
 *    若把 createGrid 放进依赖数组里，每次 render 都会新建实例 → document 级监听器成倍累积。
 * 3. `cols` / `rows` 必须由调用方 memo（useMemo），否则每渲染一次新数组就会触发一次 setRows，
 *    而 setRows 会清空行勾选 → 用户每点一下复选框就被清掉。
 * 4. 视图 / 列设置两个浮层由 React 管开合（加 .is-on），筛选浮层由内核自己管；
 *    两边的「点外部关掉」统一走 closePops：既把 React 的浮层关掉，也顺手摘掉内核浮层的 .is-on。
 */

type PopKey = 'views' | 'col-panel';

export interface ExcelGridProps {
  /** 实例 id（同时是服务端偏好快照的 scope 的一部分）。 */
  id: string;
  cols: GridColumn[];
  rows: GridRow[];
  /** 服务端分页大小，仅用于「仅本页排序」提示文案。 */
  pageSize?: number;

  /* ---- 分组 ---- */
  groupBy?: string;
  groupedDefault?: boolean;
  groupSwitchLabel?: string;
  /** 默认随 groupBy 是否存在自动显示。 */
  showGroupSwitch?: boolean;
  groupMeta?: (group: string, rows: GridRow[]) => string;

  /* ---- 视图 / 密度 / 冻结 ---- */
  views?: GridView[];
  density?: GridDensity;
  frozen?: number;

  /* ---- 行行为 ---- */
  selectable?: boolean;
  rowClass?: (row: GridRow) => string;
  isRowSelectable?: (row: GridRow) => boolean;
  disabledRowHint?: string | ((row: GridRow) => string);
  onRowAction?: (action: string, row: GridRow) => void;
  onSelectionChange?: (rows: GridRow[]) => void;

  /* ---- 导出（口径④） ---- */
  onExport?: () => void;
  onExportRows?: (rows: GridRow[]) => void;
  exportLabel?: string;
  exportBadge?: ReactNode;

  /* ---- 其它 ---- */
  findPlaceholder?: string;
  emptyText?: string;
  /** 工具条左侧：页面自己的筛选控件（账户 / 时间 / 币种…）。 */
  toolbarStart?: ReactNode;
  /** 账号级偏好拉取完成后置 true。在此之前不写回，避免把刚挂载的空状态反向覆盖服务端偏好。 */
  preferenceReady?: boolean;
  initialSnapshot?: Partial<GridSnapshot> | null;
  /** 快照变化（列显隐 / 列序 / 列宽 / 排序 / 本页筛选 / 密度 / 冻结 / 视图）→ 落服务端。 */
  onSnapshotChange?: (snapshot: GridSnapshot) => void;
  /** V36 筛选服务端化：列头筛选集合变化 → 页面映射查询参数重新请求（详见 kernel.ts）。 */
  onFilterChange?: (filters: Record<string, GridFilter>) => void;
  toast?: (message: string) => void;
  /** 表格下方（分页器等）。 */
  footer?: ReactNode;
  /** 拿到内核实例（读 state.cols 等实时状态，例如「导出选中行」要按当前可见列出列）。 */
  instanceRef?: MutableRefObject<GridInstance | null>;
  className?: string;
  style?: CSSProperties;
}

export function ExcelGrid(props: ExcelGridProps) {
  const {
    cols, rows,
    pageSize, groupBy, groupSwitchLabel, findPlaceholder,
    exportLabel, exportBadge, toolbarStart, footer, className, style,
  } = props;

  const rootRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<GridInstance | null>(null);
  const [openPop, setOpenPop] = useState<PopKey | null>(null);

  // createGrid 在挂载时读一次 opts；之后所有回调都从 propsRef 取最新值，避免闭包陈旧。
  // 注意：不能在 render 里写 ref（react-compiler 的 react-hooks/refs 会拦），必须放到 effect。
  const propsRef = useRef(props);
  useEffect(() => {
    propsRef.current = props;
  });

  const closePops = useCallback(() => {
    setOpenPop(null);
    // 筛选浮层（filter-pop）是内核自己加 .is-on 的，React 不持有它的状态，只能就地摘掉。
    rootRef.current?.querySelectorAll('.pop.is-on').forEach((node) => node.classList.remove('is-on'));
  }, []);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const read = () => propsRef.current;
    const instance = createGrid({
      root,
      id: read().id,
      cols: read().cols,
      rows: read().rows,
      groupBy: read().groupBy,
      groupedDefault: read().groupedDefault,
      groupMeta: read().groupMeta,
      views: read().views,
      density: read().density,
      frozen: read().frozen,
      selectable: read().selectable,
      emptyText: read().emptyText,
      exportLabel: read().exportLabel,
      rowClass: (row) => read().rowClass?.(row) || '',
      isRowSelectable: (row) => read().isRowSelectable?.(row) ?? true,
      disabledRowHint: (row) => {
        const hint = read().disabledRowHint;
        return typeof hint === 'function' ? hint(row) : (hint || '该行不可勾选');
      },
      onRowAction: (action, row) => read().onRowAction?.(action, row),
      onSelectionChange: (picked) => read().onSelectionChange?.(picked),
      onExport: () => read().onExport?.(),
      onExportRows: (picked) => read().onExportRows?.(picked),
      onSnapshotChange: (snapshot) => read().onSnapshotChange?.(snapshot),
      onFilterChange: (filters) => read().onFilterChange?.(filters),
      toast: (message) => read().toast?.(message),
      onClosePops: () => closePops(),
    });
    gridRef.current = instance;
    if (propsRef.current.instanceRef) propsRef.current.instanceRef.current = instance;
    return () => {
      instance?.destroy();
      gridRef.current = null;
      if (propsRef.current.instanceRef) propsRef.current.instanceRef.current = null;
    };
  }, [closePops]);

  // 服务端偏好落定：先套用快照，再打开写回。顺序反了会把空偏好推回服务端。
  const preferenceAppliedRef = useRef(false);
  useEffect(() => {
    const instance = gridRef.current;
    if (!instance || !props.preferenceReady || preferenceAppliedRef.current) return;
    if (props.initialSnapshot) instance.applySnapshot(props.initialSnapshot);
    instance.enableSnapshotNotify();
    preferenceAppliedRef.current = true;
  }, [props.preferenceReady, props.initialSnapshot]);

  // 增量同步：只在引用真的变化时调用，挂载那一次是 no-op。
  const lastColsRef = useRef(cols);
  useEffect(() => {
    if (lastColsRef.current === cols) return;
    lastColsRef.current = cols;
    gridRef.current?.setCols(cols);
  }, [cols]);

  const lastRowsRef = useRef(rows);
  useEffect(() => {
    if (lastRowsRef.current === rows) return;
    lastRowsRef.current = rows;
    gridRef.current?.setRows(rows);
  }, [rows]);

  // 点空白处收起浮层。锚点内的点击放行（否则刚点开就被关掉）。
  useEffect(() => {
    const onDocClick = (event: MouseEvent) => {
      const target = event.target as HTMLElement;
      if (target.closest('[data-grid-pop-anchor]')) return;
      if (target.closest('[data-grid-role="filter-pop"]')) return;
      closePops();
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [closePops]);

  const togglePop = (key: PopKey) => {
    closePops();
    setOpenPop((current) => (current === key ? null : key));
  };

  const showGroupSwitch = props.showGroupSwitch ?? Boolean(groupBy);
  const sortScopeHint = [
    '仅本页排序',
    '列头筛选【全量】＝服务端口径',
    pageSize ? `${pageSize} 行/页` : '',
    groupBy ? '分组时按合计排组' : '',
  ].filter(Boolean).join(' · ');

  return (
    <div ref={rootRef} className={className ? `xgrid ${className}` : 'xgrid'} style={style}>
      <div className="toolbar">
        <div className="filters">
          {toolbarStart}
          <div className="field">
            <label>本页查找</label>
            <div className="find-wrap" style={{ width: 196 }}>
              <input className="find-input" data-grid-role="find" placeholder={findPlaceholder || 'Ctrl+F 关键词'} />
              <button className="btn btn-sm" type="button" data-grid-role="find-next" title="下一个匹配">↵</button>
            </div>
          </div>
        </div>

        <div className="actions">
          <div
            className="sort-scope"
            title="排序 / 选区汇总只作用于已加载的本页行，不代表全量。列头筛选按 chips 标注的口径生效：【全量】＝服务端全量（翻页 / 导出同口径），其余＝仅本页。分组视图下：排序先作用于组内，组顺序按该列合计排列。"
          >
            <span className="dot" />{sortScopeHint}
          </div>

          {showGroupSwitch && (
            <div
              className="switch"
              data-grid-role="group-switch"
              title="把「公司主体」从筛选项变成分组维度：分组行给出组内行数与合计"
            >
              <i />{groupSwitchLabel || '按主体分组'}
            </div>
          )}

          <div className="seg" data-grid-role="density-seg" title="行密度">
            <button type="button" className="is-on">紧凑</button>
            <button type="button">舒适</button>
          </div>

          <div className="views" data-grid-pop-anchor onClick={() => togglePop('views')}>
            <button className="btn" type="button">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 5h16M4 12h16M4 19h10" /></svg>
              我的视图
              <span className="tag tag-brand" style={{ height: 18, fontSize: 11 }} data-grid-role="view-label">默认视图</span>
            </button>
            <div className={openPop === 'views' ? 'pop is-on' : 'pop'} data-grid-role="views" />
          </div>

          <div className="colset-wrap" data-grid-pop-anchor onClick={() => togglePop('col-panel')}>
            <button className="btn" type="button">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
              列设置
              <span className="tag tag-brand" style={{ height: 18, fontSize: 11 }} data-grid-role="freeze-label">未冻结</span>
            </button>
            <div className={openPop === 'col-panel' ? 'pop is-on' : 'pop'} data-grid-role="col-panel" />
          </div>

          <button className="btn btn-primary" type="button" data-grid-role="export">
            <span data-grid-role="export-label">{exportLabel || '导出 CSV'}</span>
            {exportBadge}
          </button>
        </div>
      </div>

      <div className="filter-chips" data-grid-role="chips" />

      <div className="table-wrap" data-grid-role="host">
        <table className="data">
          <thead />
          <tbody />
        </table>
      </div>

      <div className="grid-status" data-grid-role="status" />

      {footer}

      <div className="pop filter-pop" data-grid-role="filter-pop" />
    </div>
  );
}
