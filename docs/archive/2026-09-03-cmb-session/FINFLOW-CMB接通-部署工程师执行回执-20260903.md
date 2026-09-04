# FINFLOW 招行(CMB)真实接入 — 部署工程师执行回执

> 日期: 2026-09-03 16:30 ｜ 执行方: 部署工程师对话 ｜ 依据清单: `FINFLOW-CMB接通-部署工程师运维清单-20260903.md`
> 结论先行: **清单 4 步已全部执行，但步骤四验证暴露阻塞——线上 jar 不含 CMB real 适配器代码，适配器无法激活。线上已回稳，等待全栈侧交付新 jar。**
>
> 🔄 **16:55 状态更新**：缺口修复 jar（fb60e7ae）已部署上线，建档/触发/真实调用全链路打通（见 §9）；但暴露**第三个代码缺口**——状态词汇表缺 CMB 成功码 `SUC0000`，真实流水已拉到但被判定 UNKNOWN 未落库（见 §10），已登记新工单待全栈侧补。
>
> ✅ **17:18 状态更新（闭环达成）**：SUC0000 修复 jar（98cf29f9）已部署上线，同窗口重触发任务 id=3 **SUCCEEDED**，`bank_data_statement` 落库 **9/2 真实流水 4 笔合计 0.27**（与业务侧预期逐笔一致），API 可见性验证通过——**CMB 真实接入全链路闭环**（详见 §11）。

---

## 1. 四步执行结果

| 步骤 | 动作 | 结果 |
|---|---|---|
| 一 | 更新 `docker-compose.yml`（本地交付版整体覆盖，备份 `.bak-20260903`） | ✅ `grep -c 'CMB_'` = 7 |
| 二 | `.env` 追加 CMB 配置（备份 `.bak-20260903`，已去重） | ✅ 26 行，8 键各 count=1 |
| 三 | `docker compose config` + `up -d --force-recreate app` | ✅ compose OK，容器重建 |
| 四 | env 注入 + 启动日志验证 | ⚠️ **表象通过，实质未通过（见 §2）** |

**步骤四表象**（均已确认）：
- `docker compose ps`：`finflow-app` Up (healthy)，`finflow-web` Up 23h 正常
- `docker inspect finflow-app` env：8 个变量全部注入
  - `BANKDATA_REAL_ADAPTERS_ENABLED=true`、`BANKDATA_CMB_REAL_ENABLED=true`
  - `CMB_URL=http://cdctest.cmburl.cn/cdcserver/api/v2`、`CMB_UID=U006855378`
  - `CMB_PRIVATE_KEY` / `CMB_PUBLIC_KEY` / `CMB_SYM_KEY` / `CMB_BRANCH_CODE=12`
- 启动日志：无 error/exception，`Started FinanceSystemApplication in 8.8s`

---

## 2. 关键发现：线上 jar 不含 CMB real 适配器代码（阻塞项）

部署侧按 trust-but-verify 对线上 `app.jar`（**2026-09-02 16:06 构建**）做了 jar 内部取证：

| 检查项 | 线上 jar 内 | 说明 |
|---|---|---|
| `RealCmbBankDataAdapter.class` | ❌ 不存在 | 适配器主类缺失 |
| `CmbAdapterProperties.class` | ❌ 不存在 | 配置属性类缺失 |
| `CmbMockBankDataAdapter.class` | ✅ 存在 | 仍是旧 mock 版 |
| jar 内 `application.yml` bankdata 段 | 仅 `mode/call/sync/retention` | **无 cmb 段、无 citic 段** |
| 本地源码 `application.yml` bankdata 段 | 含完整 cmb 段（`real-enabled/url/uid/private-key/public-key/sym-key/branch-code` 8 个 `${ENV}` 占位符）+ citic 段 | 全栈工程师 CMB 代码已在本地源码树 |

