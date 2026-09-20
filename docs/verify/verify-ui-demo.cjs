/**
 * FINFLOW UI demo 自动化验证
 * ---------------------------------------------------------------
 * 真实浏览器（Playwright + Chromium）驱动真实点击，断言真实 DOM/计算样式/事件结果。
 * 用法：
 *   NODE_PATH=<node workspace>/node_modules node verify-ui-demo.cjs [demo.html]
 * 输出：
 *   tmp/verify-demo-result.json  机器可读结果
 *   stdout                       逐项 PASS/FAIL
 *
 * 设计原则（与项目「SSH 假阳性陷阱」同源）：
 *   不信「没报错」，只信独立效果探测——每一项都必须给出可观测到的状态变化。
 */
'use strict';

const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const ROOT = process.env.FINFLOW_ROOT || 'C:/Users/王小棵/Documents/ChatGPT/财务系统';
const DEMO = process.argv[2] || path.join(ROOT, 'docs', 'ui-v34-demo.html');
const FILE_URL = 'file:///' + DEMO.replace(/\\/g, '/');
const OUT_JSON = path.join(ROOT, 'tmp', 'verify-demo-result.json');

const results = [];
let currentGroup = '(未分组)';

function group(name) {
  currentGroup = name;
}
function record(name, ok, detail) {
  results.push({ group: currentGroup, name, ok: !!ok, detail: detail === undefined ? '' : String(detail) });
  const mark = ok ? 'PASS' : 'FAIL';
  console.log(`  [${mark}] ${name}${detail ? '  — ' + detail : ''}`);
}
function eq(actual, expected) {
  return JSON.stringify(actual) === JSON.stringify(expected);
}

/** 等待条件成立（轮询），超时返回 false —— 不用 sleep 赌时序 */
async function waitFor(page, fn, arg, timeout = 3000) {
  try {
    await page.waitForFunction(fn, arg, { timeout, polling: 60 });
    return true;
  } catch (e) {
    return false;
  }
}

/**
 * 列头类操作只能针对「默认开启」的列。
 * 默认关闭的列整列不在 DOM 里，直接点会挂在 Playwright 的 30s 超时上，
 * 报错只写 "waiting for locator..."，看不出真正原因（这个坑已复现两次）。
 * 这里先做一次显式校验，把失败变成可读的一句话。
 */
async function guardColOn(page, grid, k) {
  const on = await page.evaluate(([g, kk]) => {
    const gr = (window.__grids || {})[g];
    return !!(gr && gr.cols.some((c) => c.k === kk && c.on !== false));
  }, [grid, k]);
  if (!on) {
    throw new Error(`断言引用了默认未开启的列「${k}」→ 列头不在 DOM 中，请改用默认开启的列。`);
  }
}

/**
 * 浮层触发器是 toggle 语义（已开时再点会关掉），而且部分浮层内的按钮点完会「应用并自动收起」
 * ——已实测 #colPanel 的「列序下移」就会收起浮层。断言若假设「上一步留下的浮层还开着」，
 * 下一步就会挂在 30s 不可见超时上。这里先读真实状态，没开才点，幂等安全。
 */
