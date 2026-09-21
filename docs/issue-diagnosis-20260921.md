# 线上问题诊断（2026-09-21 18:xx）：AI 制证不可见 / 同步计划未启动 / 硬编码清单

- **诊断方式**：代码通读 + **线上库实测**（ECS 内 `mysql` 直连 RDS，只读）+ 线上日志/env 核对
- **基线**：jar `0aa72c0b`（W12b）/ 入口 `index-CoCcIVNd.js` / flyway v42
- **数据快照时间**：2026-09-21 18:1x

---

## 一、问题 1：AI 制证提示成功，但凭证中心看不到

### 结论
**两个独立缺陷叠加**，且其中一个（1A-②）**当前仍未修复**。

### 1A 假成功：审计表里的铁证

`statement_audit_event` 实测：

| id | statement_id | action | result | detail |
|---|---|---|---|---|
| 64 | 16 | AI_VOUCHER_DRAFT | **SUCCESS** | **AI 建议不可用：流水不存在或不在当前公司域内** |
| 58 | 15 | AI_VOUCHER_DRAFT | **SUCCESS** | 同上 |
| 57 | 14 | AI_VOUCHER_DRAFT | **SUCCESS** | 同上 |
| 48 | 12 | AI_VOUCHER_DRAFT | SUCCESS | AI 建议：业务类别 利息支出｜科目 财务费用-利息支出（置信度 86%） |

**result=SUCCESS 与 detail 内容自相矛盾** —— 旧代码在 AI 调用失败时把行标记为 `DRAFT_CREATED`（成功），
只在复核意见里写「AI 建议不可用」。这就是你看到「制证成功却什么都没有」的直接来源。

- 这批记录产生于 **15:12–15:17**，而修复它的 W12b 于 **17:59** 才上线 ⇒ 历史数据是旧行为产物。
- 当时跨公司 AI 建议失败的真正原因：`AccountingSuggestionService.requireInCompanyScope` 硬校验
  「流水公司 == 操作人公司」（`AccountingSuggestionService.java:177-184`），
  放行跨公司需要 `bankdata:cross-company:view` —— 该能力（FIX-008）**15:46 才上线**。
- 已核实 admin 账号**拥有**该权限（`sys_permission` id=40 → 角色 1 ADMIN），所以修复后跨公司口径是一致的。

#### 1A-② 仍未修复的第二个假成功路径（重要）

`BankDataAccountingService.processRowAsDraft` 的写入条件写死了「必须是待复核」：

```java
int updated = recordMapper.update(null, new LambdaUpdateWrapper<StatementRecord>()
        .set(StatementRecord::getReviewComment, comment)
        .set(StatementRecord::getAiSuggestionJson, serializeSuggestion(suggestion))
        .eq(StatementRecord::getId, record.getId())
        .eq(StatementRecord::getReviewStatus, REVIEW_PENDING));   // ← 撤回态不匹配
if (updated == 1) { insertAudit(...); }
return ... "DRAFT_CREATED", "OK", ..., "草稿已生成，待人工复核后推送";   // ← 影响 0 行也报成功
```

**对已撤回（WITHDRAWN）或状态已变的流水重新制证时**：AI 建议真的生成成功 → update 影响 **0 行**（什么都没写）
→ 仍返回 `DRAFT_CREATED`「草稿已生成」→ 凭证中心当然看不到。
V39 承诺的「撤回后流水回池可重新制证」在当前实现下**无法真正生效**（写入被状态条件挡住）。

### 1B 撤回/驳回的凭证在凭证中心无处可查

`VoucherGroupService.applyStatusFilter` 只有 4 个桶 + ALL：

| 页签 | 过滤条件 |
|---|---|
| 待复核 DRAFT | `review_status = PENDING` |
| 待推送 PENDING | `review_status = APPROVED` 且未推送 |
| 已推送 PUSHED | `push_status ∈ (PUSHED, GL_PUSHED)` |
| 失败 FAILED | `push_status ∈ (FAILED, GL_FAILED)` |
| **全部 ALL** | `voucher_no 非空` **OR** `review_status ∈ (PENDING, APPROVED)` |

线上 `statement_record` 共 16 条，**状态分布：WITHDRAWN 11 条 / REJECTED 5 条 —— 没有一条是 PENDING 或 APPROVED**。
⇒ 这 16 条在凭证中心的**任何页签都查不到**（其中 push_status=FAILED 的 7 条会出现在「失败」签）。
连带后果：**「重新打开」按钮（针对 REJECTED 行）实际不可达** —— 驳回的行根本不显示，按钮永远点不到。