**结论**：全栈侧的 CMB real 适配器代码**只存在于本地源码树，从未构建进线上 jar**。
- yml 占位符转发链路本身正确（本地 `application.yml` L52 `bankdata.adapter.call.real-adapters-enabled: ${BANKDATA_REAL_ADAPTERS_ENABLED:false}`、L77-83 `bankdata.adapter.cmb.*: ${CMB_*:}`），compose/.env 键名与之一一对应，**配置侧无问题**
- 但 jar 内根本没有消费方 → `bankdata.adapter.cmb.real-enabled` 恒为默认 false → `@ConditionalOnProperty` 不满足 → **real 适配器 bean 不装配**
- 步骤四"env 注入 + 无异常"是**假阳性**（env 注入只证明 compose 透传成功，不证明 jar 支持）

---

## 3. 处置与当前线上状态（已回稳）

发现阻塞后，为不破坏现有 mock 数据链路，已做**保守回稳**：

```bash
# .env L18: BANKDATA_REAL_ADAPTERS_ENABLED true → false（总开关回滚，防 real 执行器无适配器可用）
sed -i 's/^BANKDATA_REAL_ADAPTERS_ENABLED=true/BANKDATA_REAL_ADAPTERS_ENABLED=false/' /opt/finflow/.env
docker compose up -d --force-recreate app
```

当前状态：
- `.env` L18 `BANKDATA_REAL_ADAPTERS_ENABLED=false`（回 false）；L20 `BANKDATA_CMB_REAL_ENABLED=true` + CMB_* 8 键**保留**
- `finflow-app` healthy，`GET /api/health` 返回 401（与历史一致），启动日志无异常
- compose 的 7 行 CMB 透传**保留**（新 jar 一到无需再动 compose）

---

## 4. RDS 档案查询（清单 §5 第 4 项）

**⚠️ 清单 SQL 列名有误**：`bank_account` 表无 `account_no` 列，正确列名为 `account_number`。按正确列名查询：

```sql
SELECT id, bank_code, account_name, account_number, status FROM finflow.bank_account ORDER BY id;
-- 结果仅 2 行，均为 CITIC 中信 mock 账户
-- 1 | CITIC | 中信银行基本户 | 6222000000004821 | ACTIVE
-- 2 | CITIC | 中信银行一般户 | 6222000000007306 | ACTIVE
```

**`128965327910000` 无档案 → 需要业务侧在系统页面补建招行账户档案**（清单 §6 分工 1 的"无行"分支）。

---

## 5. 待修项登记（交全栈侧）

> 部署侧不写业务代码，以下请全栈侧处理：

- **[待修-1] 交付新 jar**：构建包含 CMB real 适配器代码（`RealCmbBankDataAdapter` / `CmbAdapterProperties` / `application.yml` cmb+citic 段）的新 `app.jar`，按既有流程附 MD5 + 构建时间交付部署侧。
- **注意**：本次发现 jar（9/2 16:06 构建）连 citic yml 段都缺失，请一并核对线上 jar 与本地源码的差异范围，确认是否还有其它未上线的代码（如 citic sdk 集成），避免分批上线。
- 部署侧收到新 jar 后的复验步骤（无需全栈侧操作）：
  1. 替换 `/opt/finflow/` 下 jar（备份旧 jar），`md5sum -c` 核验
  2. `.env` 把 `BANKDATA_REAL_ADAPTERS_ENABLED` 改回 `true`
  3. `docker compose up -d --force-recreate app`
  4. 复验：jar 内 class + yml 占位符存在 → env 注入 → healthy → 无异常
  5. 回执确认后，由业务侧建档 + 触发 STATEMENT_PULL 同步，观察是否返回真实流水

---

## 6. 相关文件

- 清单: `docs/cmb-clouddc/FINFLOW-CMB接通-部署工程师运维清单-20260903.md`
- 线上备份: `/opt/finflow/docker-compose.yml.bak-20260903`、`/opt/finflow/.env.bak-20260903`
- 本地源码: `backend/src/main/java/com/finance/system/bankdata/adapter/cmb/RealCmbBankDataAdapter.java`（L48 `@ConditionalOnProperty(prefix = "bankdata.adapter.cmb", name = "real-enabled", havingValue = "true")`）
- 本地配置: `backend/src/main/resources/application.yml`（L52、L74-85 CMB 占位符）

