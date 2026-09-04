// 抓取金蝶 vip.kingdee.com OpenAPI 专题所有 14 篇正文
// 使用(二选一):
//   A) node fetch-articles.js                       —— 自动读取同目录 storage-state.json (Playwright 登录态, 推荐)
//   B) KINGDEE_COOKIE='kdsession=xxxxx; ......' node fetch-articles.js [outDir]
//
// 不带登录态跑的话会 302 到登录页,这样写只为了明确告诉执行者带登录态才能成功。

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

const MANIFEST = path.resolve(__dirname, 'manifest.json');
const BASE = 'https://vip.kingdee.com';
const OUT = process.argv[2] || path.resolve(__dirname, '../articles');

if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });

// 登录态来源优先级: 环境变量 KINGDEE_COOKIE > storage-state.json
let COOKIE = process.env.KINGDEE_COOKIE || '';
if (!COOKIE) {
  const STATE_FILE = path.join(__dirname, 'storage-state.json');
  if (fs.existsSync(STATE_FILE)) {
    try {
      const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
      const parts = (state.cookies || []).map(c => c.name + '=' + c.value);
      if (parts.length) {
        COOKIE = parts.join('; ');
        console.log(`已从 storage-state.json 载入 ${parts.length} 条 cookie`);
      }
    } catch (e) {
      console.warn('storage-state.json 解析失败:', e.message);
    }
  }
}
if (!COOKIE) {
  console.error('未找到登录态: 请先运行 node kingdee-login.js 生成 storage-state.json,');
  console.error('或提供 KINGDEE_COOKIE 环境变量。文章 API 不会返回正文。');
}

const articles = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));

function getJson(p) {
  return new Promise((res, rej) => {
    const url = BASE + p;
    const lib = url.startsWith('https') ? https : http;
    lib.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        Accept: 'application/json, text/plain, */*',
        Cookie: COOKIE,
        Referer: 'https://vip.kingdee.com/knowledge/specialDetail/229961573895771136',
        'Accept-Language': 'zh-CN,zh;q=0.9',
      },
    }, r => {
      let buf = '';
      r.setEncoding('utf8');
      r.on('data', c => buf += c);
      r.on('end', () => res({ status: r.statusCode, headers: r.headers, body: buf }));
    }).on('error', rej);
  });
}

function filenameSafe(s) {
  return s.replace(/[\\\/:*?"<>|\n\r\t]/g, '_').slice(0, 80);
}

(async () => {
  const report = [];
  for (const a of articles) {
    // 实测端点(2026-09-03 经浏览器监听确认): GET /knowledgeapi/special-knowledges/{articleId}
    // 返回 JSON, content 字段为 HTML 正文; 旧路径 /api/knowledge/... 全部 404
    const tries = [
      `/knowledgeapi/special-knowledges/${a.articleId}`,
    ];
    let hit = null;
    for (const p of tries) {
      const r = await getJson(p);
      if (r.status === 200) { hit = { p, body: r.body, ct: r.headers['content-type'] }; break; }
      report.push({ name: a.name, attempt: p, status: r.status, note: r.body.slice(0, 200) });
      if (r.status !== 404) break;
    }
    if (hit) {
      const data = JSON.parse(hit.body);
      const outFile = path.join(OUT, filenameSafe(a.cat + '-' + a.name) + '.json');
      fs.writeFileSync(outFile, JSON.stringify({
        categoryId: a.categoryId,
        articleId: a.articleId,
        category: a.cat,
        title: a.name,
        source: BASE + '/knowledge/specialDetail/229961573895771136?category=' + a.categoryId + '&id=' + a.articleId + '&type=Knowledge&productLineId=1&lang=zh-CN',
        fetchedAt: new Date().toISOString(),
        rawResponse: data,
      }, null, 2));
      console.log('OK', a.cat + '/' + a.name, '->', outFile);
    } else {
      console.error('FAIL', a.cat + '/' + a.name);
    }
  }
  fs.writeFileSync(path.join(OUT, '..', 'fetch-report.json'), JSON.stringify(report, null, 2));
  console.log('\nReport entries:', report.length);
})();
