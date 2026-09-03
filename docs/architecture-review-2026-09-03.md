# FINFLOW 项目结构审阅 · 2026-09-03

> 审阅人：WB（独立实测，不依赖既往报告结论） ｜ 基准：`codex/auto-accounting-release` @ `8221b9d` + 83 项未提交改动
> 上次审阅：2026-09-01（覆盖率基线 64.4%）→ 本次 72.3%

## 一、总体结论

**架构骨架仍然健康，但当前工作树处于「不可直接提交」状态。**

两天内项目显著扩张（新增中信直联、招行 CMB 直联、金蝶凭证三条接入线），分层纪律未退化，覆盖率从 64.4% 提升到 72.3%。但本次实测抓到 **1 个 P0 + 2 个 P1**：工作树跑出 3 个红灯（已提交 HEAD 的 CI 是绿的）、默认 `mvn test` 直接依赖解析失败、30MB 厂商二进制即将入库。

---

## 二、实测证据

| 核验项 | 命令 / 方式 | 实测结果 |
| --- | --- | --- |
| 已提交状态 CI | `gh run list` | **`success`**（33726295764，15:04，分支 `codex/auto-accounting-release`）✅ |
| 工作树后端测试 | `mvn -s .m2-settings.xml -P '!citic-sdk' test` | **110 项，3 失败** ❌ |
| 默认构建（不带 profile） | `mvn -s .m2-settings.xml test` | **依赖解析失败** ❌ |
| 前端类型检查 | `npx tsc -b` | 通过（exit 0）✅ |
| 前端 lint | `npx eslint .` | 通过（exit 0）✅ |
| 前端生产构建 | `npm run build` | 通过，7.47s ✅ |
| 覆盖率 | JaCoCo（agent 走 ASCII 路径） | **72.3%**（09-01 基线 64.4%）✅ |
| 分层纪律 | `grep -rl "Mapper " --include="*Controller.java"` | **11 个 Controller，0 处注入 Mapper** ✅ |
| 迁移完整性 | `ls db/migration` | V1–V14 连续（V7 在 `vendor-migration/h2+mysql` 方言目录）✅ |
| 密钥泄漏 | 扫描 `src/main/resources`、`deploy/` | 无硬编码（生产配置全走 `${DB_PASSWORD}` / `${JWT_SECRET}` 环境变量）✅ |

### 规模快照

| 项 | 数量 | 备注 |
| --- | --- | --- |
| 后端主源码 | 229 个 `.java` | `domain/entity` 31 + `domain/mapper` 31 为最大两块 |
| 后端测试 | 22 个测试类 / 115 用例 | 较 09-01（7 类 34 例）大幅增长 |
| 前端源码 | 28 个文件 | 最大单文件 157 行，无巨石 |
| 数据库迁移 | V1–V14 | V14 为退役死权限 |
| 文档 | 108 篇 md（含 30MB 厂商镜像） | 见 P1-2 |

---

## 三、问题清单

### P0 · 工作树跑出 3 个红灯，直接提交会让 CI 变红

已提交 HEAD 的 CI 是绿的，红灯全部来自未提交的 83 项改动。

| 失败用例 | 断言 | 实际 |
| --- | --- | --- |
| `BankDataAggregationTraceTest.traceChainsTaskRawSummaryNormalizedRecordsAndProjection` | `$.data.total` = 2 | 0 |
| `V02BackendIntegrationTest.statementsAndBankDataProjectionsAreIsolatedByAuthenticatedCompany` | `$.data.total` > 0 | 0 |
| `V02BackendIntegrationTest.bankDataProjectionRoutesEnforceTenantScopePermissionsAndSafeMockBoundary` | `$.data.simulated` = true | false |

**根因（已定位到具体代码）**：这是一次**有意的产品语义改造**——`BankDataQueryService` 新增 `realDirectConnected` 门控，只投影真实直联数据（源码注释：「模拟/测试数据已下线：仅展示真实银行直联数据」），投影资源从 5 类收窄到 `balances` / `statements` 两类。而 `RealCmbBankDataAdapter`、`RealCiticBankDataAdapter` 都返回 `executionMode() = REAL`，Bean 一旦注册 → `realDirectConnected = true` → `simulated = false`；测试环境又连不上真银行 → `total = 0`。