---

## 7. 追加：新 jar 构建交付完成 ✅（16:09-16:18）

阻塞解除——含 CMB real 代码的新 jar 已构建、上传并激活：

| 项 | 值 |
|---|---|
| 交付物 | `tmp/finflow-backend-20260903-1609.jar`（83.6MB） |
| MD5 | `235b1c8774dc2502e2b8c36522274893`（本地/远端 md5sum 一致） |
| 构建 | `mvn package -DskipTests`（backend，16:09）；CMB 相关测试已由 16:02 轮次验证 39 用例 0 失败 |
| jar 内取证 | `RealCmbBankDataAdapter.class` ✅ `CmbAdapterProperties.class` ✅ yml CMB 占位符 ×7 ✅（citic 段亦在） |
| 远端备份 | `/opt/finflow/app.jar.bak-20260903-1610`（旧 jar 9/2 16:06） |
| 部署 | `.env` L18 总开关 → true；`docker compose build app`（镜像重建含新 jar）→ `up -d --force-recreate app` |

**复验（16:14，全绿）**：
1. `docker compose ps`：`finflow-app` Up (healthy)，`finflow-web` 正常
2. 容器内 `/app/app.jar` 取证：CMB 两 class 存在、yml 7 占位符存在
3. env 注入：8 个 `BANKDATA_/CMB_` 变量
4. 启动日志：无 error/exception，`Started FinanceSystemApplication in 8.0s`

**下一步（业务侧）**：系统页面补建招行账户档案 `128965327910000` → 触发 STATEMENT_PULL 同步（窗口 9/1~9/3）→ 校验 `bank_data_statement` 出现 9/2 共 4 笔真实流水（合计 -0.27）→ 页面可见。若返回 DCAT003 核对出口 IP（应为本 ECS 101.200.72.87，已在白名单）。

> ⚠️ **16:30 追加：此"下一步"无法执行——产品代码缺 real 触发通道（见 §8），需先由全栈侧补代码。**

---

## 8. 追加：产品入口缺口——real 适配器无触发通道（需全栈侧补代码）

**现象**：新 jar 上线后 real 适配器 bean 已装配（real-enabled=true + mode=mock 共存），但系统内**没有任何入口能真正调用到 RealCmbBankDataAdapter**。

**代码证据**（backend 源码）：
1. `BankDataSyncService.triggerJob()`（L99，web 入口 `POST /api/bank-sync-jobs`）：`new BankDataSyncRequest(..., "MOCK", ...)`——**adapterCode 硬编码 "MOCK"**
2. `BankDataScheduledSyncService.triggerScheduledSyncs()`（L50，调度入口）：同样硬编码 `"MOCK"`
3. `MockBankDataAdapter.adapterCode()="MOCK"` 且 `@ConditionalOnProperty(mode=mock, matchIfMissing=true)` → mode 默认 mock 时 "MOCK" **恒在 registry**
4. `BankDataAdapterRegistry.resolveCode()`：requested="MOCK" 且 registry 含 "MOCK" → **恒返回 MOCK**；即便 requested=null 且 provider="CMB"，也因 `CMB_MOCK` 存在而优先返回 brand mock——**解析规则天然偏向模拟**
5. 结果：`triggerJob` 还会先校验 `Only STATEMENT_PULL is available for the simulated bank data adapter`（L85），该接口本就是为 simulated 设计的

**根因**：registry 的 real 适配器（CMB/CITIC）是"装配可达、触发不可达"。银行数据聚合层支持 real（BankAdapterCallExecutor + real 总开关已通），但**北向触发契约没有把 adapterCode 从请求传到任务**。

