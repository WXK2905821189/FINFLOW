# FINFLOW W9 部署报告（2026-09-20）

## 部署内容

**提交 `c7fb2d4`（W9：用户反馈五项）**，CI run `35486884072` 五 job 全绿。

| # | 反馈 | 修复 |
|---|------|------|
| 1 | 用户名不设字符限制（两个字符的名字建不了） | 前端 rules 改 required+max:64；后端 `@Size(min=3,max=64)` → NotBlank+max64（UserUpsertRequest / RegisterRequest） |
| 2 | 不需要全量合计（一页中可能重复计算） | 服务端 SUM 聚合 + DTO totals + 前端合计条 + 内核 totalAgg 全链路退役；BankDataProjectionPageResponse 还原 10 参 record |
| 3 | 流水查询要能选中整行（小空白框），以便 AI 制证 | 勾选功能 W8 已上线；本轮视觉强化：复选框 16px、边框 #9fb3b3 1.5px、hover brand 色、全选框 title 提示。禁选行为保持：已转入行 / MANUAL 账户行不可选，表头全选自动跳过 |
| 4 | 所有 AI 能力处都要有设置提示词按钮，且可重置 | V38 迁移 `ai_prompt_override`（capability UNIQUE + system_prompt）；AiPromptCatalog 登记 3 能力（智能入账建议/公司分类/规则导入映射）；`AiPromptService.resolve` 覆盖优先回落默认；GET/PUT/DELETE `/api/ai/prompts` 全部 `ai:config`（仅超管）；PromptSettingButton 接入流水工具栏/档案抽屉/规则中心/AI 设置页×3；审计 AI_PROMPT_SAVE/AI_PROMPT_RESET |
| 5 | 更新 PRD + 下一阶段方案 | PRD 升 v0.6：18.6 提示词配置体系、19 章 W7~W9 迭代记录、20 章路线图（P1 稳定性收尾 / P2 AI 深化 / P3 数据报表 / P4 外部集成，各带验收口径） |

## 物料与执行

| 项 | 值 |
|---|---|
| jar | 隔离构建（`git archive c7fb2d4 backend` → `/c/Users/Public/finflow-w9`），MD5 `1d3edec31c7dfb1050b409845c49097f`，98M / 751 files，lib 95 jar / 96 条目（84 基础+POI 系 11+目录项） |
| dist | CI 构件 `finflow-web-dist`（run 35486884072），67 assets，入口 `index-DumHYUMK.js`，包 `tmp/dist-w9.tar.gz` MD5 `f5327d53b4214dbb44cb81264b981781` |
| 执行 | 备份（`app.jar.bak-20260920-w9-pre` + `web-dist.bak-20260920-w9-pre.tar.gz`）→ 换 jar → 清旧 chunk 65 个 → 原地解压（67=干净态）→ `up -d --build app` → t+20s healthy |
| compose/.env | 零改动（.env 36 行开关与 W8 基线一致，W9 无新增环境变量） |

**部署前基线**：线上=W8 版（jar MD5 `22a309b9`、入口 `index-QsnRc-RV.js`、65 assets、容器 healthy、备份链完整、磁盘 25G free、root=200/health=401），无影子部署。

> **插曲**：dist 包是 `tar -C dist-w9 .` 打的（条目带 `./` 前缀），部署脚本白名单 `grep '^assets/'` 落空致 keep=0，走了「清空全部旧 chunk」路径。因新包 hash 全变与旧包零同名，终态等价——已复核：现盘 67 assets 与新包清单 `ASSETS-EXACT-MATCH`、旧 W8 入口零残留。下版打包改用 `tar -czf pkg.tar.gz index.html assets`（不带 `./` 前缀）并让白名单断言拒绝空 keep。

**jar 取证**：`BOOT-INF/classes/db/migration/V38__ai_prompt_override.sql` ✓；`AiPromptCatalog/AiPromptService/AiPromptOverride/AiPromptOverrideMapper` class ✓；`citicbank-sdk.properties` + `CiticBankSdkClient` + `statement/kingdee/real/` ✓（SDK 以 class+properties 形态在 classes，非独立 jar）。

**dist 交叉校验说明**：本地 vite build 入口为 `index-DYQ3pVBa.js`，与 CI `index-DumHYUMK.js` 不同，但两包 assets 均为 67、index.html 除 hash 外完全同构——同源码产物，hash 漂移来自本地 node_modules 与 CI lockfile 解析差异。**交付以 CI 构件为准**（复现性强证链需本地依赖与 CI 对齐后才可用）。

## 验收（三层门禁全绿 + API 轻验）

1. **门禁①** ✅：ECS 本机 `curl 127.0.0.1` 首页引用 `index-DumHYUMK.js`；迁移日志 `Successfully validated 37 migrations` → `Migrating schema finflow to version "38 - ai prompt override"` → `Successfully applied 1 migration, now at v38` → `Started FinanceSystemApplication in 9.832s`
2. **门禁②** ✅：公网 `root=200` / `health=401`（未带 token 预期）/ `assets/index-DumHYUMK.js=200`
3. **门禁③** ✅：Edge headless 渲染公网首页，DOM 206KB、`<div id="root">` 内 3939 字符非空、登录页 2 个 `input` 渲染（留证 `tmp/ui-click-test/out/w9/dom-w9.html`）
4. **缓存头** ✅：`/index.html` → `no-cache, no-store, must-revalidate`；`/assets/<入口>` → `public, max-age=31536000, immutable`；SPA 回退 `/dashboard` → 200 + no-cache
5. **逐 chunk** ✅：67 个 assets 全部 200（懒加载 chunk 无一漏挂）
6. **API 轻验** ✅：admin 登录 → `GET /api/ai/prompts` code=0，3 能力目录完整（accounting-suggestion 智能入账建议 / company-classification 公司主体归类 / rule-import 规则导入映射，customized 全 False=默认态）——`ai_prompt_override` 表真实可读；瞎编路径带 token=500（对照组）、未登录=401，符合 SecurityConfig 预期

## 回滚

```bash
cd /opt/finflow
cp -f app.jar.bak-20260920-w9-pre app.jar
rm -rf web-dist && mkdir web-dist && tar -xzf web-dist.bak-20260920-w9-pre.tar.gz -C web-dist
docker compose up -d --build app
```

> 注：V38 迁移 `ai_prompt_override` 为新增独立表，回滚旧 jar 后该表留存无害（旧代码不读）；迁移历史按 Flyway 惯例不回退。

## 遗留提示

- **提示词权限**：仅 `ai:config`（超管）可见/可改；普通管理员看不到按钮。改坏可「恢复默认」一键重置。
- **验证建议**：①用户管理建 2 字符用户名（如「张三」）；②流水页看行首复选框（16px 可见）、勾选后 AI 制证按钮计数；③勾选含已转入行时全选只选中可选行（禁选行置灰）；④四处 AI 入口点「提示词」→ 改内容保存 → 再「恢复默认」；⑤AI 设置页 3 个提示词按钮。