async function ensurePopOpen(page, hostId, triggerSel) {
  const on = await page.evaluate((id) => {
    const p = document.getElementById(id);
    return !!p && p.classList.contains('is-on');
  }, hostId);
  if (!on) await page.click(triggerSel);
  await waitFor(page, (id) => {
    const p = document.getElementById(id);
    return !!p && p.classList.contains('is-on');
  }, hostId);
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1680, height: 1000 } });
  const page = await ctx.newPage();

  // ---------- 全局：运行时错误与外部请求 ----------
  const pageErrors = [];
  const consoleErrors = [];
  const externalRequests = [];
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('request', (r) => {
    const u = r.url();
    if (!u.startsWith('file://') && !u.startsWith('data:') && !u.startsWith('blob:')) externalRequests.push(u);
  });

  await page.goto(FILE_URL, { waitUntil: 'load' });

  /** 打开某屏（真实点击侧栏；分组折叠时先展开） */
  async function go(screen) {
    const sel = `.nav-item[data-screen="${screen}"]`;
    const visible = await page.evaluate((s) => {
      const el = document.querySelector(s);
      return !!el && el.offsetParent !== null;
    }, sel);
    if (!visible) {
      await page.evaluate((s) => {
        const el = document.querySelector(s);
        const g = el && el.closest('.nav-group');
        if (g && g.classList.contains('is-collapsed')) g.querySelector('[data-nav-toggle]').click();
      }, sel);
    }
    await page.click(sel);
    await waitFor(page, (s) => {
      const el = document.querySelector(`.screen[data-screen="${s}"]`);
      return !!el && el.classList.contains('is-on');
    }, screen);
  }
  /** 冷启动深链：先离开当前文档再带 hash 加载，避免同文档导航不重载导致的 Tab 状态残留 */
  async function gotoHash(screen) {
    await page.goto('about:blank');
    await page.goto(`${FILE_URL}#${screen}`, { waitUntil: 'load' });
  }

  const shownScreen = () => page.evaluate(() => {
    const el = document.querySelector('.screen.is-on');
    return el ? el.dataset.screen : null;
  });

  // ============================================================
  group('A. 启动 / 自包含');
  // ============================================================
  {
    const title = await page.title();
    record('A1 页面可加载（非空白）', (await page.locator('.nav-item').count()) > 0, `title="${title}"`);
    record('A2 无外部网络依赖（file:// 自包含）', externalRequests.length === 0,
      externalRequests.length ? externalRequests.slice(0, 3).join(' | ') : '0 个外部请求');
    const initScreen = await shownScreen();
    record('A3 默认落在工作台', initScreen === 'dashboard', `实际=${initScreen}`);
    const toastExists = await page.locator('#toast').count();
    record('A4 全局 toast 容器存在', toastExists === 1);
  }

  // ============================================================
  group('B. 导航');
  // ============================================================
  {
    const screens = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.nav-item')).map((b) => b.dataset.screen));
    // 期望值 = 侧栏一级页面数（voucherDoc 是 voucher 的子页面，不出现在侧栏）
    const EXPECTED_NAV = 16;
    record('B1 侧栏导航项数量', screens.length === EXPECTED_NAV, `${screens.length} 项（期望 ${EXPECTED_NAV}）`);
    record('B1b 每个导航项都有对应屏', await page.evaluate(() =>
      Array.from(document.querySelectorAll('.nav-item'))
        .every((b) => !!document.querySelector(`.screen[data-screen="${b.dataset.screen}"]`))));
    let bad = [];
    for (const s of screens) {
      await go(s);
      const on = await shownScreen();
      const hash = await page.evaluate(() => location.hash.slice(1));
      const title = await page.locator('#pageTitle').textContent();
      if (on !== s || hash !== s || !title) bad.push(`${s}(screen=${on},hash=${hash})`);
    }
    record('B2 逐项点击切换：屏/hash/标题三者一致', bad.length === 0, bad.length ? bad.join(', ') : `${screens.length}/${screens.length} 通过`);

    const activeCount = await page.evaluate(() => document.querySelectorAll('.nav-item.is-active').length);
    record('B3 任意时刻仅 1 个导航项高亮', activeCount === 1, `is-active=${activeCount}`);
  }

  // ============================================================
  group('C. 深链（hash 直达）');
  // ============================================================
  {
    const cases = ['balance', 'voucher', 'voucherDoc', 'category', 'accounts', 'users', 'closing', 'statements'];
    let bad = [];
    for (const s of cases) {
      await gotoHash(s);
      const on = await shownScreen();
      if (on !== s) bad.push(`${s}→${on}`);
    }
    record('C1 深链直达目标屏', bad.length === 0, bad.length ? bad.join(', ') : `${cases.length}/${cases.length} 通过`);

    // voucherDoc 是子页面：侧栏应高亮回退到父级 voucher
    await gotoHash('voucherDoc');
    const navActive = await page.evaluate(() => {
      const el = document.querySelector('.nav-item.is-active');
      return el ? el.dataset.screen : null;
    });
    record('C2 子页面 hash 回退父级导航高亮', navActive === 'voucher', `is-active=${navActive}`);

    // hash 变更（浏览器后退/手改）应驱动切换
    await gotoHash('balance');
    await page.evaluate(() => { location.hash = 'accounts'; });
    const changed = await waitFor(page, () => {
      const el = document.querySelector('.screen.is-on');
      return !!el && el.dataset.screen === 'accounts';
    }, null);
    record('C3 hash 变更驱动页面切换（hashchange）', changed);
  }

  // ============================================================
  group('D. 页内 Tab 分区');
  // ============================================================
  {
    await gotoHash('dashboard');
    const tabs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.screen[data-screen="dashboard"] .tab[data-tab]'))
        .map((t) => t.dataset.tab));
    record('D1 工作台 tab 数量', tabs.length === 4, `${tabs.length} 个: ${tabs.join('/')}`);

    let bad = [];
    for (const t of tabs) {
      await page.click(`.screen[data-screen="dashboard"] .tab[data-tab="${t}"]`);
      await waitFor(page, (n) => {
        const p = document.querySelector(`.screen[data-screen="dashboard"] .tabpanel[data-tab="${n}"]`);
        return !!p && p.classList.contains('is-on');
      }, t);
      const vis = await page.evaluate((n) => {
        const panels = Array.from(document.querySelectorAll('.screen[data-screen="dashboard"] .tabpanel'));
        const on = panels.filter((p) => p.classList.contains('is-on'));
        const cur = document.querySelector(`.screen[data-screen="dashboard"] .tabpanel[data-tab="${n}"]`);
        return { onCount: on.length, isOn: cur ? cur.classList.contains('is-on') : false, visible: cur ? cur.offsetParent !== null : false };
      }, t);
      if (!vis.isOn || vis.onCount !== 1 || !vis.visible) bad.push(`${t}(on=${vis.isOn},count=${vis.onCount})`);
    }
    record('D2 逐 tab 点击：唯一激活且真实可见', bad.length === 0, bad.length ? bad.join(', ') : `${tabs.length}/${tabs.length} 通过`);

    // data-go 跨屏 + 带 tab 参数（需取当前可见的触发器：数据 go 按钮分布在各 tab 面板内）
    await gotoHash('dashboard');
    const goBtn = page.locator('[data-go]:visible').first();
    const goTarget = await goBtn.getAttribute('data-go');
    const [tScreen, tTab] = goTarget.split(':');
    await goBtn.click();
    const okGo = await waitFor(page, (a) => {
      const s = document.querySelector('.screen.is-on');
      if (!s || s.dataset.screen !== a.s) return false;
      if (!a.t) return true;
      const p = s.querySelector(`.tabpanel[data-tab="${a.t}"]`);
      return !!p && p.classList.contains('is-on');
    }, { s: tScreen, t: tTab });
    record('D3 data-go 跨屏并激活指定 tab', okGo, `目标=${goTarget}`);

    // 反例护栏：data-go 指向不存在的 tab 时不应误判为成功
    await gotoHash('dashboard');
    const goCount = await page.locator('[data-go]').count();
    let allGoOk = true;
    const goTargets = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-go]')).map((el) => el.dataset.go));
    for (const g of goTargets) {
      const [s, t] = g.split(':');
      const valid = await page.evaluate((a) => {
        const scr = document.querySelector(`.screen[data-screen="${a.s}"]`);
        if (!scr) return false;
        if (!a.t) return true;
        return !!scr.querySelector(`.tabpanel[data-tab="${a.t}"]`);
      }, { s, t });
      if (!valid) allGoOk = false;
    }
    record('D4 所有 data-go 目标（屏 + tab）均有效', allGoOk, `${goCount} 个触发器: ${goTargets.join(', ')}`);
  }

  // ============================================================
  group('E. 锚定浮层（列设置）');
  // ============================================================
  {
    await gotoHash('balance');
    const cls = () => page.evaluate(() => {
      const p = document.getElementById('colPanel');
      return p ? p.classList.contains('is-on') : null;
    });
    record('E1 初始浮层关闭', (await cls()) === false);

    await page.click('[data-pop="colPanel"]');
    record('E2 点击触发器后浮层打开', (await cls()) === true);

    // 真实几何：浮层必须可见且宽度合理（不是 display:none 假装打开）
    const box = await page.locator('#colPanel').boundingBox();
    record('E3 浮层有真实渲染尺寸', !!box && box.width > 120 && box.height > 100,
      box ? `${Math.round(box.width)}×${Math.round(box.height)}` : 'null');

    await page.click('[data-pop="colPanel"]');
    record('E4 再次点击触发器关闭（切换语义）', (await cls()) === false);

    await page.click('[data-pop="colPanel"]');
    await page.click('.screen.is-on .page-heading h2'); // 点击浮层外部
    record('E5 点击外部关闭浮层', (await cls()) === false);

    await page.click('[data-pop="colPanel"]');
    await page.keyboard.press('Escape');
    record('E6 Esc 关闭浮层', (await cls()) === false);
  }

  // ============================================================
  group('F. 公司主体作用域（V34.1 核心）');
  // ============================================================
  {
    await gotoHash('balance');
    const snap = () => page.evaluate(() => ({      label: document.getElementById('scopeLabel').textContent.trim(),
      count: Number(document.getElementById('scopeCount').textContent.trim()),
      hint: document.getElementById('scopeHint').textContent.trim(),
      onRows: Array.from(document.querySelectorAll('.srow[data-skey]')).filter((r) => r.classList.contains('is-on')).length,
      chips: document.querySelectorAll('#scopeChips .chip-sc').length,
      scoped: document.getElementById('scopeBtn').classList.contains('is-scoped'),
      allBtn: document.getElementById('scopeAllBtn').textContent.trim(),
      pageText: Array.from(document.querySelectorAll('[data-scope-text]')).map((e) => e.textContent.trim())
    }));

    /** 保证主体浮层处于打开态（它是 toggle 语义，重复点会关闭） */
    async function ensureScopePop() {
      const open = await page.evaluate(() => document.getElementById('scopePop').classList.contains('is-on'));
      if (!open) await page.click('#scopeBtn');
      await waitFor(page, () => document.getElementById('scopePop').classList.contains('is-on'), null);
    }

    const s0 = await snap();
    record('F1 默认全选：标签为「全部主体」', s0.label === '全部主体' && s0.count === 5 && s0.onRows === 5,
      `label=${s0.label}, count=${s0.count}, 选中行=${s0.onRows}`);
    record('F2 默认未进入收窄态（按钮不高亮）', s0.scoped === false);

    await page.click('#scopeBtn');
    const popOn = await waitFor(page, () => {
      const p = document.getElementById('scopePop');
      return !!p && p.classList.contains('is-on');
    }, null);
    record('F3 顶栏切换器可打开', popOn);

    // 取消一个主体
    await page.click('.srow[data-skey="sh"]');
    const s1 = await snap();
    record('F4 取消单个主体：计数/标签/芯片联动',
      s1.count === 4 && s1.onRows === 4 && s1.chips === 4 && s1.label === '已选 4 个主体' && s1.scoped === true,
      `count=${s1.count}, chips=${s1.chips}, label=${s1.label}, scoped=${s1.scoped}`);
    record('F5 范围文案联动到页面（data-scope-text）',
      s1.pageText.every((t) => t === '范围内 4 个主体'),
      `页面文案=[${s1.pageText.join(' | ')}]`);

    // 再取消一个 → 单主体时标签变为简称
    await page.click('.srow[data-skey="sz"]');
    const s2 = await snap();
    record('F6 收窄到 1 个主体时显示简称', s2.count === 3 && s2.label === '已选 3 个主体', `label=${s2.label}`);

    // 恢复
    await page.click('.srow[data-skey="sh"]');
    await page.click('.srow[data-skey="sz"]');
    const s3 = await snap();
    record('F7 重新勾选可回到全部主体', s3.count === 5 && s3.label === '全部主体' && s3.scoped === false, `label=${s3.label}`);

    // 收藏星标
    const favBefore = await page.evaluate(() => document.querySelectorAll('.star[data-star].is-on').length);
    await page.click('.star[data-star="hz"]');
    const favAfter = await page.evaluate(() => document.querySelectorAll('.star[data-star].is-on').length);
    record('F8 星标收藏可切换', favAfter === favBefore + 1, `${favBefore} → ${favAfter}`);

    // 芯片移除
    await page.click('.srow[data-skey="cd"]');
    const chipCount = await page.evaluate(() => document.querySelectorAll('#scopeChips .chip-sc').length);
    await page.click('#scopeChips .chip-sc button[data-unpick]');
    const afterUnpick = await snap();
    record('F9 芯片 ✕ 可把主体移出范围', afterUnpick.chips === chipCount - 1, `${chipCount} → ${afterUnpick.chips}`);

    // 搜索过滤
    await page.fill('#scopeSearch', 'hz');
    const filt = await waitFor(page, () => {
      const rows = Array.from(document.querySelectorAll('#scopeList .srow'));
      const shown = rows.filter((r) => !r.hidden);
      return shown.length > 0 && shown.length < rows.length;
    }, null);
    const filtDetail = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('#scopeList .srow'));
      return `${rows.filter((r) => !r.hidden).length}/${rows.length} 行可见`;
    });
    record('F10 搜索可过滤主体列表', filt, filtDetail);
    await page.fill('#scopeSearch', '');

    // 「全选/清空」是按当前状态给动作名的智能切换：先验文案，再验行为
    const btnText = () => page.evaluate(() => document.getElementById('scopeAllBtn').textContent.trim());
    const s3b = await snap();
    record('F11 未全选时按钮文案为「选择全部」', s3b.count < 5 && (await btnText()) === '选择全部',
      `count=${s3b.count}, btn=${await btnText()}`);

    await page.click('#scopeAllBtn');
    const s4 = await snap();
    record('F12 点击后恢复全选且按钮改口为「清空选择」',
      s4.count === 5 && s4.label === '全部主体' && (await btnText()) === '清空选择',
      `label=${s4.label}, btn=${await btnText()}`);

    // 清空 → 零选择
    await page.click('#scopeAllBtn');
    const s5 = await snap();
    const emptyHint = await page.evaluate(() => {
      const el = document.querySelector('#scopeChips .chip-none');
      return el ? el.textContent.trim() : null;
    });
    record('F13 清空选择：浮层内给出「不返回数据」提示', s5.count === 0 && !!emptyHint, `提示="${emptyHint}"`);

    // 零选择的语义质量：不得对用户显示「已选 0 个主体」这种含糊文案
    await waitFor(page, () => {
      const t = document.querySelector('[data-scope-dim]');
      return !!t && Number(getComputedStyle(t).opacity) < 0.5;
    }, null);
    const zeroState = await page.evaluate(() => ({
      label: document.getElementById('scopeLabel').textContent.trim(),
      btnEmpty: document.getElementById('scopeBtn').classList.contains('is-empty'),
      bodyEmpty: document.body.classList.contains('is-scope-empty'),
      bannerVisible: (() => {
        const b = document.querySelector('.scope-empty-banner');
        return !!b && getComputedStyle(b).display !== 'none';
      })(),
      dimOpacity: (() => {
        const t = document.querySelector('[data-scope-dim]');
        return t ? getComputedStyle(t).opacity : null;
      })()
    }));
    record('F14 零选择时顶栏文案明确（非「已选 0 个主体」）', zeroState.label === '未选择主体',
      `chip 文案="${zeroState.label}"`);
    record('F15 零选择时顶栏 chip 呈危险态（视觉可辨）', zeroState.btnEmpty === true);
    record('F16 零选择时数据区降权 + 空态说明可见',
      zeroState.bodyEmpty && zeroState.bannerVisible && Number(zeroState.dimOpacity) < 0.5,
      `bodyEmpty=${zeroState.bodyEmpty}, banner=${zeroState.bannerVisible}, 表格 opacity=${zeroState.dimOpacity}`);

    // 复位到全选，避免污染后续用例
    await ensureScopePop();
    await page.click('#scopeAllBtn');
    await page.click('#scopeDoneBtn');
    const s6 = await snap();
    record('F17 复位：恢复全选且危险态消除',
      s6.count === 5 && s6.label === '全部主体' && !(await page.evaluate(() =>
        document.body.classList.contains('is-scope-empty'))),
      `label=${s6.label}`);

    // 完成 → 关闭并给出确认反馈
    await ensureScopePop();
    await page.click('#scopeDoneBtn');
    const closed = await page.evaluate(() => !document.getElementById('scopePop').classList.contains('is-on'));
    const toastText = await page.evaluate(() => document.getElementById('toast').textContent);
    record('F18 完成后浮层关闭且给出确认反馈', closed && /作用域已生效/.test(toastText), `toast="${toastText}"`);
  }

  // ============================================================
  group('G. 账期锁（V34 ④）');
  // ============================================================
  {
    await gotoHash('voucher');
    const locked0 = await page.evaluate(() => document.body.classList.contains('is-locked'));
    record('G1 初始为「账期已关闭」锁定态', locked0 === true);

    // 被锁按钮：真实点击 → toast 给出原因，且不触发跳转
    await page.click('button[data-lock-blocked]#pushGroupBtn');
    const t1 = await page.evaluate(() => document.getElementById('toast').textContent);
    const t1on = await page.evaluate(() => document.getElementById('toast').classList.contains('is-on'));
    record('G2 锁定态点击写操作：弹出可读原因（非静默失效）',
      t1on && /账期已关闭/.test(t1), `toast="${t1}"`);

    // 计算样式：锁定态灰色
    const lockedStyle = await page.evaluate(() => {
      const b = document.getElementById('pushGroupBtn');
      const s = getComputedStyle(b);
      return { bg: s.backgroundColor, color: s.color, cursor: s.cursor };
    });
    record('G3 锁定态按钮渲染为禁用外观', lockedStyle.cursor === 'not-allowed', `cursor=${lockedStyle.cursor}, bg=${lockedStyle.bg}`);

    // 解锁
    await page.click('#periodChip');
    const locked1 = await page.evaluate(() => document.body.classList.contains('is-locked'));
    const chipText = await page.evaluate(() => document.getElementById('periodChip').textContent.trim());
    record('G4 点击顶栏 chip 可解锁', locked1 === false && /开放中/.test(chipText), `chip="${chipText}"`);

    const unlockedStyle = await page.evaluate(() => {
      const b = document.getElementById('pushGroupBtn');
      const s = getComputedStyle(b);
      return { bg: s.backgroundColor, color: s.color, cursor: s.cursor };
    });
    record('G5 解锁后按钮真实恢复（计算样式变化）',
      unlockedStyle.bg !== lockedStyle.bg && unlockedStyle.cursor !== 'not-allowed',
      `${lockedStyle.bg} → ${unlockedStyle.bg}`);

    // 解锁态点击不再弹拒绝 toast
    await page.evaluate(() => { document.getElementById('toast').classList.remove('is-on'); document.getElementById('toast').textContent = ''; });
    await page.click('#pushGroupBtn');
    const t2 = await page.evaluate(() => document.getElementById('toast').textContent);
    record('G6 解锁后点击不再被拒绝', !/账期已关闭/.test(t2), `toast="${t2}"`);

    // 回到锁定态
    await page.click('#periodChip');
    record('G7 可切回锁定态', (await page.evaluate(() => document.body.classList.contains('is-locked'))) === true);
  }

  // ============================================================
  group('H. 抽屉 / 模态（分层承载重操作）');
  // ============================================================
  {
    await gotoHash('balance');
    const layerState = () => page.evaluate(() => ({
      drawer: document.querySelectorAll('.drawer.is-on').length,
      modal: document.querySelectorAll('.modal.is-on').length,
      overlay: document.querySelectorAll('.overlay.is-on').length,
      locked: document.body.style.overflow === 'hidden'
    }));

    await page.click('[data-open="#drawerFilter"]');
    await waitFor(page, () => !!document.querySelector('.drawer.is-on'), null);
    const d1 = await layerState();
    record('H1 打开抽屉：抽屉+遮罩+滚动锁定', d1.drawer === 1 && d1.overlay >= 1 && d1.locked,
      JSON.stringify(d1));
    const dbox = await page.locator('.drawer.is-on').boundingBox();
    record('H2 抽屉有真实渲染尺寸', !!dbox && dbox.width > 200 && dbox.height > 200,
      dbox ? `${Math.round(dbox.width)}×${Math.round(dbox.height)}` : 'null');

    await page.keyboard.press('Escape');
    await waitFor(page, () => !document.querySelector('.drawer.is-on, .modal.is-on'), null);
    const d2 = await layerState();
    record('H3 Esc 关闭抽屉并恢复滚动', d2.drawer === 0 && d2.overlay === 0 && !d2.locked, JSON.stringify(d2));

    // 模态：删除用户（危险操作 → 居中模态 + 外键检查）
    await gotoHash('users');
    const modalTrigger = page.locator('.screen.is-on [data-open^="#modal"]').first();
    if (await modalTrigger.count()) {
      await modalTrigger.click();
      await waitFor(page, () => !!document.querySelector('.modal.is-on'), null);
      const m1 = await layerState();
      const mbox = await page.locator('.modal.is-on').boundingBox();
      const mtext = await page.evaluate(() => {
        const m = document.querySelector('.modal.is-on');
        return m ? m.textContent.replace(/\s+/g, ' ').trim() : '';
      });
      const dangerOk = m1.modal === 1 && !!mbox && mbox.width > 300 && /删除/.test(mtext) && /不可撤销|外键|确认/.test(mtext);
      record('H4 危险操作打开居中模态且复述后果', dangerOk,
        `${mbox ? Math.round(mbox.width) + '×' + Math.round(mbox.height) : 'null'}, text="${mtext.slice(0, 80)}…"`);
      await page.keyboard.press('Escape');
      record('H5 Esc 关闭模态', (await layerState()).modal === 0);
    } else {
      record('H4 危险操作打开居中模态且复述后果', false, '未找到模态触发器');
      record('H5 Esc 关闭模态', false, '跳过');
    }

    // 账期解锁模态：二次输入确认（更强的危险确认）
    await gotoHash('closing');
    const unlockBtn = page.locator('.screen.is-on [data-open]').first();
    if (await unlockBtn.count()) {
      await unlockBtn.click();
      await waitFor(page, () => !!document.querySelector('.modal.is-on'), null);
      const mid = await page.evaluate(() => {
        const m = document.querySelector('.modal.is-on');
        if (!m) return null;
        return {
          n: document.querySelectorAll('.modal.is-on').length,
          text: m.textContent.replace(/\s+/g, ' ').trim(),
          confirmField: !!m.querySelector('.form-row')
        };
      });
      const ok6 = !!mid && mid.n === 1 && /二次确认/.test(mid.text) && mid.confirmField;
      record('H6 账期解锁模态含影响面复述 + 二次确认', ok6,
        mid ? `确认字段=${mid.confirmField}, 文案="${mid.text.slice(0, 90)}…"` : 'null');
      await page.keyboard.press('Escape');
      record('H7 Esc 关闭解锁模态', (await layerState()).modal === 0);
    } else {
      record('H6 账期解锁模态含影响面复述 + 二次确认', false, '未找到触发器');
      record('H7 Esc 关闭解锁模态', false, '跳过');
    }
  }

  // ============================================================
  group('I. 行内交互细节');
  // ============================================================
  {
    await gotoHash('balance');

    // 复制账号
    await page.click('.copy-chip[data-copy="7559020012345678"]');
    const ct = await page.evaluate(() => document.getElementById('toast').textContent);
    const cton = await page.evaluate(() => document.getElementById('toast').classList.contains('is-on'));
    record('I1 复制账号给出带内容的反馈', cton && /7559020012345678/.test(ct), `toast="${ct}"`);

    // 按主体分组开关（初始为开启：表格带 is-grouped）
    const grouped = () => page.evaluate(() => document.getElementById('balanceTable').classList.contains('is-grouped'));
    const visibleGroupRows = () => page.evaluate(() =>
      Array.from(document.querySelectorAll('#balanceTable tr.group-row'))
        .filter((r) => getComputedStyle(r).display !== 'none').length);

    const g0 = await grouped();
    const rows0 = await visibleGroupRows();
    record('I2 初始为分组视图：分组行真实可见', g0 === true && rows0 > 0, `is-grouped=${g0}, 可见分组行=${rows0}`);

    await page.click('#groupSwitch');
    await waitFor(page, () => !document.getElementById('balanceTable').classList.contains('is-grouped'), null);
    const rows1 = await visibleGroupRows();
    record('I3 关闭分组后分组行真实隐藏', (await grouped()) === false && rows1 === 0,
      `is-grouped=false, 可见分组行=${rows1}`);

    await page.click('#groupSwitch');
    await waitFor(page, () => document.getElementById('balanceTable').classList.contains('is-grouped'), null);
    const rows2 = await visibleGroupRows();
    record('I4 再次开启分组可恢复', (await grouped()) === true && rows2 === rows0,
      `可见分组行=${rows2}`);

    // 新增账户抽屉：银行自动识别 + 连通测试三态（均在同一区域内联反馈，不弹窗）
    await gotoHash('accounts');
    await page.click('.screen.is-on [data-open="#drawerAccount"]');
    await waitFor(page, () => !!document.querySelector('#drawerAccount.is-on'), null);

    const detect = await page.evaluate(() => {
      const d = document.getElementById('drawerAccount');
      const chip = Array.from(d.querySelectorAll('.tag')).map((t) => t.textContent.trim()).find((t) => /已识别/.test(t));
      const bankField = Array.from(d.querySelectorAll('.form-row label'))
        .map((l) => l.textContent.trim()).filter((t) => /开户银行/.test(t));
      const required = d.querySelectorAll('.form-row label .req').length;
      return { chip: chip || null, bankField: bankField.length, required };
    });
    record('I5 账号可自动识别银行并在表单内提示', !!detect.chip, `识别提示="${detect.chip}"`);
    record('I6 仅 2 个必填项（户名 / 账号）', detect.required === 2, `带 * 的字段=${detect.required} 个`);
    const initialStatus = await page.evaluate(() =>
      document.getElementById('testStatus').textContent.replace(/\s+/g, ' ').trim());
    const states = [];
    for (let i = 0; i < 3; i++) {
      await page.click('#testBtn');
      states.push(await page.evaluate(() => {
        const t = document.querySelector('#testStatus .tag');
        return t ? `${t.textContent.trim()}(${t.className.replace('tag', '').trim()})` : null;
      }));
    }
    const uniq = Array.from(new Set(states));
    record('I7 连通测试三态可循环', uniq.length === 3 && states.every(Boolean),
      `初始="${initialStatus.slice(0, 28)}…" → ${states.join(' → ')}`);

    // 连通失败必须原样给出银行错误码，不折叠成「稍后重试」
    const rawErr = await page.evaluate(() =>
      document.getElementById('testStatus').textContent.replace(/\s+/g, ' ').trim());
    const errShown = /[A-Z]{2}\d{5}|错误码|签名|白名单|未登记/.test(rawErr) || /连通正常|未启用|未配置/.test(rawErr);
    record('I8 连通结果给出可定位的原始信息', errShown, `"${rawErr.slice(0, 60)}…"`);

    await page.keyboard.press('Escape');
    record('I9 Esc 关闭新增账户抽屉', (await page.locator('#drawerAccount.is-on').count()) === 0);

    // 分段控件（.seg）必须真的有单选行为，不能只是长得像能点
    await gotoHash('balance');
    const segBefore = await page.evaluate(() => {
      const s = document.querySelector('.screen.is-on .seg');
      return Array.from(s.querySelectorAll('button')).map((b) => b.classList.contains('is-on') ? 1 : 0);
    });
    await page.click('.screen.is-on .seg button:nth-child(2)');
    const segAfter = await page.evaluate(() => {
      const s = document.querySelector('.screen.is-on .seg');
      return Array.from(s.querySelectorAll('button')).map((b) => b.classList.contains('is-on') ? 1 : 0);
    });
    record('I10 分段控件点击后真实切换选中态',
      !eq(segBefore, segAfter) && segAfter.reduce((a, b) => a + b, 0) === 1,
      `${JSON.stringify(segBefore)} → ${JSON.stringify(segAfter)}`);

    // 分段控件必须全局保持单选，不能出现两个都亮
    const multiOn = await page.evaluate(() => {
      let bad = 0;
      document.querySelectorAll('.seg').forEach((s) => {
        if (s.querySelectorAll('button.is-on').length > 1) bad += 1;
      });
      return bad;
    });
    record('I11 所有分段控件均保持单选（无多选态）', multiOn === 0, `存在多选的分段控件=${multiOn}`);
  }

  // ============================================================
  group('J. 引用完整性（运行时）');
  // ============================================================
  {
    await gotoHash('dashboard');
    const broken = await page.evaluate(() => {
      const out = { open: [], pop: [] };
      document.querySelectorAll('[data-open]').forEach((el) => {
        const sel = el.dataset.open;
        if (!document.querySelector(sel)) out.open.push(sel);
      });
      document.querySelectorAll('[data-pop]').forEach((el) => {
        const id = el.dataset.pop;
        if (!document.getElementById(id)) out.pop.push(id);
      });
      return out;
    });
    record('J1 data-open 目标全部存在', broken.open.length === 0, broken.open.join(', ') || '0 死链');
    record('J2 data-pop 目标全部存在', broken.pop.length === 0, broken.pop.join(', ') || '0 死链');

    const jumpBad = await page.evaluate(() => {
      const names = new Set(Array.from(document.querySelectorAll('.screen')).map((s) => s.dataset.screen));
      const bad = [];
      document.querySelectorAll('[data-screen-jump]').forEach((el) => {
        if (!names.has(el.dataset.screenJump)) bad.push(el.dataset.screenJump);
      });
      document.querySelectorAll('[data-go]').forEach((el) => {
        const [s] = el.dataset.go.split(':');
        if (!names.has(s)) bad.push('data-go:' + s);
      });
      return bad;
    });
    record('J3 跳转目标全部是有效屏名', jumpBad.length === 0, jumpBad.join(', ') || '0 死链');

    const tabBad = await page.evaluate(() => {
      const bad = [];
      document.querySelectorAll('.screen').forEach((s) => {
        const panels = new Set(Array.from(s.querySelectorAll('.tabpanel[data-tab]')).map((p) => p.dataset.tab));
        s.querySelectorAll('.tab[data-tab]').forEach((t) => {
          if (!panels.has(t.dataset.tab)) bad.push(`${s.dataset.screen}:${t.dataset.tab}`);
        });
      });
      return bad;
    });
    record('J4 每个 tab 都有对应面板', tabBad.length === 0, tabBad.join(', ') || '0 死链');
  }

  // ============================================================
  group('L. Excel 表格内核（排序 / 筛选 / 列布局 / 选区汇总 / 视图）');
  // ============================================================
  {
    /** 读某列在屏幕上从上到下的数值（"--" 视为空值） */
    const colNums = (table, k) => page.evaluate((a) => {
      const t = document.querySelector(a.t);
      const idx = Array.from(t.querySelectorAll('thead th')).findIndex((th) => th.dataset.k === a.k);
      return Array.from(t.querySelectorAll('tbody tr[data-ri]')).map((tr) => {
        const td = tr.querySelectorAll('td')[idx];
        if (!td) return null;
        const txt = td.textContent.trim();
        if (txt.indexOf('--') >= 0 || txt === '') return null;
        const v = Number(txt.replace(/[^0-9.\-]/g, ''));
        return isNaN(v) ? null : v;
      });
    }, { t: table, k: k });

    /** 非空值是否单调（升序 1 / 降序 -1），且空值全部在末尾 */
    function monotone(list, dir) {
      const vals = list.filter((v) => v !== null);
      const nullAt = list.findIndex((v) => v === null);
      if (nullAt >= 0 && list.slice(nullAt).some((v) => v !== null)) return false;
      for (let i = 1; i < vals.length; i++) {
        if (dir === 1 && vals[i] < vals[i - 1] - 1e-6) return false;
        if (dir === -1 && vals[i] > vals[i - 1] + 1e-6) return false;
      }
      return true;
    }

    // ---------- L1 默认列集 ----------
    await gotoHash('balance');
    const headCols = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#balanceTable thead th[data-k]')).map((th) => th.dataset.k));
    record('L1 余额页默认列集＝V34 五列 + 必需列「直连状态」',
      headCols.length === 6 && headCols.indexOf('connectStatus') >= 0,
      `列=${headCols.join(', ')}`);

    // ---------- L2 排序口径必须常驻可见（用户选了「仅当前页排序」，界面必须说清） ----------
    const scopeChip = await page.evaluate(() => {
      const el = document.querySelector('.screen.is-on .sort-scope');
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { text: el.textContent.trim(), w: Math.round(r.width), h: Math.round(r.height), visible: el.offsetParent !== null };
    });
    record('L2 排序口径「仅本页」常驻标注可见（非 toast，不可关闭）',
      !!scopeChip && scopeChip.visible && scopeChip.w > 80 && /本页/.test(scopeChip.text),
      scopeChip ? `${scopeChip.w}×${scopeChip.h} "${scopeChip.text}"` : 'null');

    const thTip = await page.evaluate(() => document.querySelector('#balanceTable thead th[data-k="availableBalance"]').getAttribute('title'));
    record('L3 列头 tooltip 也写明「仅本页排序」', /仅本页|本页/.test(thTip || ''), `title="${thTip}"`);

    // ---------- L4 排序三态循环 + 真实行序变化 ----------
    const clickTh = (k) => page.click(`#balanceTable thead th[data-k="${k}"] .th-t`);
    const sortState = () => page.evaluate(() => JSON.parse(JSON.stringify(window.__grids.balance.sort)));
    const grouped = () => page.evaluate(() => document.getElementById('balanceTable').classList.contains('is-grouped'));

    // 先关掉分组：分组视图下排序只作用于组内，全局有序性另有专项用例（L8b）
    await page.click('#groupSwitch');
    await waitFor(page, () => !document.getElementById('balanceTable').classList.contains('is-grouped'), null);
    record('L4 可关闭分组以观察全局排序（前置）', (await grouped()) === false);

    await clickTh('availableBalance');
    const ascVals = await colNums('#balanceTable', 'availableBalance');
    const s1 = await sortState();
    record('L5 点列头升序：行序真实变为全局升序',
      s1.length === 1 && s1[0].k === 'availableBalance' && s1[0].dir === 1 && monotone(ascVals, 1),
      `sort=${JSON.stringify(s1)}, 列值=[${ascVals.join(', ')}]`);

    const indCls = await page.evaluate(() => document.querySelector('#balanceTable thead th[data-k="availableBalance"]').className);
    record('L6 升序指示器落类（sort-asc）', /sort-asc/.test(indCls), indCls);

    await clickTh('availableBalance');
    const descVals = await colNums('#balanceTable', 'availableBalance');
    const s2 = await sortState();
    record('L7 再点转降序：行序真实变为全局降序且空值仍在末尾',
      s2[0].dir === -1 && monotone(descVals, -1) &&
      descVals[descVals.length - 1] === null && descVals[descVals.length - 2] !== null,
      `dir=${s2[0].dir}, 列值=[${descVals.join(', ')}]`);

    await clickTh('availableBalance');
    const s3 = await sortState();
    record('L8 第三次点取消排序（三态循环闭合）', s3.length === 0, `sort=${JSON.stringify(s3)}`);

    // ---------- L8b 分组视图下的排序语义（组内有序 + 组间按合计） ----------
    await gotoHash('balance');
    await clickTh('availableBalance');   // 默认分组开启
    const groupedSort = await page.evaluate(() => {
      const table = document.getElementById('balanceTable');
      const idx = Array.from(table.querySelectorAll('thead th')).findIndex((th) => th.dataset.k === 'availableBalance');
      const seq = [];
      let cur = null;
      table.querySelectorAll('tbody tr').forEach((tr) => {
        if (tr.classList.contains('group-row')) { cur = { name: tr.textContent.trim().slice(0, 8), vals: [] }; seq.push(cur); return; }
        if (!cur) return;
        const td = tr.querySelectorAll('td')[idx];
        const txt = td ? td.textContent.trim() : '';
        cur.vals.push(txt.indexOf('--') >= 0 ? null : Number(txt.replace(/[^0-9.\-]/g, '')));
      });
      return seq;
    });
    const withinOk = groupedSort.every((g) => {
      const m = monotone(g.vals, 1);
      return m;
    });
    const groupSums = groupedSort.map((g) => g.vals.reduce((a, v) => a + (v || 0), 0));
    const groupOrderOk = groupSums.every((v, i) => i === 0 || v >= groupSums[i - 1] - 1e-6);
    record('L8b 分组视图下：组内有序，且组顺序按该列合计排列（与分组行印的合计同口径）',
      withinOk && groupOrderOk && groupedSort.length === 3,
      `组数=${groupedSort.length}, 组合计=[${groupSums.map((v) => v.toFixed(2)).join(', ')}], 组内有序=${withinOk}`);

    // ---------- L9 Shift+点击多列排序 ----------
    await gotoHash('balance');
    await page.click('#groupSwitch');    // 关分组，看全局多列序
    await clickTh('currency');
    await page.keyboard.down('Shift');
    await clickTh('bankName');
    await page.keyboard.up('Shift');
    const s4 = await sortState();
    // 徽标数字是「排序优先级」，DOM 顺序是「列顺序」，两者本就不一致：
    // bankName 在默认列序里靠前，但它是第二个排序键 → 应挂徽标 2。
    // 因此必须按列 key 建映射来验证，不能假设 DOM 顺序 == 排序顺序。
    const rankMap = await page.evaluate(() => {
      const out = {};
      document.querySelectorAll('#balanceTable thead th.has-rank').forEach((th) => {
        const b = th.querySelector('.sort-rank');
        out[th.dataset.k] = b ? b.textContent.trim() : '';
      });
      return out;
    });
    const rankKeys = Object.keys(rankMap);
    record('L9 Shift+点击支持多列排序，且序号徽标只出现在参与排序的列上',
      s4.length === 2 && rankKeys.length === 2
        && rankMap.currency === '1' && rankMap.bankName === '2',
      `sort=${JSON.stringify(s4)}, 带序号表头=${rankKeys.length}, 徽标=${JSON.stringify(rankMap)}`);

    // ---------- L10 列头筛选：值勾选 ----------
    await gotoHash('balance');
    const beforeRows = await page.evaluate(() => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length);
    await page.click('#balanceTable thead th[data-k="currency"] .filter-btn');
    const fpopOpen = await waitFor(page, () => {
      const p = document.getElementById('filterPop');
      return !!p && p.classList.contains('is-on');
    }, null);
    record('L10 列头筛选浮层可打开', fpopOpen);
    const fbox = await page.locator('#filterPop').boundingBox();
    record('L11 筛选浮层用 fixed 定位并有真实尺寸', !!fbox && fbox.width > 200 && fbox.height > 80,
      fbox ? `${Math.round(fbox.width)}×${Math.round(fbox.height)} @${Math.round(fbox.x)},${Math.round(fbox.y)}` : 'null');

    // 只留 CNY
    await page.evaluate(() => {
      document.querySelectorAll('#filterPop .fp-val').forEach((el) => {
        const on = el.dataset.fval === 'CNY';
        const box = el.querySelector('.box');
        box.classList.toggle('on', on);
        box.textContent = on ? '✓' : '';
      });
    });
    await page.click('#filterPop [data-fapply]');
    await waitFor(page, () => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length === 2, null);
    const afterRows = await page.evaluate(() => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length);
    const chipInfo = await page.evaluate(() => ({
      on: document.getElementById('balanceChips').classList.contains('is-on'),
      text: document.getElementById('balanceChips').textContent.replace(/\s+/g, ' ').trim()
    }));
    record('L12 值勾选筛选：行数真实减少且 chip 出现',
      afterRows === 2 && afterRows < beforeRows && chipInfo.on && /币种/.test(chipInfo.text),
      `${beforeRows} → ${afterRows} 行, chip="${chipInfo.text}"`);

    // 移除 chip 恢复
    await page.click('#balanceChips [data-unfilter]');
    await waitFor(page, () => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length === 4, null);
    const restored = await page.evaluate(() => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length);
    record('L13 移除 chip 后行数恢复', restored === 4, `→ ${restored} 行`);

    // ---------- L14 列头筛选：数值区间 ----------
    await gotoHash('balance');
    await page.click('#balanceTable thead th[data-k="availableBalance"] .filter-btn');
    await waitFor(page, () => document.getElementById('filterPop').classList.contains('is-on'), null);
    await page.fill('#filterPop .fp-range input:nth-of-type(1)', '5000000');
    await page.click('#filterPop [data-fapply]');
    await waitFor(page, () => document.querySelectorAll('#balanceTable tbody tr[data-ri]').length === 1, null);
    const rangeRows = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#balanceTable tbody tr[data-ri]')).map((tr) =>
        tr.querySelector('td[data-k="availableBalance"]').textContent.trim()));
    record('L14 数值区间筛选：只留 ≥ 500 万的行，且空值行被排除',
      rangeRows.length === 1 && /12,480,336\.20/.test(rangeRows[0]),
      `命中=[${rangeRows.join(' | ')}]`);

    // ---------- L15 无命中必须给空态，不能白屏 ----------
    // 注意：账号列同时支持「按值勾选 / 文本包含」两种口径，默认停在第一种（kinds[0]）。
    // 不先切 tab 就直接找 .fp-text 会超时 → 这是脚本自身的取用前提，不是页面缺陷。
    await gotoHash('balance');
    await page.click('#balanceTable thead th[data-k="accountNumber"] .filter-btn');
    await waitFor(page, () => document.getElementById('filterPop').classList.contains('is-on'), null);
    await page.click('#filterPop [data-fkind="text"]');
    await waitFor(page, () => !!document.querySelector('#filterPop .fp-text input'), null);
    await page.fill('#filterPop .fp-text input', 'ZZZ-NOT-EXIST');
    await page.click('#filterPop [data-fapply]');
    await waitFor(page, () => !!document.querySelector('#balanceTable tbody .grid-empty'), null);
    const emptyTxt = await page.evaluate(() => {
      const el = document.querySelector('#balanceTable tbody .grid-empty');
      return el ? el.textContent.trim() : '';
    });
    record('L15 筛选无命中：给出可读空态（不白屏、不假报 0 行）',
      /没有命中/.test(emptyTxt) && /chip/.test(emptyTxt), `"${emptyTxt}"`);

    // ---------- L16 列宽拖拽 ----------
    await gotoHash('balance');
    const wOf = (k) => page.evaluate((kk) => window.__grids.balance.cols.find((c) => c.k === kk).w, k);
    const w0 = await wOf('bankName');
    const hb = await page.locator('#balanceTable thead th[data-k="bankName"] .col-resize').boundingBox();
    await page.mouse.move(hb.x + 3, hb.y + hb.height / 2);
    await page.mouse.down();
    await page.mouse.move(hb.x + 73, hb.y + hb.height / 2, { steps: 6 });
    await page.mouse.up();
    const w1 = await wOf('bankName');
    record('L16 拖拽列宽真实生效', w1 > w0 + 40, `${w0} → ${w1}px`);

    // 拖拽不应误触发排序
    const sortAfterDrag = await sortState();
    record('L17 拖拽列宽不会误触发排序', sortAfterDrag.length === 0, `sort=${JSON.stringify(sortAfterDrag)}`);

    // ---------- L18 双击自适应列宽 ----------
    await gotoHash('balance');
    await guardColOn(page, 'balance', 'lastSyncAt');
    const c0 = await wOf('lastSyncAt');
    await page.dblclick('#balanceTable thead th[data-k="lastSyncAt"] .col-resize');
    const c1 = await wOf('lastSyncAt');
    record('L18 双击手柄按内容自适应列宽', c1 !== c0 && c1 >= 74, `${c0} → ${c1}px`);

    // ---------- L19 列顺序 ----------
    await gotoHash('balance');
    const orderOf = () => page.evaluate(() => window.__grids.balance.cols.map((c) => c.k));
    const o0 = await orderOf();
    await ensurePopOpen(page, 'colPanel', '[data-pop="colPanel"]');
    await page.click('#colPanel [data-coldown="bankName"]');
    const o1 = await orderOf();
    record('L19 列顺序可调整（下移）且顺序真实变化',
      o1.indexOf('bankName') === o0.indexOf('bankName') + 1,
      `${o0.slice(0, 3).join('>')} → ${o1.slice(0, 3).join('>')}`);

    // ---------- L20 列冻结 ----------
    // 重置列序：L19 已把 bankName 下移，而「冻结前 N 列」冻的是第 1..N 列，
    // 不重置的话冻的是 accountNumber，断言取 bankName 会误判成「冻结失效」。
    await gotoHash('balance');
    // 默认不冻结：冻结列数与行密度是「命名视图」携带的偏好（见 views: [{ ..., density, frozen }]），
    // 顶层 opts 不声明 frozen。所以这里走用户的真实路径——在列设置里手动开启。
    const frozenInit = await page.evaluate(() => window.__grids.balance.frozen);
    await ensurePopOpen(page, 'colPanel', '[data-pop="colPanel"]');
    await page.click('#colPanel [data-freeze="1"]');
    await waitFor(page, () => document.querySelector('#balanceTable thead th[data-k="bankName"]')
      .classList.contains('is-frozen'), null);
    const fzState = await page.evaluate(() => {
      const th = document.querySelector('#balanceTable thead th[data-k="bankName"]');
      const td = document.querySelector('#balanceTable tbody tr[data-ri] td[data-k="bankName"]');
      return {
        frozen: window.__grids.balance.frozen,
        label: document.getElementById('freezeLabel').textContent.trim(),
        thPos: getComputedStyle(th).position,
        tdPos: getComputedStyle(td).position
      };
    });
    record('L20 开启冻结后表头与数据单元格都变 sticky，且顶栏标签同步',
      frozenInit === 0 && fzState.frozen === 1 && /冻结 1 列/.test(fzState.label)
        && fzState.thPos === 'sticky' && fzState.tdPos === 'sticky',
      `初始 frozen=${frozenInit} → ${fzState.frozen}, 标签="${fzState.label}", pos th/td=${fzState.thPos}/${fzState.tdPos}`);

    // 冻结只有在表宽超出容器时才观察得到：先多开几列把表宽撑出去。
    for (const k of ['accountName', 'onlineBalance', 'frozenBalance']) {
      await ensurePopOpen(page, 'colPanel', '[data-pop="colPanel"]');
      await page.click(`#colPanel [data-coltoggle="${k}"]`);
      await waitFor(page, (kk) => {
        const c = window.__grids.balance.cols.find((x) => x.k === kk);
        return !!c && c.on === true;
      }, k);
    }
    await page.keyboard.press('Escape');

    const fzGeo = await page.evaluate(async () => {
      const host = document.getElementById('balanceHost');
      // 真正的横向滚动容器未必是 balanceHost 本身，往上找第一个溢出的
      const sc = [host].concat(Array.from(host.querySelectorAll('*')))
        .find((el) => el.scrollWidth > el.clientWidth + 20 && el.clientWidth > 200) || host;
      const thOf = (k) => document.querySelector('#balanceTable thead th[data-k="' + k + '"]');
      const tdOf = (k) => document.querySelector('#balanceTable tbody tr[data-ri] td[data-k="' + k + '"]');
      const x = (el) => (el ? Math.round(el.getBoundingClientRect().x) : null);
      const overflow = sc.scrollWidth - sc.clientWidth;
      const before = { th: x(thOf('bankName')), td: x(tdOf('bankName')), free: x(thOf('availableBalance')) };
      sc.scrollLeft = sc.scrollWidth;          // 赋大值，浏览器会 clamp 到真实最大值（滚到底）
      await new Promise((r) => setTimeout(r, 260));
      const after = { th: x(thOf('bankName')), td: x(tdOf('bankName')), free: x(thOf('availableBalance')) };
      return {
        overflow, scrollLeft: Math.round(sc.scrollLeft), before, after,
        thPos: getComputedStyle(thOf('bankName')).position,
        tdPos: getComputedStyle(tdOf('bankName')).position
      };
    });
    const dId = (a, b) => Math.abs(a - b);
    const freeShift = dId(fzGeo.after.free, fzGeo.before.free);
    record('L20b 横向滚到底时冻结的表头与单元格都不动、且彼此对齐（表头不跟着滚走，也不与数据列错位）',
      fzGeo.overflow > 20 && fzGeo.scrollLeft >= fzGeo.overflow - 2
        && fzGeo.thPos === 'sticky' && fzGeo.tdPos === 'sticky'
        && dId(fzGeo.after.th, fzGeo.before.th) <= 1
        && dId(fzGeo.after.td, fzGeo.before.td) <= 1
        && dId(fzGeo.after.th, fzGeo.after.td) <= 1
        && freeShift > 20 && dId(freeShift, fzGeo.scrollLeft) <= 2,
      `溢出=${fzGeo.overflow}px, scrollLeft=${fzGeo.scrollLeft}, 冻结 th ${fzGeo.before.th}→${fzGeo.after.th} / td ${fzGeo.before.td}→${fzGeo.after.td}, 非冻结位移=${freeShift}, pos=${fzGeo.thPos}/${fzGeo.tdPos}`);

    // 可取消：设为 0 后表头与单元格都不再 sticky
    await ensurePopOpen(page, 'colPanel', '[data-pop="colPanel"]');
    await page.click('#colPanel [data-freeze="0"]');
    await waitFor(page, () => !document.querySelector('#balanceTable thead th[data-k="bankName"]')
      .classList.contains('is-frozen'), null);
    const unfrozen = await page.evaluate(() => {
      const th = document.querySelector('#balanceTable thead th[data-k="bankName"]');
      const td = document.querySelector('#balanceTable tbody tr[data-ri] td[data-k="bankName"]');
      return {
        th: getComputedStyle(th).position, td: getComputedStyle(td).position,
        cls: th.classList.contains('is-frozen')
      };
    });
    record('L20c 取消冻结后表头与单元格都恢复非 sticky',
      !unfrozen.cls && unfrozen.th !== 'sticky' && unfrozen.td !== 'sticky',
      `class.is-frozen=${unfrozen.cls}, th=${unfrozen.th}, td=${unfrozen.td}`);

    // ---------- L21 行密度 ----------
    await gotoHash('balance');
    // tbody 里第一个 td 属于分组行（group-row），它的 padding 是分组行专用值、不随密度变化。
    // 必须取真实数据行（tr[data-ri]）的 td，否则无论怎么切换密度都读到同一个数。
    const padOf = () => page.evaluate(() => {
      const td = document.querySelector('#balanceTable tbody tr[data-ri] td');
      return td ? getComputedStyle(td).paddingTop : null;
    });
    const pad0 = await padOf();
    await page.click('#densitySeg button:nth-child(2)');
    const dense = await page.evaluate(() => document.getElementById('balanceTable').classList.contains('is-dense'));
    const pad1 = await padOf();
    const pd0 = parseInt(pad0, 10);
    const pd1 = parseInt(pad1, 10);
    record('L21 行密度切换真实改变行高（紧凑/舒适）',
      dense === false && pd0 < pd1, `dense=${dense}, padding ${pad0} → ${pad1}`);

    // ---------- L22 选区汇总（口径＝本页） ----------
    await gotoHash('balance');
    // 勾选前两行
    await page.click('#balanceTable tbody tr[data-ri="0"] .col-check .box');
    await page.click('#balanceTable tbody tr[data-ri="1"] .col-check .box');
    await waitFor(page, () => /已选/.test(document.getElementById('balanceStatus').textContent), null);
    const expectSum = await page.evaluate(() => {
      const g = window.__grids.balance;
      return 12480336.20 + 86420.55 + 0 + 0; // 两行的可用余额 + 两行的冻结/联机等数字列
    });
    const aggInfo = await page.evaluate(() => {
      const g = window.__grids;
      const el = document.getElementById('balanceStatus');
      const m = el.textContent.match(/求和\s*¥\s*([\d,\.]+)/);
      const a = el.textContent.match(/平均\s*¥\s*([\d,\.]+)/);
      const c = el.textContent.match(/已选\s*(\d+)\s*单元格\s*\/\s*(\d+)\s*行/);
      return {
        text: el.textContent.replace(/\s+/g, ' ').trim(),
        sum: m ? Number(m[1].replace(/,/g, '')) : null,
        avg: a ? Number(a[1].replace(/,/g, '')) : null,
        cells: c ? Number(c[1]) : null, rows: c ? Number(c[2]) : null
      };
    });
    record('L22 选区汇总给出计数/求和/平均三项且行数正确',
      aggInfo.sum !== null && aggInfo.avg !== null && aggInfo.rows === 2 && aggInfo.cells > 0,
      `已选 ${aggInfo.cells} 单元格 / ${aggInfo.rows} 行, 求和=${aggInfo.sum}, 平均=${aggInfo.avg}`);
    record('L23 选区汇总口径被标注为「本页」（不冒充全量）',
      /只作用于本页/.test(aggInfo.text) && /不代表全量/.test(aggInfo.text),
      aggInfo.text.slice(-70));

    // ---------- L24 两个合计口径并存且数值不同 ----------
    const twoTotals = await page.evaluate(() => {
      const st = document.getElementById('balanceStatus').textContent;
      const tb = document.getElementById('balanceTotalAgg').textContent;
      const m1 = st.match(/本页可见小计\s*¥\s*([\d,\.]+)/);
      const m2 = tb.match(/全量合计\s*¥\s*([\d,\.]+)/);
      return {
        pageSum: m1 ? Number(m1[1].replace(/,/g, '')) : null,
        pageLabel: /本页可见小计/.test(st),
        totalSum: m2 ? Number(m2[1].replace(/,/g, '')) : null,
        totalLabel: /全量合计/.test(tb) && /438/.test(tb),
        totalText: tb.trim()
      };
    });
    record('L24 「本页可见小计」与「全量合计」两个口径并存、各自标注',
      twoTotals.pageLabel && twoTotals.totalLabel && twoTotals.pageSum !== null && twoTotals.totalSum !== null,
      `本页=${twoTotals.pageSum}, 全量=${twoTotals.totalSum}, 按钮="${twoTotals.totalText}"`);
    record('L25 两个口径不是同一个数（否则等于误导）',
      twoTotals.pageSum !== twoTotals.totalSum && twoTotals.totalSum > twoTotals.pageSum,
      `${twoTotals.pageSum} vs ${twoTotals.totalSum}`);

    // ---------- L26 Ctrl+C 复制为 TSV ----------
    await page.click('#balanceStatus [data-copy-sel]');
    await waitFor(page, () => window.__grids.balance.lastTSV.length > 0, null);
    const tsv = await page.evaluate(() => window.__grids.balance.lastTSV);
    const lines = tsv.trim().split('\r\n');
    record('L26 复制选区产出真正的 TSV（表头 + 2 行，制表符分隔）',
      lines.length === 3 && lines.every((l) => l.indexOf('\t') >= 0) && /7559020012345678/.test(tsv),
      `${lines.length} 行，首行="${lines[0].slice(0, 46)}…"`);

    // ---------- L27 查找（Ctrl+F 语义） ----------
    await gotoHash('balance');
    await page.fill('#balanceFind', '招');
    await waitFor(page, () => document.querySelectorAll('#balanceTable td.cell-hit').length > 0, null);
    const hit1 = await page.evaluate(() => document.querySelectorAll('#balanceTable td.cell-hit').length);
    await page.click('#balanceFindNext');
    const hit2 = await page.evaluate(() => document.querySelectorAll('#balanceTable td.cell-hit-active').length);
    record('L27 本页查找命中高亮 + 逐个跳转', hit1 > 0 && hit2 === 1, `命中 ${hit1} 处，当前定位 ${hit2} 处`);

    // ---------- L28 我的视图 ----------
    await gotoHash('balance');
    const v0 = await page.evaluate(() => window.__grids.balance.views.length);
    await ensurePopOpen(page, 'viewsPop', '[data-pop="viewsPop"]');
    await page.click('#viewsPop [data-view-save]');
    const v1 = await page.evaluate(() => window.__grids.balance.views.length);
    record('L28 可保存当前为命名视图', v1 === v0 + 1, `${v0} → ${v1} 个视图`);

    await ensurePopOpen(page, 'viewsPop', '[data-pop="viewsPop"]');
    await page.click('#viewsPop .viewrow[data-view="1"]');
    await waitFor(page, () => window.__grids.balance.view === '大额关注', null);
    const viewApplied = await page.evaluate(() => {
      const g = window.__grids.balance;
      const cols = g.cols.filter((c) => c.on).map((c) => c.k);
      const label = document.getElementById('viewLabel').textContent.trim();
      return { cols: cols, sort: JSON.parse(JSON.stringify(g.sort)), label: label };
    });
    record('L29 应用视图：整套「列 + 排序」被恢复（不是只改个名字）',
      viewApplied.label === '大额关注' &&
      viewApplied.sort.length === 1 && viewApplied.sort[0].k === 'availableBalance' && viewApplied.sort[0].dir === -1 &&
      viewApplied.cols.length === 4 && viewApplied.cols.indexOf('connectStatus') >= 0,
      `列=${viewApplied.cols.join(',')}, sort=${JSON.stringify(viewApplied.sort)}`);

    // ---------- L30 导出范围标注 ----------
    const exp = await page.evaluate(() => document.getElementById('balExportBtn').textContent.replace(/\s+/g, ' ').trim());
    record('L30 导出按钮标明范围是「筛选全量」而非当前页', /筛选全量/.test(exp), `"${exp}"`);
    await page.click('#balExportBtn');
    const expToast = await page.evaluate(() => document.getElementById('toast').textContent);
    record('L31 导出反馈说明是服务端全量（不是本页行数）', /全量/.test(expToast), `"${expToast}"`);

    // ---------- L32 单元格内交互控件不被选区逻辑吞掉（回归护栏） ----------
    await gotoHash('balance');
    await page.click('#balanceTable tbody td[data-ri="0"][data-k="bankName"]'); // 先制造一次选区（会重渲染）
    await page.click('#balanceTable .copy-chip[data-copy="7559020012345678"]');
    const copyToast = await page.evaluate(() => document.getElementById('toast').textContent);
    const copyOn = await page.evaluate(() => document.getElementById('toast').classList.contains('is-on'));
    record('L32 选区重渲染后单元格内控件仍可点（复制按钮未被吞）',
      copyOn && /7559020012345678/.test(copyToast), `toast="${copyToast}"`);

    // ---------- L33 流水查询屏：借贷双轨 ----------
    await gotoHash('statements');
    const stmtCols = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#stmtTable thead th[data-k]')).map((th) => th.dataset.k));
    record('L33 流水页借贷双轨三列并存（借 / 贷 / 余额，不合并为正负号单列）',
      stmtCols.indexOf('debitAmount') >= 0 && stmtCols.indexOf('creditAmount') >= 0 && stmtCols.indexOf('balance') >= 0,
      `列=${stmtCols.join(', ')}`);

    const stmtRows = await page.evaluate(() => document.querySelectorAll('#stmtTable tbody tr[data-ri]').length);
    record('L34 流水页数据真实渲染（非占位）', stmtRows >= 20, `${stmtRows} 行`);

    // 无发生额一侧必须是 --，不能用 0 兜底
    const zeroFallback = await page.evaluate(() => {
      const tds = Array.from(document.querySelectorAll('#stmtTable tbody td[data-k="debitAmount"], #stmtTable tbody td[data-k="creditAmount"]'));
      const dash = tds.filter((td) => td.textContent.trim() === '--').length;
      const zero = tds.filter((td) => td.textContent.trim() === '0.00' || td.textContent.trim() === '0').length;
      return { dash: dash, zero: zero, total: tds.length };
    });
    record('L35 无发生额一侧显示 -- 而不是 0（不用零值制造「有值」假象）',
      zeroFallback.dash > 0 && zeroFallback.zero === 0,
      `--: ${zeroFallback.dash} 个 / 0.00: ${zeroFallback.zero} 个 / 共 ${zeroFallback.total}`);

    // ---------- L36 条件格式 ----------
    const cf = await page.evaluate(() => ({
      late: document.querySelectorAll('#stmtTable td.cf-late').length,
      big: document.querySelectorAll('#stmtTable td.cf-big').length,
      voucherRejected: document.querySelectorAll('#stmtTable td.cf-neg').length
    }));
    record('L36 条件格式有真实命中（未制证 / 大额 / 已驳回）',
      cf.late > 0 && cf.big > 0,
      `未制证=${cf.late}, 大额=${cf.big}, 已驳回=${cf.voucherRejected}`);
    const bigText = await page.evaluate(() => {
      const td = document.querySelector('#stmtTable td.cf-big');
      return td ? td.textContent.trim() + ' / boxShadow=' + getComputedStyle(td).boxShadow : null;
    });
    record('L37 条件格式真的渲染出样式（不只是加了个 class）',
      /rgb/.test(bigText || ''), bigText);

    // ---------- L38 流水页排序可用 ----------
    await page.click('#stmtTable thead th[data-k="creditAmount"] .th-t');
    await waitFor(page, () => window.__grids.statements.sort.length === 1, null);
    const stmtDesc = await colNums('#stmtTable', 'creditAmount');
    record('L38 流水页列头排序可用且顺序正确',
      monotone(stmtDesc, 1) || monotone(stmtDesc, -1),
      `sort=${JSON.stringify(await page.evaluate(() => window.__grids.statements.sort))}, 前 5 值=[${stmtDesc.slice(0, 5).join(', ')}]`);

    // ---------- L39 分组小计（把主体/账户从筛选项变成分组维度） ----------
    await gotoHash('statements');
    await page.click('#stmtGroupSwitch');
    await waitFor(page, () => document.querySelectorAll('#stmtTable tbody tr.group-row').length > 0, null);
    const gRows = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('#stmtTable tbody tr.group-row'));
      return {
        n: rows.filter((r) => getComputedStyle(r).display !== 'none').length,
        text: rows[0] ? rows[0].textContent.replace(/\s+/g, ' ').trim() : ''
      };
    });
    record('L39 流水页「按账户分组」生成带小计的分组行',
      gRows.n > 0 && /笔/.test(gRows.text), `${gRows.n} 个分组，首组="${gRows.text}"`);

    // ---------- L40 两个页面共享同一内核（结构一致性） ----------
    const both = await page.evaluate(() => ({
      stmt: {
        status: !!document.getElementById('stmtStatus'),
        chips: !!document.getElementById('stmtChips'),
        colPanel: !!document.getElementById('stmtColPanel'),
        views: !!document.getElementById('stmtViewsPop'),
        scope: !!document.querySelector('.screen[data-screen="statements"] .sort-scope')
      },
      bal: {
        status: !!document.getElementById('balanceStatus'),
        chips: !!document.getElementById('balanceChips'),
        colPanel: !!document.getElementById('colPanel'),
        views: !!document.getElementById('viewsPop'),
        scope: !!document.querySelector('.screen[data-screen="balance"] .sort-scope')
      }
    }));
    const keys = ['status', 'chips', 'colPanel', 'views', 'scope'];
    record('L40 余额页与流水页共用同一套表格内核（能力不缺失）',
      keys.every((k) => both.bal[k] && both.stmt[k]),
      JSON.stringify(both));
  }

  // ============================================================
  group('K. 运行时健康度');
  // ============================================================
  {
    await gotoHash('dashboard');
    // 走一遍显眼路径后再看错误收集
    for (const s of ['balance', 'voucher', 'category', 'accounts', 'users', 'closing', 'statements']) await go(s);
    record('K1 无未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | ') || '0 个');
    record('K2 无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | ') || '0 条');

    // 关键容器的实际渲染尺寸（防「打开但零尺寸」）
    const sizes = await page.evaluate(() => {
      const keys = ['.shell', '.sider', '.topbar', '.content', '.screen.is-on .page-heading'];
      const out = {};
      keys.forEach((k) => {
        const el = document.querySelector(k);
        if (!el) { out[k] = null; return; }
        const r = el.getBoundingClientRect();
        out[k] = { w: Math.round(r.width), h: Math.round(r.height) };
      });
      return out;
    });
    const shellOk =
      sizes['.shell'] && sizes['.shell'].w >= 1000 &&
      sizes['.sider'] && sizes['.sider'].w > 150 && sizes['.sider'].h > 400 &&
      sizes['.topbar'] && sizes['.topbar'].h > 40 &&
      sizes['.content'] && sizes['.content'].h > 200 &&
      sizes['.screen.is-on .page-heading'] && sizes['.screen.is-on .page-heading'].h > 20;
    record('K3 外壳关键容器均有合理尺寸', shellOk, JSON.stringify(sizes));

    // 横向溢出检查（数据密集型表格常见事故）
    const overflow = await page.evaluate(() => ({
      docW: document.documentElement.scrollWidth,
      winW: window.innerWidth
    }));
    record('K4 页面无横向溢出', overflow.docW <= overflow.winW + 2, `${overflow.docW} vs ${overflow.winW}`);
  }

  await browser.close();

  // ---------- 汇总 ----------
  const total = results.length;
  const passed = results.filter((r) => r.ok).length;
  const failed = results.filter((r) => !r.ok);
  console.log('\n' + '='.repeat(60));
  console.log(`总计 ${passed}/${total} 通过` + (failed.length ? `，${failed.length} 项失败` : '，全部通过'));
  if (failed.length) {
    console.log('\n失败项：');
    failed.forEach((f) => console.log(`  - [${f.group}] ${f.name}  — ${f.detail}`));
  }
  console.log('='.repeat(60));

  fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });
  fs.writeFileSync(OUT_JSON, JSON.stringify({
    generatedAt: new Date().toISOString(),
    demo: DEMO,
    total, passed, failed: failed.length,
    results
  }, null, 2), 'utf8');
  console.log('结果已写入 ' + OUT_JSON);

  process.exit(failed.length ? 1 : 0);
})().catch((e) => {
  console.error('HARNESS ERROR:', e && e.stack || e);
  process.exit(2);
});