**修复建议（供全栈侧，二选一或组合）**：
- **方案 A（推荐，改动最小）**：`BankSyncJobTriggerRequest` 增加可选 `adapterCode` 字段（默认空）→ `triggerJob` 不再硬编码 "MOCK"，透传请求值；`BankDataAdapterRegistry.resolveCode` 增加 real 优先规则：real 总开关开启且 provider/requested 命中 registry 中的 REAL 适配器时，先返回 real（如 CMB），mock 仅作 fallback。
- **方案 B**：`triggerJob` 保持，另增 real 专用触发端点（如 `POST /api/bank-sync-jobs/real`，`bankdata:sync:trigger` 权限 + real 总开关门禁），内部允许 adapterCode="CMB"。
- **配套**：无论 A/B，`resolveCode` 需处理 provider="CMB" 时 real-enabled=false 应回落 CMB_MOCK、real-enabled=true 应命中 CMB 的语义，避免 mock/real 二义。

**部署侧暂不动作**：线上保持 real-enabled=true + mode=mock 是安全组合（real bean 就绪、现有模拟功能不破坏）；待全栈侧补通道并交付新 jar 后，替换 + 复验 + 真实触发。

---

## 9. 追加：缺口修复 jar 部署 + API 验收 ✅（16:45-16:55）

全栈侧按缺口工单交付修复 jar，部署侧完成替换与端到端验收：

### 9.1 交付与部署

| 项 | 值 |
|---|---|
| 交付单 | `docs/cmb-clouddc/FINFLOW-CMB缺口修复-交付单-20260903.md`（全栈侧） |
| 交付物 | `tmp/FINFLOW-backend-cmb-gapfix-20260903.jar`（83,654,144 B） |
| MD5 | `fb60e7ae50287652e2ae2b005071014d`（本地/远端一致 ✅） |
| jar 内取证 | `CmbBankService.class` ✅ 改造后 `BankDataAdapterRegistry.class` ✅ `BankSyncJobTriggerRequest`(adapterCode) ✅ yml 7 占位符 ✅ |
| 远端备份 | `/opt/finflow/app.jar.bak-20260903-1610`（235b1c87，上版；有效回滚弹药） |
| 部署 | scp → `/opt/finflow/app.jar` → `docker compose build app` → `up -d --force-recreate app` |
| 复验 | healthy、容器内 jar 取证通过、env 8 变量、Started 8.92s、`/api/health` 401 ✅ |

### 9.2 API 端到端验收（服务器内 localhost 执行）

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1. 登录 | `POST /api/auth/login`（admin） | ✅ token 获取 |
| 2. 建档 CMB 账户 | `POST /api/bank-accounts` `{bankCode:"CMB",accountNumber:"128965327910000",...}` | ✅ **200**，返回 `id=4, bankCode=CMB, status=ACTIVE`——**缺口 2（建档）实证通过** |
| 3. 触发同步 | `POST /api/bank-sync-jobs` `{jobType:"STATEMENT_PULL",bankAccountId:4,adapterCode:"CMB",windowStart:"2026-09-01T00:00:00",windowEnd:"2026-09-03T00:00:00"}` | ✅ **200**，job id=2，task_no `BDST-2CFD56BCFDF64962AEB7` |
| 4. 落库核对 | RDS `bank_data_sync_task WHERE id=2` | ✅ **`adapter_code=CMB`**（非 MOCK）、`bank_request_no=202609031650402145OKC5YKV20`——**缺口 1（real 触发通道）实证通过** |

### 9.3 银行真实响应（raw 铁证）

任务 2 的 `bank_data_raw_message` 记录了 CMB 测试网关**真实解密后的响应**：

- 余额快照：`availableBalance=816064.12`（asOf 16:50:41）
- **4 笔流水全部拉取成功**（`bank_account_id=4`，均 2026-09-02，EXPENSE，收方何桂香 `6214830094198511`）：
  - `C0547IL00009WTZ` 0.01 支付转账测试1（14:38:04）
  - `C0547IL0000A0FZ` 0.02 支付转账测试1（14:45:25）
  - `C0547IL0000A4DZ` 0.22 批量支付测试1（14:57:27）
  - `C0547IL0000AAGZ` 0.02 支付转账测试1（15:10:16）——合计 0.27，与业务侧预期"9/2 四笔合计 -0.27"**完全吻合**