### 修复建议（问题 1）
| # | 改动 | 位置 | 目的 |
|---|---|---|---|
| 1 | **影响 0 行必须返回 FAILED**（不再报成功） | `BankDataAccountingService` DRAFT/PUSH 两条路径 | 杜绝任何假成功 |
| 2 | 显式处理 **WITHDRAWN**：视为「可重新制证」→ 复位 `review_status=PENDING` 再写入（真复活），或返回明确 outcome + 提示先重新打开 | 同上 | 让「撤回后重新制证」真的生效 |
| 3 | 凭证中心补**「已撤回」**桶（或让 ALL 覆盖 WITHDRAWN/REJECTED） | `VoucherGroupService` + 前端 `VOUCHER_GROUP_FILTERS` | 撤回/驳回记录可见、可追溯，恢复「重新打开」入口 |

---

## 二、问题 2：新建的同步计划没有启动

### 线上实测

| 项 | 值 |
|---|---|
| 计划表 | 仅 **2 条**：`06:35`（id 6，09-17 09:28 创建）、`15:45`（id 7，09-17 15:42 创建），均 `enabled=1` |
| 调度开关 | `BANKDATA_SYNC_SCHEDULE_ENABLED=true`（容器 env + 宿主 `.env` 均有）⇒ **"开关没开"的嫌疑排除** |
| 时区 | 宿主与容器均 `CST`（Asia/Shanghai）⇒ 时区偏移排除 |
| 06:35 的执行 | **每天正常**：09-21 06:35 生成任务 119–126（全账户 SCHEDULED / SUCCEEDED） |
| 15:45 的执行 | **从未产生任何任务**（近 7 天 SCHEDULED 任务最后时间 = 09-21 06:35） |

### 根因：同一自然日内「T-1 窗口」恒定 ⇒ 第二个时刻必然被幂等吞掉

```java
// BankDataScheduledSyncService
private String scheduledRequestId(BankAccount account, String adapterCode, Window window) {
    String key = companyId + ":" + accountId + ":" + adapterCode + ":" + window.start() + ":" + window.end();
    return "scheduled-" + UUID.nameUUIDFromBytes(key...);      // 窗口 = 昨天 00:00 → 今天 00:00
}
```
```java
// BankDataSyncService（命中同 requestId 时）
if (existing != null) { ... return queryService.getTaskDetail(existing.getId(), companyId); }  // 复用旧任务
```

06:35 已经把当天 T-1 窗口全部拉完；15:45 心跳**确实触发了**（`fireIfDue` 对任意启用计划都匹配，
日志关键字 `bank sync schedule fired at`），但每个账户的 requestId 都与 06:35 相同
→ 直接复用旧任务：**不新建、不执行、不打日志** ⇒ 看起来「没有启动」。

**放大问题的两点**：
1. `BankDataScheduledSyncService` 里 `catch (RuntimeException ignored) {}` 连「同账户同窗口已有任务在跑（409）」都静默吞掉，**零日志** ⇒ 运维不可观测。
2. 界面上的「下次执行」是**前端本地推算**的，与后端真实是否会执行无关 ⇒ 用户被引导相信它一定会跑。

### 修复选项（问题 2）
| 方案 | 内容 | 代价 | 评价 |
|---|---|---|---|
| **A（推荐）** | **保留语义、让它可见**：卡片标注「计划时刻决定当天首次拉取；同一 T-1 窗口已同步则跳过」，并显示**上次触发时间 / 上次结果 / 跳过原因**；后端给跳过分支补 INFO 日志，409 不再静默 | 小（1 端点 + 1 组件） | 诚实、零风险，直接消除"我配了但没生效"的困惑 |
| B | 让第二个时刻**真正再拉一次**：窗口由「T-1 全天」改为「自上次成功同步至今」 | 中高（增量边界、银行限流、幂等键重构） | 功能更强，但要重新验证招行/中信限流 |
| C | 一天只允许一个计划（建第二个直接拒绝） | 最小 | 诚实但削弱功能，与"多时刻"的 UI 预期冲突 |

---

## 三、问题 3：清单 —— 「做出来但没真交互 / 底层硬代码」

### 先纠正一个前提（有据）

我用机械扫描脚本（`tmp/fake-interaction-scan.py`，判据全在代码里）逐文件核对了四类嫌疑：
**①有 success 提示但窗口内无任何 API 调用 ②空 handler / 只有 console ③内联硬编码下拉 ④占位页**。