三个失败测试写的是**旧的 mock-first 契约**，改造没带上它们。

> 判断：这不是代码 bug，是**契约变更未同步测试**。但必须在提交前闭合，否则 CI 红。

### P1-1 · 默认 `mvn test` 依赖解析失败（citic-sdk profile）

```
[ERROR] Could not find artifact com.citicbank:isec-jce:jar:2.0.1.9 in central
[ERROR] Could not find artifact com.citicbank:logback-cfca:jar:4.1.1.0 in central
```

- `backend/pom.xml` 的 `citic-sdk` profile 设了 `activeByDefault=true`，拉取 5 个商业 jar（`com.citicbank:*`），这些不在 Maven Central。
- **CI 三处调用全部显式 `-P '!citic-sdk'`**（ci.yml L127 / L223 / L316）→ CI 从不验证真实 SDK 路径，真机行为无自动化覆盖。
- **本地仓库分裂**：厂商 jar 被装在全局 `~/.m2/repository/com/citicbank/`，而项目用 `-s .m2-settings.xml`（`localRepository` 指向 `backend/.m2-local`，180M）→ 两条仓库各有一半，任何一次不经意的组合都会构建失败。
- 附带：`backend/src/citic-sdk/java/.../adapter/` 是个空目录残留。

**建议**：把 `activeByDefault=true` 换成按 SDK 是否存在自动激活——

```xml
<activation>
  <file><exists>${user.home}/.m2/repository/com/citicbank/dlink-sdk-lib/4.1.3/dlink-sdk-lib-4.1.3.jar</exists></file>
</activation>
```

这样：装了 SDK 的人自动编译真机传输层，没装的人开箱即用，CI 无需 `-P` 特例。

### P1-2 · docs/ 混入 30MB 厂商 SDK，含 exe/dll 二进制

| 目录 | 体积 | 内容 |
| --- | --- | --- |
| `docs/cmb-clouddc/` | **26M** | 66 篇 md + `SMKeyTool.exe`(32KB) + `CMBSMDLLKY.dll`(2.4MB) + `Newtonsoft.Json.dll`(640KB) + 3 个 zip + `linklab/` 含 `.class` |
| `docs/kingdee-openapi/` | **4.6M** | 16 篇 md |

两者状态均为 `??`（未跟踪）——**下一次 `git add -A` 就会把 30MB 连 exe/dll 一起永久写进仓库历史**。

参照既往惯例（prts.wiki / Emuera 手册镜像都放在 D: 盘项目外），建议：文档镜像外置，或至少在 `.gitignore` 里排除 `docs/*/samples/`、`**/*.exe`、`**/*.dll`、`**/*.class`。

### P2-1 · CI 契约测试用 grep 断言源码文本，天然易碎

`release-contract` job 有 **35 条 `grep -q` 断言**，其中 **8 条 pin 在 `.java` 源码字面量**上，例如：

```
grep -q 'raw.setRetentionUntil(receivedAt.plusDays(30))' .../BankDataSyncEvidenceService.java
```

这等于把实现细节写进 CI——改个变量名、换个写法，行为没变 CI 也红。

**实证代价**：06:53 → 06:59 → 07:04 连续三个 `fix(ci)` 提交（`b7d9279`、`d8d0d17`、`8221b9d`），**每个都只改 `.github/workflows/ci.yml`**，修的全是断言本身而非产品代码。

建议：能转成行为断言的转成真测试（已有 22 个测试类的基建），grep 只保留给无法测的部分（如生产配置防泄漏）。

### P2-2 · 覆盖率盲区：旧的没收敛，新的又出现

| 包 | 覆盖率 | 变化 / 说明 |
| --- | --- | --- |
| `operations` | **5.6%** | 09-01 是 6.4% → **两天未改善还略降** |
| `operations.dto` | 0.0% | 同上 |
| `statement.kingdee` | **8.8%** | 新增（金蝶接入进行中，9/11 联调目标） |
| `bank.citic` / `bank.cmb` | 14.5% / 29.4% | legacy 转账通道，v0.4 已砍支付调拨 → 应随 V14 一并退役 |
| `rbac.dto` | 0.0% | DTO，低价值 |

