// 金蝶 openapi.open.kingdee.com 登录态捕获 + 文档树拉取脚本 v1
// 用法: node openapi-capture.js
// 行为: 启动有头 Edge -> 打开 ApiDoc 页 -> 等待用户登录(检测到 CDP_TK) ->
//       在页面内直接 fetch /mscdp/apicenter/apitree 保存文档树 -> 存 storageState
// 参照: kingdee-login.js (vip 抓取版) 的同款方案
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const OUT_DIR = __dirname;
const STATE_PATH = path.join(OUT_DIR, 'openapi-storage-state.json');
const TREE_PATH = path.join(OUT_DIR, 'openapi-apitree.json');
const DOC_URL = 'https://openapi.open.kingdee.com/ApiDoc';
const TIMEOUT_MS = 12 * 60 * 1000; // 12 分钟
const POLL_MS = 2000;

(async () => {
  console.log('[1] 启动有头 Edge (channel=msedge)...');
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: false,
    args: ['--start-maximized']
  });
  const context = await browser.newContext({ viewport: null });
  const page = await context.newPage();

  console.log('[2] 打开 ApiDoc 页 (可能跳登录)...');
  await page.goto(DOC_URL, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => {
    console.log('  页面加载提示:', e.message.split('\n')[0]);
  });
  await page.waitForTimeout(5000);

  const getTk = () => page.evaluate(() => {
    const m = document.cookie.match(/(?:^|;\s*)CDP_TK=([^;]+)/);
    return m ? m[1] : null;
  }).catch(() => null);

  const deadline = Date.now() + TIMEOUT_MS;
  let tk = null;
  let lastUrl = '';
  console.log('[3] 等待登录 (窗口内若显示登录页/扫码, 请完成登录)...');
  while (Date.now() < deadline) {
    tk = await getTk();
    if (tk) {
      console.log('  已检测到 CDP_TK，登录成功');
      break;
    }
    const url = page.url();
    if (url !== lastUrl) {
      console.log('  当前URL:', url.slice(0, 120));
      lastUrl = url;
    }
    await page.waitForTimeout(POLL_MS);
  }

  if (!tk) {
    console.log('[x] 超时未检测到登录态，退出');
    await browser.close();
    process.exit(1);
  }

  console.log('[4] 在页面内拉取文档树 /mscdp/apicenter/apitree ...');
  const tree = await page.evaluate(async (tok) => {
    const r = await fetch('/mscdp/apicenter/apitree', {
      headers: { 'CDP_TK': tok, 'Content-Type': 'application/json; charset=utf-8' }
    });
    return { status: r.status, body: await r.text() };
  }, tk);
  fs.writeFileSync(TREE_PATH, JSON.stringify(tree, null, 2), 'utf8');
  console.log('  文档树保存:', TREE_PATH, '(HTTP', tree.status + ',', tree.body.length, 'chars)');

  console.log('[5] 保存 storageState ...');
  await context.storageState({ path: STATE_PATH });
  console.log('  已保存:', STATE_PATH);
  console.log('[6] 完成');
  await browser.close();
})().catch(e => { console.error('失败:', e.message); process.exit(1); });
