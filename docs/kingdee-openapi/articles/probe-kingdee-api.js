// 捕获金蝶详情页真实 API 请求 (用于确定正文端点真实路径)
// 用法: node probe-kingdee-api.js
const { chromium } = require('playwright');
const path = require('path');

const STATE_PATH = path.join(__dirname, 'storage-state.json');
// 第一篇文章的详情页 URL
const ARTICLE_URL = 'https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963348454780672&id=298029356990162688&type=Knowledge&productLineId=1&lang=zh-CN';

(async () => {
  console.log('[1] 启动无头 Edge + storageState...');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext({ storageState: STATE_PATH });
  const page = await context.newPage();

  // 监听所有 XHR/fetch 请求
  page.on('request', req => {
    const t = req.resourceType();
    if (t === 'xhr' || t === 'fetch') {
      console.log('  [REQ]', req.method(), req.url());
    }
  });
  page.on('response', async res => {
    const t = res.request().resourceType();
    if (t === 'xhr' || t === 'fetch' && res.status() < 400) {
      const ct = res.headers()['content-type'] || '';
      if (ct.includes('json')) {
        let body = '';
        try { body = (await res.text()).slice(0, 300); } catch (e) {}
        console.log('  [RES]', res.status(), res.url().slice(0, 120), '=>', body.replace(/\n/g, ' ').slice(0, 200));
      }
    }
  });

  console.log('[2] 打开文章详情页...');
  await page.goto(ARTICLE_URL, { waitUntil: 'networkidle', timeout: 60000 }).catch(e => {
    console.log('  goto warn:', e.message.split('\n')[0].slice(0, 100));
  });
  await page.waitForTimeout(8000);

  // 打印页面标题与正文片段
  const info = await page.evaluate(() => {
    const title = document.title;
    const text = (document.body ? document.body.innerText : '').slice(0, 600);
    return { title, text };
  });
  console.log('[3] page title:', info.title);
  console.log('[4] body head:', info.text.replace(/\n+/g, ' | ').slice(0, 500));

  await browser.close();
  console.log('[5] DONE');
})().catch(e => {
  console.error('PROBE FAILED:', e.message);
  process.exit(1);
});
