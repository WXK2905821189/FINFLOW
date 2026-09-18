import { useCallback, useEffect, useRef, useState } from 'react';
import { preferenceApi } from '../../../services/api';
import type { GridSnapshot } from './kernel';

/**
 * 表格偏好的服务端持久化（V35 口径③：随**账号**保存，跨设备一致，不是本机 localStorage）。
 *
 * 用法：
 *   const { snapshot, ready, save } = useGridPreference('bankdata.balances');
 *   <ExcelGrid preferenceReady={ready} initialSnapshot={snapshot} onSnapshotChange={save} ... />
 *
 * `ready` 之前 ExcelGrid 不会回写 —— 否则首次挂载的空状态会把服务端已存偏好反向覆盖。
 */
const SAVE_DEBOUNCE_MS = 900;

type Loaded = { scope: string; snapshot: Partial<GridSnapshot> | null; ready: boolean };

export function useGridPreference(scope: string) {
  const [loaded, setLoaded] = useState<Loaded>({ scope, snapshot: null, ready: false });
  const timerRef = useRef<number | null>(null);
  const pendingRef = useRef<GridSnapshot | null>(null);

  useEffect(() => {
    let alive = true;
    preferenceApi.get(scope)
      .then((result) => {
        if (!alive) return;
        let parsed: Partial<GridSnapshot> | null = null;
        if (result?.payload) {
          try {
            parsed = JSON.parse(result.payload) as Partial<GridSnapshot>;
          } catch {
            // 服务端存的是不透明 JSON；真损坏了就当没存过，绝不能让整页崩在解析上。
            parsed = null;
          }
        }
        setLoaded({ scope, snapshot: parsed, ready: true });
      })
      .catch(() => {
        if (alive) setLoaded({ scope, snapshot: null, ready: true });
      });
    return () => { alive = false; };
  }, [scope]);

  const flush = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    const pending = pendingRef.current;
    pendingRef.current = null;
    if (pending) {
      // 偏好写失败不打断用户操作（列已经调好了），下次变更会重试。
      void preferenceApi.put(scope, JSON.stringify(pending)).catch(() => {});
    }
  }, [scope]);

  // 拖列宽 / 连续勾选列会在一秒内触发几十次快照，落库必须防抖。
  const save = useCallback((next: GridSnapshot) => {
    pendingRef.current = next;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(flush, SAVE_DEBOUNCE_MS);
  }, [flush]);

  // 离开页面（或切换 scope）时把还没落库的最后一次变更补发，
  // 否则「调完列宽立刻切页」会把这次调整丢掉。
  useEffect(() => flush, [flush]);

  // scope 变了但请求还没回来时，按「没存过」渲染，避免拿上一个页面的偏好去套新页面。
  const current: Loaded = loaded.scope === scope ? loaded : { scope, snapshot: null, ready: false };
  return { snapshot: current.snapshot, ready: current.ready, save };
}