| 扫描项 | 结果 | 说明 |
|---|---|---|
| 空 handler（`onClick={() => {}}` 等） | **0 处** | 不存在"点了什么都不做"的按钮 |
| success 提示但无 API 调用 | 23 处，**逐条核对后全部属正常** | 多来自表格内核本地操作（复制/列设置/本页筛选/视图保存）与本地 CSV 导出 |
| 内联硬编码下拉 | 7 处，**均为枚举** | 币种 CNY/USD、借贷方向、页签等，属合理 |
| 占位页组件 | 1 处**定义、0 处引用** | `shared/components.tsx:62`「入口与路由已保留」是**死代码**，没有任何页面在用 |

**并且你举例的「新增同步时间」其实是真的连了后端** —— 数据库里 `bank_sync_schedule` 的两条计划就是它写进去的。
你感受到的"没生效"是**问题 2 的幂等 + 无反馈**，不是按钮造假。

### 那"底层硬代码"到底在哪 —— 展示字典与映射层（真实清单）

这些是"界面看起来是系统的能力，实际写死在代码里、改一个字就要发版"的地方：

| # | 类别 | 位置 | 影响 | 字典化状态 |
|---|---|---|---|---|
| 1 | **金蝶组织映射** | 后端 `KingdeeOrgResolver` 内置常量（即设 300 / 雪云 400 / 海南 900 / 长沙 710 / 广州 720 / 图虫 410 / 映脉 411 / 浙江 420 / 浙江北分 421） | **新增公司主体必须改代码 + 发版** | ❌ **最高价值** |
| 2 | **币种中文名** | 前端 `bankQueryTexts.ts:31`（屏幕）/ `grid/kernel.ts:194`（**死代码**）/ 后端 `BankDataExportService.java:56`（导出） | **三处口径不一 ⇒ 屏幕与 CSV 可能显示不同** | ❌ 财务口径风险 |
| 3 | **银行报文码表** | `bankQueryTexts.ts:7-21`：借贷标志 `LOAN_CODE_TEXT`、冲补账 `REVERSAL_TEXT`、账户状态 `ACCOUNT_STATUS_TEXT`、利息类型 `INTEREST_TYPE_TEXT`、信息标志 `INFO_FLAG_TEXT` | 银行新增码值要改代码 | ❌ |
| 4 | **状态/事件/日志文案** | `shared/dict.ts` 8 个 Record：`JOB_TYPE_TEXT`/`TRIGGER_TYPE_TEXT`/`SYNC_STATUS_TEXT`/`LOG_LEVEL_TEXT`/`LOG_RESULT_TEXT`/`LOG_EVENT_TEXT`/`LOG_RESULT_EXTENDED_TEXT`/`STATUS_TAG_TEXT` | 改文案要发版 | ❌ |
| 5 | **银行 adapter 路由** | 后端 `BankDataAdapterRegistry` 按 `bank_code` 硬编码 + REAL 适配器白名单 | 新增银行要写 adapter + 改配置 | ❌ |
| 6 | 其他展示映射 | `admin/UsersPage.tsx:30` `DOMAIN_LABELS`、`bank-access/pages.tsx:22,27` `CHANNEL_NOTES`/`CONNECTION_TONE`、`kingdeeMapping.tsx:16` `STATUS_META` | 纯展示，低风险 | ❌ |
| 7 | 校验规则表达式 | 后端表达式语法（如 `amount between 100 and 5000`） | 字段与语法固定 | ⚠️ 规则本身可配 |
| 8 | 金蝶默认维度账号 | `KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER` | V41 起已降级为「流水无账户归属时的兜底」 | ⚠️ 部分 |
| — | 银行中文名 | `bankQueryTexts.ts:24` `BANK_NAME_TEXT` | 新增银行 | ✅ **已字典化**（W11b），常量仅兜底 |

### 建议的字典化优先级
1. **金蝶组织映射**（新增主体=改代码，业务侧最痛）
2. **币种中文名**（三处口径不一，属财务口径风险，且改动量小）
3. 银行报文码表（等银行新增码值/字段时再做）
4. 状态与日志文案（低风险，可延后）

---

## 四、待你拍板

| 项 | 选项 |
|---|---|
| 问题 1 | 修 3 项（0 行不算成功 / 撤回态可复活 / 凭证中心补已撤回桶）—— 建议全做 |
| 问题 2 | A 保留语义+可见（推荐） / B 真再拉一次 / C 一天一计划 |
| 问题 3 | 是否现在启动组织映射字典化（1）+ 币种统一（2） |
