# FINFLOW W10 批次 A 部署报告（2026-09-20）

## 部署内容

**提交 `eaf355c`（W10 批次 A：WP-1 勾选体验 / WP-2 AI 可用性 / WP-3 凭证撤回 / WP-4 单点登录）**，CI run `35496403211` 五 job 全绿。

| WP | 反馈原文 | 修复 |
|---|---|---|
| WP-2 | 「AI 生成凭证时，AI 仍然提示不可用」 | **根因 = max_tokens=512 截断**（非服务不通）：线上 call-logs 显示 accounting-suggestion 的 completionTokens 多次正好卡 512/511，含 entries 分录的中文 JSON 被砍断 → 半截 JSON 解析失败 → 降级标「AI 建议不可用」。修：AI 制证 512→2048、公司分类 2048→4096、gateway 增加 `finish_reason=length` 截断检测（从静默成功改为明确 502 + 诊断） |
| WP-1 | 「点单元格自动勾选；全选第二次无法清除；切模块回来复选框消失；关键字无匹配显示加载中」 | ①全选 toggle 口径改为「可选行」（旧口径含禁选行时永不成立）②单击单元格=toggle 勾选、拖动=框选复制 ③复选框列改实时求值（selectable 原为挂载快照，权限异步就绪后列永不出现 → 须 Ctrl+F5）④空态文案改实时同步（emptyText 原为挂载快照，首屏加载期挂载会永久锁成「正在加载……」）⑤关键字无匹配显示「没有匹配「xxx」的银行数据。」 |
| WP-3 | 「有些流水进了凭证中心之后能否撤回」 | V39 迁移 + `POST /api/statements/{id}/withdraw`：未成功推送金蝶（push_status ∉ {PUSHED, GL_PUSHED}）可撤；撤回=review_status=WITHDRAWN + withdrawn_at/by 落库 + 审计 WITHDRAW，记录与凭证号保留；银行数据层 transferred 判定排除 WITHDRAWN → **流水回池可重新制证**；凭证中心「撤回」按钮+确认弹窗 |
| WP-3b | 「凭证中心操作下面的几个操作做成按钮」 | 操作列由 link 样式改为实体按钮（size=small，撤回为 danger），列宽 250→350 |
| WP-4 | 「检查用户与角色模块，同一账号不可同时登录」 | 登录成功即撤销该用户其他活跃会话（新踢旧），审计带 kickedSessions；被踢端 401 → 跳登录页，提示文案改为「会话超时，或该账号已在其他终端登录」 |

## 物料与执行

| 项 | 值 |
|---|---|
| jar | 隔离构建（`git archive eaf355c backend` → `/c/Users/Public/finflow-w10`），MD5 `c1e54897703e528bb85acfcf3424e69a`，97.6MB，lib 95 jar / 96 条目 |
| dist | CI 构件 `finflow-web-dist`（run 35496403211），67 assets，入口 `index-Om5AHTgN.js`，包 MD5 `4acc9cfcf3dfa1be4f7ce14031665925`；**本地 vite build 入口同为 index-Om5AHTgN.js → 构建可复现强证链恢复** |
| 执行 | 备份（`app.jar.bak-20260920-w10-pre` + `web-dist.bak-20260920-w10-pre.tar.gz`）→ 换 jar → 清 64 个旧 chunk → 原地解压（67=干净态）→ `up -d --build app` → t+22s healthy |
| compose/.env | 零改动（.env 36 行开关与 W9 基线一致） |

**jar 取证**：`V39__statement_withdraw.sql` ✓、`OpenAiCompatibleLlmGateway.class` / `StatementService.class` / `AuthSessionService.class` ✓、lib 95/96 同口径 ✓。

**部署脚本断言修正（W9 遗留错误）**：原断言「新包清单必须 ⊆ 现盘」在本版**误拦**（新包 chunk hash 与旧包必然不同，64 个新文件本就不在现盘）。正确口径应为「①清单非空 ②现盘独有文件清除 ③解压后集合与新包清单完全一致」。已按此修正并加 `ASSETS-EXACT-MATCH` 断言，同时更新 skill。

## 验收（三层门禁全绿 + API + 单点登录实测）

1. **门禁①** ✅：本机首页引用 `index-Om5AHTgN.js`；迁移日志 `Successfully validated 38 migrations` → `Migrating schema finflow to version "39 - statement withdraw"` → `Successfully applied 1 migration, now at v39` → `Started in 9.999s`
2. **门禁②** ✅：`root=200` / `health=401` / `assets/index-Om5AHTgN.js=200`
3. **门禁③** ✅：Edge headless 公网首页 DOM 206KB、`<div id="root">` 内 3939 字符非空（留证 `tmp/ui-click-test/out/w10/dom-w10.html`）
4. **缓存头** ✅：index no-cache / assets immutable / SPA 回退带头；**67 chunk 全部 200**
5. **API 轻验** ✅：`GET /api/statements` code=0；瞎编路径带 token=500（对照组）
6. **单点登录线上实测** ✅：两次登录同账号 → 旧 token `401`、新 token `200`（线上真实生效）

## 回滚

```bash
cd /opt/finflow
cp -f app.jar.bak-20260920-w10-pre app.jar
rm -rf web-dist && mkdir web-dist && tar -xzf web-dist.bak-20260920-w10-pre.tar.gz -C web-dist
docker compose up -d --build app
```

> 注：V39 迁移为新增两列（withdrawn_at/withdrawn_by），回滚旧 jar 后列留存无害（旧代码不读）；已撤回的凭证在旧代码下 review_status=WITHDRAWN 会落进默认分支显示原文，不影响主流程。

## 遗留与提示

- **单点登录已生效**：部署验收时做了线上抽样，会踢掉当时在线的 admin 会话——如被踢，刷新页面重新登录即可（提示文案已说明原因）。
- **AI 可用性验证**：max_tokens 修复需用真实流水跑一次「AI 制证为草稿」确认不再出现「AI 建议不可用」；若仍失败，`ai_call_logs` 现在会给出明确截断诊断（`finish_reason=length`）。
- **批次 B 待办**：WP-5 规则注入 AI 制证 / WP-6 规则中心+凭证中心迁 Excel 内核 / WP-7 规则分组侧边栏+拖拽分组。
