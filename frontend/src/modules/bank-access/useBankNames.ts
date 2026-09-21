import { useEffect, useMemo, useState } from 'react';
import { dictApi, type DictItemRow } from '../../services/api';
import { BANK_NAME_TEXT } from './bankQueryTexts';

/**
 * 银行中文名解析（2026-09-21）。
 *
 * 唯一可维护源是**字典中心**的 `bank` 字典类型（系统管理 → 字典中心）：
 *   item_code = 银行代码（CITIC / CMB / …），label = 中文名，sort_no 决定下拉顺序，
 *   停用（status != ACTIVE）即从界面消失而历史数据不动。
 * 新增银行只需在字典中心加一条 —— **无需改代码、无需重新构建、无需发版**。
 *
 * `BANK_NAME_TEXT` 降级为**代码兜底**：字典未维护或接口不可用时仍能显示正确中文名；
 * 同一代码两边都有时以字典为准（运营可随时改名而不必发版）。
 *
 * 三个实现要点（踩坑记录，改这里前先读）：
 *  · 模块级缓存 + inflight 去重：一次会话只请求一次，全站多页共用；失败可重试。
 *  · `resolveBankName` 是**模块级函数**（引用恒定）：V35 内核的列定义会把它放进 useMemo
 *    依赖，若它每次渲染都换引用，列数组就会每渲染重建一次 → ExcelGrid 每渲染一次
 *    setCols 一次。恒定引用从根上避免这个抖动。
 *  · 字典是异步到达的，靠 `revision` 变化触发列/行重建；内核 `setCols` 按列 key 保留
 *    用户的可见性与列宽（`grid/kernel.ts` 已保证），所以不会冲掉用户调好的表格。
 */

export type BankNameOption = { value: string; label: string };

/** 字典类型编码：在字典中心新建类型时填这个值。 */
export const BANK_DICT_TYPE = 'bank';

let dictNames: Record<string, string> = {};
let dictOrder: string[] = [];
let revision = 0;
let loaded = false;
let inflight: Promise<void> | null = null;
const listeners = new Set<() => void>();

const notify = () => {
  listeners.forEach((fn) => fn());
};

/**
 * 按代码取银行中文名：字典优先 → 代码兜底常量 → 原样返回代码。
 * 不认识的银行原样显示代码而不是留空 —— 便于第一时间发现「档案里有、字典里没有」。
 */
export const resolveBankName = (code?: string | null): string => {
  if (!code) return '';
  return dictNames[code] || BANK_NAME_TEXT[code] || code;
};

/** 下拉选项：字典项按 sort_no 在前，兜底常量中字典未覆盖的追加在后（去重）。 */
export const bankNameOptions = (): BankNameOption[] => {
  const seen = new Set(dictOrder);
  return [
    ...dictOrder.map((code) => ({ value: code, label: resolveBankName(code) })),
    ...Object.entries<string>(BANK_NAME_TEXT)
      .filter(([code]) => !seen.has(code))
      .map(([value, label]) => ({ value, label })),
  ];
};

/** 账号尾号：掩码账号 `**** **** 4821` 与完整账号都取最后 4 位。 */
export const accountTail = (accountNumber?: string | null): string => {
  const digits = String(accountNumber ?? '').replace(/\D/g, '');
  return digits ? digits.slice(-4) : '';
};

/**
 * 「银行-尾号」账户标识（2026-09-21 用户拍板口径），主体树等展示位统一用它。
 * 账号缺失退化为仅银行名；银行也未知时返回空串，由调用方决定兜底文案。
 */
export const bankAccountLabel = (bankCode?: string | null, accountNumber?: string | null): string => {
  const name = resolveBankName(bankCode);
  const tail = accountTail(accountNumber);
  if (name && tail) return `${name}-${tail}`;
  return name || tail;
};

const load = (): Promise<void> => {
  if (loaded) return Promise.resolve();
  if (inflight) return inflight;
  // 显式标注载荷类型 + 显式 Promise<void>：不依赖「dictApi 返回类型是否可推断」，
  // 避免控制流分析把 inflight 视作仍可空而报 TS2322。
  const chain: Promise<void> = dictApi.activeItems(BANK_DICT_TYPE)
    .then((items: DictItemRow[]) => {
      const names: Record<string, string> = {};
      const order: string[] = [];
      (items || []).forEach((item: DictItemRow) => {
        const code = item?.itemCode;
        const label = item?.label;
        if (!code || !label) return;
        names[code] = label;
        order.push(code);
      });
      dictNames = names;
      dictOrder = order;
      loaded = true;
      revision += 1;
    })
    .catch(() => {
      // 银行名不是关键路径：字典取不到就退回代码兜底，绝不让页面因此失败；loaded 保持 false 以便重试。
      dictNames = {};
      dictOrder = [];
    })
    .finally(() => {
      inflight = null;
      notify();
    });
  inflight = chain;
  return chain;
};

export type BankNames = {
  /** 每次字典成功加载后自增：放进 useMemo 依赖即可让列/行随字典刷新。 */
  revision: number;
  /** 恒定引用：按银行代码取中文名（含兜底）。 */
  resolve: (code?: string | null) => string;
  /** 下拉选项（字典顺序优先，含兜底项）。 */
  options: BankNameOption[];
};

export function useBankNames(): BankNames {
  const [rev, setRev] = useState(revision);
  useEffect(() => {
    const sync = () => setRev((current) => (current === revision ? current : revision));
    listeners.add(sync);
    void load();
    return () => {
      listeners.delete(sync);
    };
  }, []);
  // options 只在字典变更（rev 变化）时重建：bankNameOptions() 每次调用都返回新数组，
  // 直接放进返回值会让 Select 的 options 每渲染换引用，进而带着依赖它的 memo 一起重算。
  const options = useMemo(() => bankNameOptions(), [rev]);
  return { revision: rev, resolve: resolveBankName, options };
}
