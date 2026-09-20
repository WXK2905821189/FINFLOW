# FINFLOW W10 批次 B 部署报告（2026-09-20）

## 部署内容

**提交 `f32f466`（W10 批次 B：WP-5 规则注入 AI / WP-6 两页迁 Excel 内核 / WP-7 分组侧边栏拖拽 + PRD v0.7）**，CI run `35498711139` 五 job 全绿。

| WP | 反馈原文 | 实现 |
|---|---|---|
| WP-5 | 「AI 制证的提示词好像没和规则中心联动」 | AI 制证前先跑**服务端规则引擎**（复用 V34 的 `KingdeeVoucherMatchingService`）：命中规则的分录行作为**强约束**注入提示词，系统提示词要求模型原样采用规则科目并在 rationale 注明命中规则号；未命中才纯 AI 判断。响应新增 `hitRules`，前端刷新建议时提示「按命中规则生成（R2 xx）」。规则引擎异常自动降级为纯 AI（不阻断制证链路） |
| WP-6 | 「规则中心和凭证中心也做成 Excel 内核吧」 | 两页由 antd Table 迁至 Excel 内核：列宽拖拽 / 本页排序 / 列头筛选 / TSV 复制 / 列显隐 / CSV 导出；行内操作用 `data-row-action` 回投页面；凭证中心操作列全实体按钮（撤回 danger）。新增两个列定义文件（`VoucherCenterGridColumns.ts` / `CategoryRulesGridColumns.ts`） |
| WP-7 | 「管理分组最好有个侧边栏，拖动规则进行分组」 | 分组由顶部按钮排改为**左侧侧边栏**（全部规则 / 未分组 / 各组 + 实时计数，点选筛选）；拖动规则行首「⠿」手柄到分组即换组（PUT 全量更新），拖到「未分组」可移出 |
| — | 「更新 PRD」 | PRD 升 v0.7「反馈闭环版」，19.4 补记 W10 两批次（含根因与验收证据） |

## 物料与执行

| 项 | 值 |
|---|---|
| jar | 隔离构建（`git archive f32f466 backend` → `/c/Users/Public/finflow-w10b`），MD5 `1fa565e5a7e86ce4854201251532bb70`，97.6MB，lib 95 jar / 96 条目；取证：`AccountingSuggestionService` / `KingdeeVoucherMatchingService` / `V39__statement_withdraw.sql` ✓ |
| dist | CI 构件 `finflow-web-dist`（run 35498711139），**72 assets**，入口 `index-CjBMeGPf.js`（与本地 vite build 同名 ✓），包 MD5 `30d54a3e489cc5ef01c42a808348be3f` |
| 执行 | 备份（`app.jar.bak-20260920-w10b-pre`=批次 A jar `c1e54897` ✓ + `web-dist.bak-20260920-w10b-pre.tar.gz`）→ 换 jar → 清 66 个旧 chunk → 原地解压（72=EXACT-MATCH）→ `up -d --build app` → t+22s healthy |
| compose/.env | 零改动 |

## 验收（三层门禁全绿 + 页面级 API）

1. **门禁①** ✅：本机首页引用 `index-CjBMeGPf.js`；日志 `Successfully validated 38 migrations`（本批次无新迁移，V39 沿用）→ `Started in 10.968s`
2. **门禁②** ✅：`root=200` / `health=401` / `assets/index-CjBMeGPf.js=200`
3. **门禁③** ✅：Edge headless 公网首页 DOM 206KB、root 内 3939 字符非空（留证 `tmp/ui-click-test/out/w10b/dom-w10b.html`）
4. **缓存头 + 逐 chunk** ✅：index no-cache / assets immutable；**72 chunk 全部 200**
5. **页面级 API** ✅：`GET /api/kingdee/voucher-rules` code=0（22 条、分组「财务默认规则」）；`GET /api/statements/voucher-groups` code=0（首 3 行状态正常）
6. **本地 E2E 前置验证** ✅：凭证中心内核化 7 步 + 规则中心侧边栏/拖拽换组 5 步，共 12 步全 PASS（含「拖到未分组 → 标签变化 → 拖回恢复」实测）

> 本次线上验收**未做单点登录抽样**（避免踢掉在线用户会话）。

## 回滚

```bash
cd /opt/finflow
cp -f app.jar.bak-20260920-w10b-pre app.jar
rm -rf web-dist && mkdir web-dist && tar -xzf web-dist.bak-20260920-w10b-pre.tar.gz -C web-dist
docker compose up -d --build app
```

> 本批次无新迁移（V39 已在批次 A 上线），回滚只涉及 jar 与 dist。

## 遗留与提示

- **规则注入的实际效果**取决于规则覆盖度：命中规则时 AI 必须采用规则分录（建议里 rationale 会写「命中规则 R#」）；无规则命中仍是纯 AI 判断。
- **拖拽换组**写的是 PUT 全量更新，payload 只覆盖 groupId，其余字段照抄行内现值（不会误清字段）。
- 规则中心的 Excel 导入向导仍为 antd 实现（向导内临时表格，不迁内核）。
