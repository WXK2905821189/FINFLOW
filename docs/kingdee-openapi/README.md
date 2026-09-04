# 金蝶云星空 OpenAPI 专题 · 抓取实施报告

> 抓取目标：https://vip.kingdee.com/knowledge/specialDetail/229961573895771136 共 14 篇 OpenAPI 教程
> 抓取日期：2026-09-03
> 报告状态：**✅ 已完成 — 14/14 全部抓到并转 Markdown，离线可用**

---

## 1. 最终成果

### 1.1 目录结构

```
docs/kingdee-openapi/
├── README.md                        ← 本报告
├── articles-index.md                ← 14 篇离线 Markdown 索引（按 5 大分类）
├── fetch-report.json                ← 抓取状态（completed, 14/14 ok）
├── articles/                        ← 脚本 + 原始 JSON + 登录态
│   ├── manifest.json                ← 14 篇 (categoryId, articleId) 全映射
│   ├── fetch-articles.js            ← 抓取脚本（读 storage-state.json 自动带登录态）
│   ├── kingdee-login.js             ← Playwright 有头 Edge 登录捕获脚本
│   ├── probe-kingdee-api.js         ← 浏览器请求监听探针（找真实端点用）
│   ├── storage-state.json           ← ⚠️ 登录态（19 条 cookie，含 SSO，勿外传/勿进 git）
│   ├── <分类>-<标题>.json ×14       ← 原始 API 响应（含完整 HTML 正文）
│   └── sample-special-knowledge.json
├── articles-md/                     ← ✅ 14 篇离线 Markdown（可直接阅读）
│   ├── 整体介绍-金蝶云星空OpenAPI介绍.md
│   ├── 快速入门-*.md ×5
│   ├── 进阶开发-*.md ×3
│   ├── 实战示例-*.md ×1
│   └── 常见问题-*.md ×4
└── raw/                             ← SPA HTML + Nuxt bundle 快照
    ├── special-page.html (444 KB)
    └── nuxt-app.js (3.6 MB)
```

### 1.2 正文内容统计

| 分类 | 篇数 | 代表内容 |
|---|---|---|
| 整体介绍 | 1 | OpenAPI 定位、18 标准接口 + 1 自定义接口、SDK 三语言 |
| 快速入门 | 5 | 快速入门、第三方登录授权、SDK 使用、在线测试、WebApi 日志 |
| 进阶开发 | 3 | 附件上传下载（分块）、自定义 API、SDK 进阶 |
| 实战示例 | 1 | 非配置文件方式配置第三方授权 |
| 常见问题 | 4 | 会话丢失、非网关请求、403 N001、Newtonsoft.Json 加载失败 |

合计 HTML 约 9 万字符，14 篇 Markdown 均已转出，含代码块与图片链接（已补全为绝对 URL）。

---

## 2. 关键技术结论（留给后续维护者）

### 2.1 真实正文端点（踩坑记录）

```
✅ GET https://vip.kingdee.com/knowledgeapi/special-knowledges/{articleId}
   - 无 /api 前缀！网关直接挂 /knowledgeapi
   - 返回 JSON，正文 HTML 在 content 字段
   - 需登录 cookie，否则 404/302

❌ 之前的错误结论
   /api/knowledge/{entityType}/{entityId}   → 404 "No endpoint"
   /api/knowledge/Knowledge/{id}            → 404
   /knowledgeapi/knowledge/Knowledge/{id}   → 404 (格式不同)
```

**发现过程**：逆向 Nuxt bundle 里 `KNOWLEDGE_DETAIL` 常量指向 `/knowledge/{entityType}/{entityId}` 且埋点正则含 `knowledgeapi` 前缀，但直接猜 URL 全部 404。最终靠 **Playwright 打开详情页 + 监听浏览器真实发出的 XHR**，看到页面自己请求了 `/knowledgeapi/special-knowledges/{id}` 才确定——逆向只能给方向，真实端点以浏览器请求为准。

### 2.2 登录态方案（Playwright + 系统 Edge）

agent-browser 方案因 Chromium CDN 被墙 + daemon 崩溃弃用。改用 **Playwright 直接驱动系统 Edge**：

```bash
# 1. 一次性: 有头 Edge 弹窗 -> 小棵手动登录 -> 自动存 storage-state.json
node articles/kingdee-login.js

# 2. 抓取(自动读 storage-state.json 的 cookie)
node articles/fetch-articles.js
```

优势：`channel: 'msedge'` 零下载、无 daemon、纯 Node API。

---

## 3. 完成状态看板

| 阶段 | 状态 |
|---|---|
| 整体结构逆向（manifest 14 篇） | ✅ 100% |
| 真实端点确认 | ✅ `/knowledgeapi/special-knowledges/{articleId}` |
| 登录态捕获 | ✅ Playwright storageState |
| 正文抓取 | ✅ 14/14 OK |
| HTML → Markdown 转换 | ✅ 14/14 |
| 索引 articles-index.md | ✅ 已更新 |

## 4. 后续建议（如需要）

1. **图片本地化**：14 篇含约 70+ 张 `/download/*.png` 截图，当前 Markdown 引用的是在线绝对 URL（需登录态看图）。若要完全离线，可再跑一次批量下载（`https://vip.kingdee.com/download/{hash}.png` + 登录 cookie）。
2. **沉淀知识库**：把文章要点增量合并进 `docs/kingdee-openapi-knowledge-base.md`（原 191 行底稿）。
3. **清理敏感文件**：`articles/storage-state.json` 含金蝶 SSO 会话 cookie——确认抓取完成后可删除，或确保不进 git。

---