`bankdata.adapter.cmb` 91.6%、`bankdata.adapter.citic` 90.9% —— 新接入线测试质量反而最好，说明团队知道怎么写测试，盲区纯粹是排期取舍。

### P2-3 · 前端主 chunk 795.70 kB（gzip 257 kB）

FIX-001 为修 P0 白屏移除了 `manualChunks`（commit `c268f38`），代价是主 chunk 从 553kB 涨到 795.70 kB。这是**已权衡的取舍**（构建绿 ≠ 运行绿），当前可接受，但建议登记进 `pending-fixes.md`，等 9/23 试用前再评估。

---

## 四、结构观察（非阻塞）

| 观察 | 判断 |
| --- | --- |
| `bank/`（账户 + legacy 转账）与 `bankdata/adapter/`（数据直联 SPI）双轨并存 | **不是重复造轮子**，是两代设计：前者是 v0.2 的模拟支付通道，后者是 v0.3 起的直联聚合 SPI。但 `bank/citic`(5 文件) + `bank/cmb`(1) 属 v0.4 已砍的转账能力，V14 已退役权限，**代码退役可一并做掉** |
| `domain/entity`(31) + `domain/mapper`(31) 平铺，与业务模块包并存 | 09-01 已提出的命名组织债，两天未动。功能无碍，纯可读性成本 |
| `bank/cmb/CmbBankService` 零外部引用 | **不是死代码**——它靠 Spring 收集 `Collection<BankService>` 注册，作用是让 `POST /api/bank-accounts` 接受 `bankCode=CMB`。源码注释已写明，判定为有意为之 |
| `tmp/`（121 文件，含 2 个 jar + 部署包 zip + 中信 SDK 文档） | 已被 gitignore（commit `3ed3bea`），属部署暂存区。建议定期清理，别无限堆积 |
| 金蝶网关三态设计（`Kingdee/Mock/Unavailable`） | 与银行 Adapter 的 Real/Mock 模式一致，**抽象复用得好** ✅ |

---

## 五、建议行动（按 ROI 排序）

| # | 优先级 | 行动 | 阻塞关系 |
| --- | --- | --- | --- |
| 1 | **P0** | 同步 3 个失败测试到新契约（`simulated=false` / real-only 投影），或给测试显式关掉 real adapter Bean | 提交前必须闭合 |
| 2 | **P1** | `citic-sdk` profile 改为按 SDK 文件存在激活，统一本地仓库（把 `~/.m2` 的厂商 jar 装进 `.m2-local`） | 解锁默认构建 |
| 3 | **P1** | `docs/cmb-clouddc/` + `docs/kingdee-openapi/` 外置或 gitignore 二进制 | 下次 `git add -A` 之前 |
| 4 | P2 | 补 `operations` 模块单测（5.6% → 目标 40%+） | 无 |
| 5 | P2 | 契约断言能转行为测试的转掉 | 无 |
| 6 | P2 | 退役 `bank/citic` + `bank/cmb` legacy 转账代码（随 V14） | 无 |

---

## 六、环境备忘（本次踩坑）

- **JaCoCo 中文路径崩溃**（沿用 09-01 结论，本次复现）：agent jar 须放 `C:/Users/Public/jacoco/agent.jar`，用 `-DargLine=-javaagent:...=destfile=C:/Users/Public/jacoco/jacoco.exec` 注入，再 `jacoco:report -Djacoco.dataFile=...` 指回生成。CI 是 Linux ASCII 路径，不受影响。
- **Git Bash 下 mvn 不可用**（classworlds 类加载失败）：后端 Maven 一律走 PowerShell + `mvn.cmd -s .m2-settings.xml`。
- 本次验证均在 `-P '!citic-sdk'`（CI 等价）下进行；该配置下收集中不含中信 SDK 传输层源码。
