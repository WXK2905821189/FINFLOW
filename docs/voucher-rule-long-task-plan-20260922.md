# 制证规则化长任务规划（2026-09-22）

> **目标**：银行流水制证**基本完全按规则中心规则**走——勾选流水 → 一键推送 → 规则引擎匹配 → 唯一命中自动推金蝶，其余落问题凭证由 A2 编辑器人工处理。
> **本文档自包含**：后续会话不依赖本对话上下文，凭本文档 + 工作区记忆即可接力执行。
> **执行模式**：AI 自主跑 / 测 / 改 / 修，跨会话接力；每阶段结束向用户汇报一次。

---

## 0. 验收口径（Definition of Done）

| # | 验收项 | 判定方法 |
|---|--------|----------|
| D1 | 流水查询页只有「一键推送至金蝶」一个制证入口，AI 制证按钮/进度条/类型全部退役 | 前端 grep `ai-voucher` 仅剩样式类名或 0 命中 |
| D2 | 勾选流水推送后：AUTO_FILL 且无 MANUAL 行 → 自动 APPROVED + 金蝶草稿；其余 → PROBLEM_* 问题凭证 | 离线集成测试（H2）断言逐行 outcome |
| D3 | 重跑已失败/已撤回行**按当前规则表**重新匹配，不读 ai_suggestion_json | 集成测试：改规则后重跑，断言走新规则 |
| D4 | 问题凭证可在编辑器内改科目/金额/借贷方向/维度/摘要，借贷合计不等禁存，处理完自动出列 | 集成测试 + UI 点击测试 |
| D5 | 真账套端到端：≥1 条流水从勾选到金蝶草稿可见全链路成功 | 用户提供的测试账套实测 |
| D6 | CI 全绿（5 job），`mvn test -o` 本地 0 失败 | 本地 + CI 双确认 |

---

## 1. 现状快照（2026-09-22 19:15 核查）

- HEAD = `dc869ff`（W16b 部署报告）；flyway 线上 v42，**V43 已写好未上线**。
- **并行会话 A1 已暂存未提交**（staged）：`BankPushJob`(←AiVoucherJob 改名)、`BankPushJobMapper`、`BankDataPushController`、`BankDataPushService`（W16-A1 编排，类注释完整）、`BankPushJobService`、Push DTO 五件套、`AiGlVoucherAssembler` 已删（D）、`KingdeeVoucherEngineService` 已改（M）、`V43__bank_push_job_drop_ai_voucher_job.sql`。
- **已知编译缺口**：`KingdeeVoucherEngineService` 工作区版本仍引用已删除的 `AiGlVoucherAssembler` 5 处（L55/82/95/145/146）——并行会话半成品，**等其落地或明确交接后修**（见 §3 阶段 0）。
- **前端 A1 对接完全缺失**：`api.ts` 仍是 `ai-voucher` 三端点；`BankDataQueryPage.tsx` 仍有「正在提交制证任务」逻辑；`AiVoucherJobBanner.tsx` 未改造。
- **测试基础设施**：71 个测试文件今日事故中曾全部丢失，已从 HEAD 完整恢复（`mvn test -o` 可跑）。
- 规则引擎服务链齐全：`KingdeeVoucherMatchingService`（四态：AUTO_FILL/CANDIDATES/UNMATCHED/NOT_ELIGIBLE；预过滤→条件求值→优先级仲裁→金额分摊→维度解析）、`KingdeeVoucherEngineService.preview/push(ruleNo)`、`KingdeeGlVoucherPayloadBuilder`、`KingdeeOrgResolver`。

---

## 2. 自主工作循环协议（每阶段通用）

**跑**：后端 `mvn.cmd test -o -Djacoco.skip=true`（离线全量，基线 423 用例，用例数随批涨）；前端不跑本地构建（node_modules 空壳），语法自检用「git show 工作区 vs HEAD 差量括号平衡」脚本，类型/lint 交给 CI。

**测**：优先写离线集成测试（H2 + 默认 profile）锁行为；不测真金蝶（REAL 适配器须独立上下文，留到阶段 4）。判端点注册须带 token 打对照组（真端点 200/400/405，瞎编 500）。

