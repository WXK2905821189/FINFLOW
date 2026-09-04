// 招行云直联文档中心: 按 bizkey 拉取产品文档树全部节点正文 (免登录)
// 用法: node fetch-cmb-docs.js <bizkey> [outDir]
// 端点(2026-09-03 实测):
//   目录树  POST /fbdev/v2/public/document-center/document-catalog/find-catalogs {bizkey}
//   正文    POST /fbdev/v2/public/document-center/document-catalog/contents {nwcdls:[...]}
const fs = require('fs');
const path = require('path');
const https = require('https');

const BIZKEY = process.argv[2] || 'DCCT20201215110811035'; // 开通设置
const OUT = process.argv[3] || path.join(__dirname, '..', 'cmb-clouddc', 'raw');
const BASE = 'https://openbiz.cmbchina.com';

if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });

function post(p, data) {
  return new Promise((res, rej) => {
    const body = JSON.stringify(data);
    const req = https.request(BASE + p, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': 'application/json, text/plain, */*',
        'Origin': 'https://openbiz.cmbchina.com',
        'Referer': BASE + '/clouddc-document',
      },
    }, r => {
      let buf = '';
      r.setEncoding('utf8');
      r.on('data', c => buf += c);
      r.on('end', () => {
        try { res({ status: r.statusCode, json: JSON.parse(buf) }); }
        catch (e) { rej(new Error('bad json: ' + buf.slice(0, 200))); }
      });
    });
    req.on('error', rej);
    req.write(body);
    req.end();
  });
}

function safe(s) { return s.replace(/[\\\/:*?"<>|\n\r\t]/g, '_').slice(0, 60); }

(async () => {
  // 1. 目录树
  const cat = await post('/fbdev/v2/public/document-center/document-catalog/find-catalogs', { bizkey: BIZKEY });
  const trcifs = cat.json.infBdy.trcifs;
  const meta = { bizkey: BIZKEY, dctitl: cat.json.infBdy.dctitl, modytm: cat.json.infBdy.modytm, nodes: trcifs.length };
  console.log(`目录: ${meta.dctitl} (${meta.modytm}) 共 ${meta.nodes} 节点`);

  // 2. 全量正文(一次性传全部 nwcdls)
  const ids = trcifs.map(n => n.nowdcd);
  const ctn = await post('/fbdev/v2/public/document-center/document-catalog/contents', { nwcdls: ids });
  const pageList = ctn.json.infBdy.pageList;
  console.log('正文返回:', pageList.length, '篇');

  // 3. 落盘: 每节点一个 json (含 HTML), 目录树本身单独存
  fs.writeFileSync(path.join(OUT, '_catalog-' + BIZKEY + '.json'), JSON.stringify({ fetchedAt: new Date().toISOString(), meta, tree: trcifs }, null, 2));

  const byId = {};
  for (const n of trcifs) byId[n.nowdcd] = n;
  let ok = 0, empty = 0;
  for (const pg of pageList) {
    const node = byId[pg.nowdcd] || { dctitl: pg.nowdcd };
    const fname = safe((node.dctitl || pg.nowdcd)) + '.json';
    fs.writeFileSync(path.join(OUT, fname), JSON.stringify({
      bizkey: BIZKEY, nowdcd: pg.nowdcd, fathid: node.fathid, title: node.dctitl,
      fetchedAt: new Date().toISOString(), html: pg.dccicn || '',
    }, null, 2));
    const htmlLen = (pg.dccicn || '').length;
    if (htmlLen > 100) ok++; else empty++;
    console.log(`  [${htmlLen > 100 ? 'OK' : 'EMPTY'}] ${node.dctitl} (${htmlLen}字符)`);
  }
  console.log(`\n完成: 有正文 ${ok}/${pageList.length}, 空 ${empty}`);
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
