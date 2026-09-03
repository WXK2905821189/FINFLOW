# FINFLOW CMB 接入缺口工单 — 交全栈侧

> 日期: 2026-09-03 16:35 ｜ 提交方: 部署工程师对话 ｜ 处理方: 全栈工程师对话
> 前置说明: 部署侧已完成 CMB 接入的**全部环境与产物工作**（compose 透传、.env 密钥、含 real 代码的新 jar 上线、real 总开关开启、容器复验全绿）。但代码级调查发现**两个产品缺口，导致真实招行流水仍不可达**，需全栈侧补代码后重新交付 jar。

---

## 背景（当前线上状态，全栈侧无需重复部署）

- 线上 `finflow-app` 已运行含 CMB real 代码的新 jar（MD5 `235b1c8774dc2502e2b8c36522274893`，2026-09-03 16:09 构建）
- `.env`：`BANKDATA_REAL_ADAPTERS_ENABLED=true`、`BANKDATA_CMB_REAL_ENABLED=true`、`CMB_*` 8 键齐全（测试网关密钥）
- `RealCmbBankDataAdapter` bean 装配条件成立（jar 内 class + yml 占位符已取证）
- 容器 healthy，现有模拟数据功能不受影响

---

## 缺口 1：real 适配器无触发通道（阻塞真实流水）

**现象**：系统内没有任何入口能真正调用 `RealCmbBankDataAdapter`，触发同步恒走 MOCK 模拟造数。

**代码证据**：
1. `backend/src/main/java/com/finance/system/bankdata/BankDataSyncService.java` L99（web 入口 `POST /api/bank-sync-jobs` → `triggerJob()`）：
   ```java
   new BankDataSyncRequest(request.connectionCode(), account.getId(), "MOCK", window.start(), window.end())
   ```
   **adapterCode 硬编码 "MOCK"**。
2. `backend/src/main/java/com/finance/system/bankdata/BankDataScheduledSyncService.java` L50（调度扫描）：同样硬编码 `"MOCK"`。
3. `MockBankDataAdapter`（`adapterCode()="MOCK"`，`@ConditionalOnProperty(prefix="bankdata.adapter", name="mode", havingValue="mock", matchIfMissing=true)`）→ mode 默认 mock 时 `"MOCK"` **恒在 registry**。
4. `BankDataAdapterRegistry.resolveCode()`：requested="MOCK" 且 registry 含 "MOCK" → **恒返回 MOCK**。即便 requested=null 且 provider="CMB"，也因 `CMB_MOCK` 存在而优先返回 brand mock。
5. 附带：`triggerJob` L85 校验 `Only STATEMENT_PULL is available for the simulated bank data adapter`——该接口本就是为 simulated 设计。

**建议修法（方案 A，改动最小，推荐）**：
1. `BankSyncJobTriggerRequest`（`backend/src/main/java/com/finance/system/bankdata/dto/BankSyncJobTriggerRequest.java`）增加可选字段 `adapterCode`（`@Size(max=64)`，可空）。
2. `BankDataSyncService.triggerJob()` L99：不再硬编码 "MOCK"，透传 `normalize(request.adapterCode())`（空则按现有逻辑回落）。
3. `BankDataAdapterRegistry.resolveCode()` 增加 **real 优先语义**：当 real 总开关（`bankdata.adapter.call.real-adapters-enabled=true`）开启、且 provider/requested 命中 registry 中的 REAL 执行模式适配器（如 "CMB"→`RealCmbBankDataAdapter`）时，优先返回 real 适配器；mock 仅作 fallback。需区分真实执行模式，可复用 `BankDataAdapter.executionMode()`（`REAL` vs `SIMULATED`）或按 adapterCode 白名单。

**验收标准**：`POST /api/bank-sync-jobs` 携带 `adapterCode="CMB"` + 已建档的招行账户 id + 窗口，能触发一次真实调用（招行测试网关返回 SUC0000 或可预期的银行错误码，如 IP/密钥类），任务落库 adapterCode=CMB 而非 MOCK。

---

## 缺口 2：CMB 账户档案无法通过产品接口建档（阻塞触发前置条件）

**现象**：`RealCmbBankDataAdapter.collect()` 依赖 `bank_account` 表中存在 bankCode=CMB 的账户档案（按 `bankAccountId` 解析 `account_number`），但建档 API 拒绝 CMB。

**代码证据**：
1. `BankAccountService.create()`/`updateAccount()` 调用 `bankServiceFactory.get(request.bankCode())` 做前置校验。
2. `BankServiceFactory` 收集所有 `implements BankService` 的 bean——当前**仅有 `CiticBankService`（`bankCode()="CITIC"`）一个实现**（`backend/src/main/java/com/finance/system/bank/citic/CiticBankService.java`）。
3. 因此 `POST /api/bank-accounts` 传 `bankCode="CMB"` → `Unsupported bank: CMB`（404）。

**建议修法**：
- 新增 `CmbBankService implements BankService`（`bankCode()="CMB"`），职责可参照 CiticBankService 的最小实现（若 BankService 抽象主要服务于对账/付款等场景，而 CMB 当前仅需要"数据同步读"，可先提供空操作/最小实现 + TODO 注释，或与缺口 1 一并评估 BankService 是否应放宽为"已注册的银行数据适配器即可建档"）。
- **注**：RDS 中招行结算户 `128965327910000`（户名"银企直连测试用户专用12"，测试网关实测余额 816065.34）尚**无档案**，建档时 `availableBalance` 可从 0 起步（同步会把真实余额覆盖）。

**验收标准**：`POST /api/bank-accounts` 可建档 `bankCode="CMB"`（示例 body：`{"bankCode":"CMB","accountName":"招商银行基本户","accountNumber":"128965327910000","currency":"CNY","availableBalance":0,"status":"ACTIVE"}`）返回 200。

---

## 交付要求（同既有流程）

1. 两缺口修复后本地跑相关测试（至少 `BankDataAggregationServiceTest` / `BankDataAggregationTraceTest` / `RealCmbBankDataAdapterTest` / 建档相关单测）确认全绿。
2. 构建新 jar，产物附 **MD5 + 构建时间** 交付（放本工作区 `tmp/` 或告知路径即可，部署侧会自行取用并核验）。
3. 若修复涉及页面（建档入口 UI 校验银行列表等），一并说明前端是否有改动，避免只更后端导致页面不显示 CMB。

---

## 部署侧承诺（收到新 jar 后自动执行，无需全栈侧操作）

- 替换 `/opt/finflow/app.jar`（备份旧 jar）→ `md5sum -c` 核验 → `docker compose build app && up -d --force-recreate app` → 复验（jar 取证 + env + healthy + 日志）
- 用 `admin` 账号建档 CMB 账户 → 触发 `adapterCode="CMB"` 的 STATEMENT_PULL（窗口 2026-09-01~2026-09-03）→ 回执真实流水落库结果
- 若触发返回银行错误码（如 DCAT003 IP 白名单、密钥类错误），按错误码排查并把证据回传

## 附：部署侧已勘误的清单问题（全栈侧知悉即可）

- CMB 运维清单 §5 回执 SQL 列名错误：`finflow.bank_account` 无 `account_no` 列，正确为 `account_number`。
- 线上 jar（2026-09-02 16:06 构建）此前连 citic yml 段都缺失——已由本次新 jar（16:09）覆盖，若全栈侧本地有其他未上线改动请核对差异范围。