**改**：每次只动一个主题；开工前 `git status --short` 记基线，收工 diff 复核；**写操作后必须断言影响行数 == 1**。

**提交**：攒批按主题分组（A1 编排 / A2 编辑器 / W16-B 查询体验各一提），不混提；并行会话文件只做 pathspec 限界提交。

**事故防护**（今日 417 文件消失事故教训）：
1. 开工先 `git status --short | head -50` 存快照；发现文件异常消失 → 先 `git stash list` + HEAD diff 定界，**不盲目 restore**（restore 是覆盖式，会抹掉未提交修改）。
2. `index.lock` 报错 = 保护机制：先 `tasklist | grep git` 确认无进程 + lock mtime 超 5 分钟才可删。
3. 每完成一个子任务立即 `git add` 该主题文件（暂存区即备份），不要攒到最后。
4. 改动文件 >5 个的批次，提交前 `git diff --stat` 逐文件确认无意外混入。

**回滚**：每阶段部署前打 `.bak` 包（jar / web-dist / compose 三件套，见 finflow-deploy skill）。

---

## 3. 阶段划分

### 阶段 0：A1 编排落地（等并行会话 + 编译修复）— 预计 0.5 个会话

| 任务 | 说明 |
|------|------|
| 0.1 等待/确认并行会话交接 | `KingdeeVoucherEngineService` 仍引用已删的 `AiGlVoucherAssembler`（编译必红）。**先查 `git log` 是否已有 A1 提交**；若并行会话已完成→直接进 0.2；若用户确认烂尾→由本会话接手清理 5 处引用（推送路径改走 `push(ruleNo)`，AI 组装路径删除） |
| 0.2 A1 后端离线测试 | 新增 `BankDataPushServiceIntegrationTest`：AUTO_FILL 无 MANUAL 行→自动 APPROVED+推送成功；CANDIDATES/UNMATCHED/NOT_ELIGIBLE/需人工金额→PROBLEM_*；已 PUSHED 行幂等跳过（already 计数）；MANUAL 账户/越权/驳回行跳过；写后影响行数断言 |
| 0.3 V43 台账同步 | **新增迁移才需要同步 ci.yml 6 处**；V43 是并行会话新增→核对 `expected_versions` 已含 V43（项数 == `ls V*.sql \| wc -l`），漏了就补 |

**完成标志**：`mvn test -o` 全绿 + A1 提交落库（含 V43 校验断言）。

### 阶段 1：A1 前端对接 — 预计 1 个会话

| 任务 | 说明 |
|------|------|
| 1.1 api.ts 切换 | 删 `aiVoucher` 三端点函数 + `AiVoucherSubmitResult/AiVoucherJobResult` 类型，新增 `pushToKingdee(ids)` / `latestPushJob()` / `pushJob(id)` + `PushSubmitResult/PushJobResult` 类型 |
| 1.2 查询页按钮改造 | `BankDataQueryPage.tsx`：「一键 AI 制证」→「一键推送至金蝶」（权限 `voucher:push`），复用后台任务轮询模式（提交→loading→跳凭证中心或原地 banner） |
| 1.3 凭证中心 banner | `AiVoucherJobBanner.tsx` → `PushJobBanner.tsx`：进度 + 四计数（pushed/problem/skipped/already）+ 逐行结果表 |
| 1.4 AI 残留清扫 | grep `ai-voucher|aiVoucher|AiVoucher`：删路由/常量/提示文案；「AI 制证入口」提示（UNMATCHED 文案里）改为「落入问题凭证」 |
| 1.5 类型自检 | 差量括号脚本 + CI tsc/eslint 兜底 |

**完成标志**：前端 grep AI 制证仅剩无害残留；CI 前端 job 绿。

### 阶段 2：A2 问题凭证编辑器 — 预计 1.5 个会话（核心增量）