- 网关返回 `bankStatusCode=SUC0000`（招行成功码）→ **签名/加解密/验签全通过，出口 IP 白名单无问题**（此前担心的 DCAT003 不存在）

**适配器真实链路 100% 打通**——但任务却落 `status=UNKNOWN`、`raw_count=0`、`bank_data_statement` 无落库，原因见 §10（第三个代码缺口）。

---

## 10. 追加：状态词汇表缺 CMB 成功码 SUC0000（第三个缺口，需全栈侧补）

### 10.1 现象

任务 2 payload 自相矛盾：`bankStatusCode="SUC0000"`（银行成功）+ `status="UNKNOWN"`（系统判定未知）→ 数据被阻断在规范化前（`raw_count=0`、statement 未落库）。

### 10.2 代码证据（三重）

1. `RealCmbBankDataAdapter` L122/L196：`new BankDataCollection(..., SUCCESS, SUCCESS)`，`SUCCESS = CmbResponseParser.SUCCESS_CODE = "SUC0000"`
2. `BankDataAggregationService` L69：`BankDataStatus.fromVendor(status)`（status="SUC0000"）
3. `BankDataStatus.fromVendor` 映射表：`case "SUCCESS","OK","AAAAAAA" -> SUCCESS`——**有 CITIC 成功码 AAAAAAA，无 CMB 成功码 SUC0000** → `default -> UNKNOWN`

### 10.3 为什么单测没抓到

`RealCmbBankDataAdapterTest`（10 用例）只测解析/构造层，不覆盖 `fromVendor("SUC0000")` 聚合映射；`CmbGapClosureIntegrationTest` 的 stub 适配器若走 3 参构造则 status 默认 `"SUCCESS"`（可被 fromVendor 识别），未暴露真实银行码。

### 10.4 修复建议（供全栈侧，一行 + 单测）

- `BankDataStatus.fromVendor` 增加 `case "SUC0000" -> SUCCESS;`
- 单测补：`fromVendor("SUC0000") == SUCCESS`；建议集成测试 stub 返回 `bankStatusCode="SUC0000"` 走完整聚合路径
- 部署侧收到新 jar 后：替换 → 复验 → **重触发任务 2 同窗口同步**（原任务 status=UNKNOWN 数据未落库，需新任务重拉；历史 UNKNOWN 任务保留作 trace）→ 核对 `bank_data_statement` 落库 4 笔 → 页面可见

### 10.5 当前线上状态（安全）

- 组合 safe：real-enabled=true + mode=mock；CMB 建档 id=4 与 UNKNOWN 任务 id=2 保留，等修复 jar 到位后重触发即可
- 全部备份齐备（旧 jar ×2 / compose / .env）

---

## 11. 追加：SUC0000 修复 jar 部署 + 真实流水闭环 ✅（17:07-17:18）

全栈侧按 §10 工单补 SUC0000 映射并交付新 jar，部署侧完成替换、重触发与落库验证——**CMB 真实接入全链路闭环**。

### 11.1 交付与部署

| 项 | 值 |
|---|---|
| 交付单 | `docs/cmb-clouddc/FINFLOW-CMB缺口2-SUC0000修复-交付单-20260903.md`（全栈侧） |
| 交付物 | `tmp/FINFLOW-backend-cmb-suc0000fix-20260903.jar`（83,654,168 B） |
| MD5 | `98cf29f9c503857b88f4629871512310`（本地/远端 md5sum 一致 ✅） |
| jar 内取证 | **`BankDataStatus.class` 字节码含 `SUC0000`** ✅（AAAAAAA/AAAAAAE/EEEEEEE 词汇表码并列在位）；`CmbBankService.class` 等前序修复未回退 ✅；yml 7 占位符 ✅ |
| 远端备份 | `/opt/finflow/app.jar.bak-20260903-1709`（fb60e7ae gapfix 版，备份先于 scp，有效回滚弹药） |
| 部署 | scp → `/opt/finflow/app.jar` → `docker compose build app` → `up -d --force-recreate app` |
| 复验 | healthy、容器内 jar 取证 SUC0000 ✅、env 8 变量、Started 8.407s、`/api/health` 401 ✅ |

