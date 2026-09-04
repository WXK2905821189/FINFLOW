// 金蝶 vip.kingdee.com 登录态捕获脚本 v2
// 用法: node kingdee-login.js
// 行为: 启动有头 Edge -> 打开专题页 -> 等待用户登录(URL离开login页即成功) -> 保存 storageState
const { chromium } = require('playwright');
const path = require('path');

const OUT_DIR = __dirname; // docs/kingdee-openapi/articles/
const STATE_PATH = path.join(OUT_DIR, 'storage-state.json');
const SPECIAL_URL = 'https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963348454780672&id=298029356990162688&type=Knowledge&productLineId=1&lang=zh-CN';
const TIMEOUT_MS = 10 * 60 * 1000; // 10分钟
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

  // 监听所有请求里的 login 跳转与最终落地 URL
  console.log('[2] 打开金蝶专题页...');
  await page.goto(SPECIAL_URL, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => {
    console.log('  页面加载提示:', e.message.split('\n')[0]);
  });
  await page.waitForTimeout(5000);

  const deadline = Date.now() + TIMEOUT_MS;
  let ok = false;
  console.log('[3] 等待登录 (窗口内若显示登录页,请完成登录)...');
  let lastUrl = '';
  while (Date.now() < deadline) {
    const url = page.url();
    // 登录成功标志: 离开 login 类页面 & 回到 vip.kingdee.com 域名 & 非登录路径
    const onVip = url.startsWith('https://vip.kingdee.com');
    const notLogin = !/login|passport|sso|signin|oauth/i.test(url);
    if (onVip && notLogin) {
      // 再确认一下正文区是否可读: 简单判断页面有没有明显正文容器
      const hasContent = await page.evaluate(() => {
        const t = document.body ? document.body.innerText : '';
        return t.length > 200;
      }).catch(() => false);
      if (hasContent) { ok = true; break; }
    }
    if (url !== lastUrl) {
      console.log('  当前URL:', url.slice(0, 110));
      lastUrl = url;
    }
    await page.waitForTimeout(POLL_MS);
  }

  if (!ok) {
    console.error('TIMEOUT: 10分钟内未检测到登录完成');
    await browser.close();
    process.exit(1);
  }

  await context.storageState({ path: STATE_PATH });
  console.log('[4] 登录成功! URL:', page.url().slice(0, 110));
  console.log('[5] storageState 已保存 ->', STATE_PATH);
  await page.waitForTimeout(1000);
  await browser.close();
  console.log('[6] DONE 窗口已关闭');
})().catch(e => {
  console.error('LOGIN CAPTURE FAILED:', e.message);
  process.exit(1);
});