| 任务 | 说明 |
|------|------|
| 2.1 端点设计 | 凭证中心问题凭证行 → `GET /api/vouchers/problems`（分桶复用 6 桶口径）/ `GET .../{id}`（草稿+可编辑字段）/ `PUT .../{id}`（保存并重校验）/ `POST .../{id}/submit`（修复完成→重走借贷合计校验→推送金蝶→自动出列） |
| 2.2 可编辑域 | 科目（明细科目 catalog 下拉，父科目/空科目禁选——金蝶 `FIsDetail=false` 不能记账）、金额（MANUAL 行必填）、借贷方向、核算维度（槽位/值映射界面可改，复用 V42 两表）、摘要/附言放开 |
| 2.3 校验 | 借贷合计不等禁存（前端禁按钮 + 后端 400 双保险）；科目必明细；维度必录校验按账套 `QueryBusinessInfo` 口径 |
| 2.4 出列语义 | 修复推送成功 → `push_status=GL_PUSHED`，从问题桶消失；失败 → 留桶并更新失败原因（写 review_comment） |
| 2.5 测试 | 集成测试锁 2.3/2.4 全部分支；UI 点击测试补 checklist |

**完成标志**：D4 验收项达成；问题凭证闭环（落桶→修复→出列）集成测试全绿。

### 阶段 3：规则覆盖度实测与补全 — 预计 1 个会话 + 用户资源

| 任务 | 说明 |
|------|------|
| 3.1 覆盖度统计脚本 | 只读 SQL（SSH 直连 RDS，不踢会话）：对近 N 天流水逐条跑规则匹配口径，输出四态分布 + UNMATCHED 行明细（对手方/摘要 top 频次表） |
| 3.2 规则补全建议清单 | 按 UNMATCHED 明细给出「建议新增规则」列表（含建议科目/维度/优先级），交用户确认后在规则中心录入 |
| 3.3 金额阈值补全 | 社保/个税同附言冲突对：财务给 amount_min/max 后验证「剩余唯一命中自动预填」生效 |
| 3.4 组织映射字典化评估 | `KingdeeOrgResolver` 内置常量 → 新增主体要改代码；评估是否本轮字典化（最高价值项，可顺带做） |

**依赖用户**：①覆盖度实测授权（跑线上只读 SQL 的窗口）②财务提供金额阈值 ③新主体清单（若做 3.4）。

**完成标志**：UNMATCHED 占比降到用户可接受线（如 <20%）；补全规则录入并复测。

### 阶段 4：端到端真账套验收 — 预计 0.5 个会话

| 任务 | 说明 |
|------|------|
| 4.1 预发验证 | 打包部署（finflow-deploy skill 三层门禁）→ preview 只读核对 V43 迁移与端点注册 |
| 4.2 真账套闭环 | 用户测试账套：勾选→一键推送→金蝶侧草稿可见→问题凭证修复→再推送成功 |
| 4.3 验收报告 | W8 风格部署报告 + D1–D6 逐项打勾 |

**依赖用户**：金蝶测试账套可用 + 验收时段。

### 阶段 5：C 批杂项收尾 — 穿插执行

- 王一霏加同步任务修改权限（待账号名）；
- 公司主体支持删除（软删 + 引用检查：账户/流水/规则占用者禁删）；
- W16-B 9 文件（前端 6 + 后端 3）随批次 A 一起攒批提交。

---

## 4. 挂起的用户资源（阻塞点汇总）

| # | 资源 | 阻塞阶段 |
|---|------|----------|
| 1 | 并行会话 A1 交接确认（或确认由本线接手修编译） | 0 |
| 2 | 覆盖度实测授权（线上只读 SQL 窗口） | 3 |
| 3 | 财务金额阈值（社保/个税冲突对） | 3 |
| 4 | 金蝶测试账套 + 验收时段 | 4 |
| 5 | 王一霏账号名 | 5 |

阶段 0→1→2 之间无用户阻塞，**AI 可连续自主推进**。

---

## 5. 红线（执行中不可违反）

1. 主 chunk 禁 manualChunks（白屏红线）；构建体积只认 `vite build` 输出值（base-1000）。
2. JSON 端点必须 `ApiResponse.success(...)`；裸数组/裸 Map 前端判失败。
3. Flyway 历史不可改；新增迁移 N 同步 ci.yml 6 处并核对项数。
4. 任何写操作后断言影响行数；「假成功」是本项目头号事故源。
5. 并行会话文件不碰，只做 pathspec 限界提交；`git restore` 前必先 diff 定界。
6. 凭据即用即删，严禁上传；SSH 密码登录、GitHub 走 `id_ed25519_finflow`。
