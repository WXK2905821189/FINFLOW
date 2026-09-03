// 招行云直连文档: 监听浏览器真实请求, 确认正文/PDF 获取链路
const { chromium } = require('playwright');
const path = require('path');

const URL = 'https://openbiz.cmbchina.com/clouddc-document?bizkey=DCCT20201215110811035&subclass=1&treeID=2026070917153782128';

(async () => {
  console.log('[1] 启动无头 Edge...');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  page.on('request', req => {
    const t = req.resourceType();
    if (t === 'xhr' || t === 'fetch') {
      let body = '';
      if (req.method() === 'POST') body = ' ' + (req.postData() || '').slice(0, 300);
      console.log('  [REQ]', req.method(), req.url().slice(0, 180) + body);
    }
  });
  page.on('response', async res => {
    const t = res.request().resourceType();
    if ((t === 'xhr' || t === 'fetch') && res.status() < 400) {
      const ct = res.headers()['content-type'] || '';
      if (ct.includes('json')) {
        try {
          const txt = await res.text();
          console.log('  [RES]', res.status(), res.url().slice(0, 140), '=>', txt.slice(0, 400).replace(/\n/g, ' '));
        } catch (e) {}
      }
    }
  });

  console.log('[2] 打开文档页...');
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(e => console.log('  goto:', e.message.split('\n')[0].slice(0, 100)));
  await page.waitForTimeout(12000);

  const info = await page.evaluate(() => {
    const t = document.body ? document.body.innerText : '';
    return { title: document.title, len: t.length, head: t.slice(0, 500) };
  });
  console.log('[3] title:', info.title, '| body len:', info.len);
  console.log('[4] body head:', info.head.replace(/\n+/g, ' | ').slice(0, 400));

  await browser.close();
  console.log('[5] DONE');
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
