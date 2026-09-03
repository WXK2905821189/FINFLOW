# FINFLOW CMB 缺口修复交付单（全栈侧 → 部署侧）

> 日期: 2026-09-03 ｜ 处理方: 全栈工程师对话 ｜ 对应工单: FINFLOW-CMB缺口工单-交全栈侧-20260903.md
> 状态: 两缺口均已修复，后端全量测试 109/109 通过，新 jar 已就绪待部署侧取用

---

## 一、修复内容

### 缺口 1：real 适配器无触发通道（adapterCode 透传 + real 优先语义）

| 文件 | 改动 |
|---|---|
| `backend/.../bankdata/dto/BankSyncJobTriggerRequest.java` | 新增可选字段 `adapterCode`（`@Size(max=64)`，可空） |
| `backend/.../bankdata/BankDataSyncService.java` | `triggerJob()` L99 不再硬编码 `"MOCK"`，透传 `request.adapterCode()`（空则由 registry 依连接 provider 解析，最终回落 MOCK）；jobType 校验文案改为中性表述 |
| `backend/.../bankdata/aggregation/BankDataAdapterRegistry.java` | `resolveCode()` 增加 **real 优先语义**：`bankdata.adapter.call.real-adapters-enabled=true` 且 provider 命中 registry 中 `executionMode()==REAL` 的适配器（如 `CMB`→`RealCmbBankDataAdapter`）时优先返回 real，brand mock 仅作 fallback；新增以 `BankAdapterCallProperties` 注入 real 总开关的构造（保留单参构造供单测） |

**行为说明（部署侧知悉）**
- 显式 `adapterCode=CMB` → 只要 real bean 装配（`BANKDATA_CMB_REAL_ENABLED=true`）即命中 real 适配器；任务落库 `adapterCode=CMB`。
- 不传 adapterCode 且带 CMB 连接 → real 总开关开启时同样走 real（provider 驱动）。
- 显式传一个不存在的 code（如 `CITIC_REAL`）→ **400 fail-fast**，不再静默回落 MOCK。
- 调度扫描（`BankDataScheduledSyncService`）维持 MOCK 语义未动（schedule 默认关闭；如需"调度跑真实 CMB"另行立项）。

### 缺口 2：CMB 账户档案建档

| 文件 | 改动 |
|---|---|
| `backend/.../bank/cmb/CmbBankService.java`（新增） | `implements BankService`，`bankCode()="CMB"`；`POST /api/bank-accounts` 传 `bankCode=CMB` 通过 `BankServiceFactory.get()` 前置校验。余额查询/转账为遗留支付通道能力（v0.4 已裁撤），显式抛业务异常说明走 bank data pipeline，不静默返回陈旧数据 |

---

## 二、测试证据

| 范围 | 结果 |
|---|---|
| 新增 `BankDataAdapterRegistryTest`（aggregation 包，real 优先/回落/显式未知 code 拒绝） | 4/4 通过 |
| 新增 `CmbGapClosureIntegrationTest`（CMB 建档 200 + `adapterCode=CMB` 触发落库 CMB + 未知 code 400 fail-fast；@TestPropertySource 开 real 总开关 + 无网络 stub real 适配器） | 2/2 通过 |
| 存量回归：`BankDataAggregationServiceTest` / `BankDataAggregationTraceTest` / `V02BackendIntegrationTest` / `RealCmbBankDataAdapterTest` / `RealCiticBankDataAdapterTest` / `BankAdapterCallExecutorTest` 等 | 全绿 |
| **后端全量 `mvn test`（-Djacoco.skip=true，jacoco agent 本机不可用已跳过）** | **109 通过 / 0 失败 / 0 错误** |

> 注：本机 jacoco javaagent 启动失败（`FATAL ERROR ... processJavaStart failed`），测试与打包均以 `-Djacoco.skip=true` 执行，不影响测试语义与产物。

---

## 三、交付产物

### 后端 jar（主交付物）

- 路径：`tmp/FINFLOW-backend-cmb-gapfix-20260903.jar`
- **MD5: `fb60e7ae50287652e2ae2b005071014d`**
- 大小：83,654,144 bytes（约 79.8 MB）
- 构建时间：**2026-09-03 16:41（GMT+8）**
- jar 内已取证：`BOOT-INF/classes/com/finance/system/bank/cmb/CmbBankService.class`、改造后 `BankDataAdapterRegistry.class`、`BankSyncJobTriggerRequest.class` 均存在
- 与线上 16:09 jar（MD5 `235b1c87...74893`）的差异范围 = 本交付单 §一 所列 **3 个后端改动文件 + 1 个新增文件**；本 jar 由当前本地工作树构建，若部署侧发现其他差异文件，请回传核对

### 前端 dist（可选交付物）

- 本次前端改动：`frontend/src/modules/bank-access/types.ts`（`BankSyncJobTrigger` 增 `adapterCode?`）、`operations.tsx`（「手动触发同步」弹窗增「适配器代码」输入项，留空回落默认）
- `tsc -b` 编译通过；`vite build` 成功
- 打包：`tmp/FINFLOW-web-dist-cmbgapfix-20260903.zip`，**MD5: `d503345ebb83eb6603b1b4176bb8bde2`**
- **说明**：验收标准（建档 + 触发）走 API 即可完成，**不依赖前端**。UI 入口仅当需要产品页手动填 `CMB` 时再替换 nginx `web-dist`（解压 zip 覆盖）

---

## 四、部署侧验收指引（沿用工单承诺）

1. 替换 jar → `md5sum -c`（期望 `fb60e7ae50287652e2ae2b005071014d`）→ compose 重建 → 复验。
2. 建档 CMB 账户（示例，验收标准 body）：
   ```json
   {"bankCode":"CMB","accountName":"招商银行基本户","accountNumber":"128965327910000","currency":"CNY","availableBalance":0,"status":"ACTIVE"}
   ```
   → 期望 200 返回 `bankCode=CMB`。
3. 触发 STATEMENT_PULL（窗口建议按实际测试网关可取范围，工单示例 2026-09-01~09-03）：
   ```json
   {"jobType":"STATEMENT_PULL","bankAccountId":<建档返回的 id>,"adapterCode":"CMB","windowStart":"2026-09-01T00:00:00","windowEnd":"2026-09-03T00:00:00"}
   ```
   → 期望任务状态落库 `adapterCode=CMB`（可用 trace/查询接口或 DB 核对 `bank_data_sync_task.adapter_code`），不再为 MOCK。
4. 若返回银行错误码（DCAT003 IP 白名单 / 密钥类），按错误码排查并回传证据（测试网关公网出口 IP 需加入白名单属预期风险）。
