# W13 部署报告（撤回态复活 / 假成功修复 / 同步计划可见化 / 币种口径统一）

- **日期**：2026-09-21 18:32–18:36（GMT+8）
- **提交**：`81ebd90`（撤回态复活 + 0 行守卫 + 已撤回桶）→ `c4f2694`（同步计划可见化）→ `8e57f19`（币种口径统一）
- **CI**：run `35589206161` **5 job 全绿**
- **触发**：用户报三个问题（AI 制证成功但看不到 / 新建同步时间没启动 / 硬编码清单）。
  诊断见 `docs/issue-diagnosis-20260921.md`；用户拍板：问题 1 **三项全修**、问题 2 **方案 A**、问题 3 **先做币种**。

---

## 一、版本基线（部署前预检实测）

| 项 | 值 | 判据 |
|---|---|---|
| 线上 jar | `0aa72c0b8450e4f7ee9ac73a76a0c649` | = W12b 交付值 ⇒ 无影子部署 |
| 线上入口 | `assets/index-CoCcIVNd.js` / 69 assets | 同上 |
| flyway | `Validated 41` → `Current version: 42` | = V42（本批无迁移） |

## 二、上线内容

### 1. 问题 1：AI 制证「成功却看不到」（`81ebd90`）

| # | 改动 | 位置 |
|---|---|---|
| ① | **写入影响 0 行不再报成功**：原 update 写死 `.eq(reviewStatus, PENDING)`，对已撤回记录命中 0 行却仍返回 `DRAFT_CREATED`「草稿已生成」⇒ 现 `updated != 1` 即返回 **FAILED** 并写明当前状态 | `BankDataAccountingService.processRowAsDraft` |
| ② | **撤回态复活**：新增 `reviveWithdrawn()` —— 重新制证时把 WITHDRAWN 复位为待复核（清撤回痕迹/凭证号，push_status→NOT_PUSHED）并留 `STATEMENT_REVIVE` 审计，让 V39「撤回后回池可重新制证」真正成立 | 同上（DRAFT 与 PUSH 两条路径都接） |
| ③ | PUSH 路径补状态兜底：只允许 待复核/已复核 继续，其余明确失败（不再静默往下走） | `processRow` |
| ④ | **凭证中心补「已撤回」桶**；ALL 覆盖 WITHDRAWN + REJECTED（原先两者不在任何桶里 ⇒ REJECTED 不可见导致「重新打开」按钮不可达） | `VoucherGroupService` + 前端 `types.ts` / `voucherTexts.ts` |

回归测试 2 个：`withdrawnRecordIsRevivedOnRedraftInsteadOfSilentlySucceeding`、`withdrawnAndRejectedRowsAreVisibleAfterBucketFix`。

### 2. 问题 2：同步计划「没启动」（`c4f2694`，方案 A）

- 后端：`catch (RuntimeException ignored) {}` → **WARN 带账户/适配器/窗口/原因**（原先连 409「同账户同窗口已在跑」都静默吞掉）；扫描结束打 `scan done: accounts/dispatched/rejected/window`。
- 前端：计划卡片加说明（同一 T-1 窗口当天已同步过时不会重复拉取、同步任务里会出现「复用」记录、同一天多时刻只有首个真正产生新任务、补历史用「补拉历史数据」）；「下次执行」→「下次触发时刻」；修正页面描述里过时的「默认凌晨 02:10」。

### 3. 问题 3：币种中文名口径统一（`8e57f19`）

- 后端 `BankDataExportService.CURRENCY_TEXT` 补 `01` / `CNY`（原先只有 `10`）⇒ CSV 与屏幕一致。
- 删除前端 `grid/kernel.ts` 的死副本与其 `currencyText` 导出（全仓无引用）。

## 三、交付物与取证

| 项 | 值 |
|---|---|
| jar | MD5 `b949a0b60de16c2f6244e8669b498383`，102,486,081 B |
| jar 内取证 | 通用 **24/24 PASS**；本批新增 4 项 PASS：`STATEMENT_REVIVE` 审计动作、0 行守卫文案「草稿未写入」、`VoucherGroupService` 含 `WITHDRAWN` 常量、调度扫描日志串；反证对照组成立（该串未出现在 `BankDataExportService`） |
| 前端 | 入口 `index-C4MUBOJN.js` / **69 assets**（CI 构件） |
| 构件探针 | 「已撤回」命中 `VoucherCenterPage` / `voucherTexts`；「同一 T-1 窗口当天已同步过时不会重复拉取」「下次触发时刻」命中 `operations`；旧文案「下次执行：」0 命中 |

## 四、验收证据

| 层 | 结果 |
|---|---|
| ① 服务器内 | 入口 `index-C4MUBOJN.js`；白名单 **69/69 全 200** |
| ② 公网 | root 200 / `/api/actuator/health` 401 |
| ③ Edge headless | root 49 元素 / 136 字符可见文本，**无白屏** |
| 缓存头 | `/index.html` → `no-cache, no-store, must-revalidate`；`/assets/` → `immutable` |
| 反证 | 上一版入口 `index-CoCcIVNd.js` **404** |
| 启动 | `Started FinanceSystemApplication in 11.14 seconds`，容器 healthy |
| 备份先于替换 | `app.jar.bak-20260921-w13-pre`（MD5 = 基线 `0aa72c0b`）、备份包含旧入口 |

**真实数据验证（修复效果实测，只读查库）**：

| 口径 | 返回行数 |
|---|---|
| 修复前 ALL 桶（`voucher_no 非空 OR PENDING OR APPROVED`） | **0** |
| 修复后 ALL 桶（+ WITHDRAWN / REJECTED） | **16** |
| 新增「已撤回」桶（`review_status = WITHDRAWN`） | **11** |

⇒ 用户「AI 制证成功但凭证中心看不到」的量化解释：**线上 16 条标准流水全部落在旧桶之外**。

## 五、回滚弹药

`app.jar.bak-20260921-w13-pre`(`0aa72c0b`) / `app.jar.release-20260921-w13` /
`web-dist.bak-20260921-w13-pre.tar.gz`（含 `index-CoCcIVNd.js`）/ `web-dist-release-20260921-w13.tar.gz`。
**本批无迁移**，回滚不动数据库。

## 六、观察项

1. **调度新日志要等下一个计划时刻才会出现**：验收时 `bank sync schedule` 计数为 0，因为当前 18:35 不在计划时刻（06:35 / 15:45）上，属预期；下一个 06:35 应看到 `scan done` 行。
2. **历史 16 条 WITHDRAWN/REJECTED 记录**修复后可查，但其中 11 条已撤回的**不会再自动复活** —— 需要时在凭证中心「已撤回」签逐条重新制证。
3. **问题 2 的完整「上次触发/结果/跳过原因」展示**未做（需新端点把 `bank_data_sync_log.TASK_REUSED` 暴露到卡片）；本批先给日志 + 文案，下一轮可补。
4. **币种仍未字典化**（本批只统一口径）；组织映射字典化下一轮启动（用户已排期）。
5. `MEMORY.md` 本批已减负（12362 → 11303 字节，细节下沉 `docs/module-notes.md` §12/§13），仍接近注入上限，下一轮建议再精简。
