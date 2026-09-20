import { create } from 'zustand';

/**
 * V36 D3：顶栏全局主体切换器的共享状态（全站生效：余额 / 流水 / 账户等页）。
 *
 * W8（2026-09-20）多选改造：选择语义从「单选 + 全部主体」升级为「多选 + 全部主体」，
 * 后端查询参数同步新增 companyIds（逗号分隔多值；银行数据链路一期接入）。
 * · companyIds = [] 表示全部主体（与旧 null 等价）。
 * · 旧 localStorage（{companyId: "..."} 或 null）自动迁移为 companyIds 数组。
 * · 偏好（当前选择 + 收藏）仍存本机 localStorage：作用域是浏览器级使用习惯。
 */

type SubjectScopeState = {
  /** 当前选中的公司主体 id 集合（字符串形式数字）；空数组 = 全部主体。 */
  companyIds: string[];
  /** 收藏（常用）主体 id 列表，仅本机偏好。 */
  favs: string[];
  setCompanyIds: (ids: string[]) => void;
  /** 单选兼容入口：等价 setCompanyIds([id])；null = 清空（全部主体）。 */
  setCompany: (companyId: string | null) => void;
  toggleCompany: (companyId: string) => void;
  toggleFav: (companyId: string) => void;
};

const STORAGE_KEY = 'finflow.subject-scope.v1';

const readStored = (): { companyIds: string[]; favs: string[] } => {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { companyId?: string | null; companyIds?: string[]; favs?: string[] };
      // 迁移：旧单选值 → 数组；新键优先。
      const legacy = parsed.companyId ?? null;
      const ids = Array.isArray(parsed.companyIds)
        ? parsed.companyIds.filter((id) => typeof id === 'string')
        : legacy
          ? [legacy]
          : [];
      return { companyIds: ids, favs: Array.isArray(parsed.favs) ? parsed.favs.filter((id) => typeof id === 'string') : [] };
    }
  } catch {
    // 存储损坏就当没存过，不能让整站崩在 JSON 上。
  }
  return { companyIds: [], favs: [] };
};

const persist = (state: { companyIds: string[]; favs: string[] }) => {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级为会话内状态。
  }
};

export const useSubjectScope = create<SubjectScopeState>((set, get) => ({
  ...readStored(),
  setCompanyIds: (companyIds) => {
    set({ companyIds });
    persist({ companyIds, favs: get().favs });
  },
  setCompany: (companyId) => get().setCompanyIds(companyId ? [companyId] : []),
  toggleCompany: (companyId) => {
    const current = get().companyIds;
    get().setCompanyIds(current.includes(companyId)
      ? current.filter((id) => id !== companyId)
      : [...current, companyId]);
  },
  toggleFav: (companyId) => {
    const favs = get().favs.includes(companyId)
      ? get().favs.filter((id) => id !== companyId)
      : [...get().favs, companyId];
    set({ favs });
    persist({ companyIds: get().companyIds, favs });
  },
}));
