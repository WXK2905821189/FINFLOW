import { create } from 'zustand';

/**
 * V36 D3：顶栏全局主体切换器的共享状态（全站生效：余额 / 流水 / 账户等页）。
 *
 * 口径说明（与 demo 第二版的差异，落地必需）：
 * · 服务端查询参数 companyId 只支持**单个**主体（BankDataExtraFilter.companyId 为单值），
 *   所以选择语义是「单选 + 全部主体」，不做 demo 里的多选 chips——客户端假装多选只会
 *   让分页 / 全量合计 / 导出对不上。
 * · 偏好（当前选择 + 收藏）存本机 localStorage：作用域是浏览器级使用习惯，不是账号级
 *   数据口径，没必要进服务端偏好表。
 */

type SubjectScopeState = {
  /** 当前选中的公司主体 id（字符串形式数字）；null = 全部主体。 */
  companyId: string | null;
  /** 收藏（常用）主体 id 列表，仅本机偏好。 */
  favs: string[];
  setCompany: (companyId: string | null) => void;
  toggleFav: (companyId: string) => void;
};

const STORAGE_KEY = 'finflow.subject-scope.v1';

const readStored = (): { companyId: string | null; favs: string[] } => {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { companyId?: string | null; favs?: string[] };
      return {
        companyId: parsed.companyId ?? null,
        favs: Array.isArray(parsed.favs) ? parsed.favs.filter((id) => typeof id === 'string') : [],
      };
    }
  } catch {
    // 存储损坏就当没存过，不能让整站崩在 JSON 上。
  }
  return { companyId: null, favs: [] };
};

const persist = (state: { companyId: string | null; favs: string[] }) => {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级为会话内状态。
  }
};

export const useSubjectScope = create<SubjectScopeState>((set, get) => ({
  ...readStored(),
  setCompany: (companyId) => {
    set({ companyId });
    persist({ companyId, favs: get().favs });
  },
  toggleFav: (companyId) => {
    const favs = get().favs.includes(companyId)
      ? get().favs.filter((id) => id !== companyId)
      : [...get().favs, companyId];
    set({ favs });
    persist({ companyId: get().companyId, favs });
  },
}));