### 11.2 同窗口重触发（含幂等绕行说明）

首次触发被幂等拦下：`BankDataSyncService` 的 syncKey 幂等（account+connection+adapterCode+window）命中旧任务 id=2（UNKNOWN 终态，L164-173 `recordTaskReused` 直接返回旧任务）。按交付单备注**微调窗口起点**绕行（8/31 起点，仍完整覆盖 9/1~9/2 目标流水，产生新 syncKey）：

| 步骤 | 结果 |
|---|---|
| 登录 | `POST /api/auth/login`（admin）✅ |
| 触发 | `POST /api/bank-sync-jobs` `{jobType:"STATEMENT_PULL",bankAccountId:4,adapterCode:"CMB",windowStart:"2026-08-31T00:00:00",windowEnd:"2026-09-03T00:00:00"}` ✅ |
| 新任务 | **id=3**，task_no `BDST-F154F0E1B40B43FBAA51`，**`status=SUCCEEDED`**（不再是 UNKNOWN）|
| 计数 | `raw=11, normalized=7, duplicates=4, invalid=0`（窗口 8/31 起比 9/1~9/3 更宽，银行返回更多流水）|

### 11.3 落库核对（RDS，核心验收）

`bank_data_statement` 新增 **4 笔**（`bank_account_id=4`，全表仅此 4 条）：

| statementNo | 交易时间 | 方向 | 金额 | 摘要 |
|---|---|---|---|---|
| C0547IL00009WTZ | 09-02 14:38:04 | EXPENSE | 0.01 | 支付转账测试1 |
| C0547IL0000A0FZ | 09-02 14:45:25 | EXPENSE | 0.02 | 支付转账测试1 |
| C0547IL0000A4DZ | 09-02 14:57:27 | EXPENSE | 0.22 | 批量支付测试1 |
| C0547IL0000AAGZ | 09-02 15:10:16 | EXPENSE | 0.02 | 支付转账测试1 |

**合计 0.27，与业务侧预期"9/2 四笔合计 -0.27"完全一致。**

`bank_data_raw_message`（task=3）payload 双字段现已一致：`bankStatusCode=SUC0000` + `status=SUCCESS`——§10 映射缺口修复生效实证；余额快照 816,064.12 亦在列。

### 11.4 API 可见性验证

`GET /api/bank-data/statements?accountId=4`（页面数据源）→ **total: 4**，4 笔流水全部可查——页面可见性成立（注：`/api/statements` 为 statement-import 另一概念，total=0 属预期，勿混淆）。

### 11.5 备注观察项（不阻塞闭环，供全栈侧确认）

- `raw_count=11` vs `bank_data_raw_message` 表仅 3 行：银行按余额/流水分批返回多个响应（id 3=余额、4/5=流水页），raw_count 应为 entry 级计数口径；id=4/5 以同一 statementNo `C0547IL00009WTZ` 开头，duplicates=4 来自分批重叠返回（去重键 (account, statementNo) 命中）。**落库结果正确（4 笔目标流水无重复），统计口径差异待全栈侧确认是否需对齐。**
- 旧任务 id=2（UNKNOWN）与新任务 id=3（SUCCEEDED）均保留作 trace；业务侧可在页面核对流水与任务详情。

### 11.6 CMB 真实接入 — 全链路验收对照表

| 环节 | 结果 |
|---|---|
| compose 透传 / .env 密钥 | ✅（§1） |
| jar 含 real 代码 + bean 装配 | ✅（§7，235b1c87） |
| real 触发通道（adapterCode=CMB） | ✅（§9，fb60e7ae） |
| CMB 账户建档 | ✅（§9，id=4） |
| 真实调用（签名/加解密/白名单） | ✅（§9 raw 铁证） |
| SUC0000 状态映射 → 落库 | ✅（§11，98cf29f9） |
| **真实流水入库 + API 可见** | ✅（§11，4 笔 0.27） |
