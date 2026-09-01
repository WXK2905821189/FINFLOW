# FINFLOW 架构审阅报告

日期：2026-09-01 ｜ 审阅人：WB ｜ 方式：代码实测（非文档转述），所有结论附证据

## 一、总评

**基础架构是优良的，可以放心在其上继续盖楼。** 分层纪律、扩展主轴（银行 Adapter）设计、配置管理、技术卫生都达到或超过同类项目水准。债务集中且已知：前端单文件巨石（App.tsx 647 行）和一个职责过宽的后端服务类（BankDataSyncService 625 行），均为可控、可渐进偿还的债务，不动摇根基。

## 二、评分卡

| 维度 | 评价 | 关键证据 |
| --- | --- | --- |
| 分层纪律 | ★★★★★ | 12 个 Controller **0 个**直接注入 Mapper（grep 实测）；DTO 按业务包隔离；构造器注入风格全局统一；`final` 字段注入无 setter 注入 |
| 可扩展性（主轴：新增银行） | ★★★★★ | `BankDataAdapter` SPI + 注册表路由，新增银行 = 1 个实现类 + 注册，聚合层零改动（CITIC_MOCK/CMB_MOCK 已验证此路径）；三处端口-适配器同构（`statement/collector`、`statement/kingdee`、`bankdata/adapter`）风格一致 |
| 配置管理 | ★★★★★ | profile 三分离（dev/prod/base）；敏感值全部走环境变量占位；`PAYMENT_ENABLED` 默认 false，legacy 支付双保险关闭 |
| 技术卫生 | ★★★★☆ | **0 个 TODO/FIXME**（grep 实测）；Deprecated 明确标注（3 个文件）且不删历史代码；扣一星：无 ESLint 配置 |
| 数据库演进 | ★★★★☆ | Flyway V1–V12 全版本化、命名可读；V7 按 H2/MySQL 分目录处理方言差异是正确做法；扣一星：31 张表无 ER 图文档 |
| 测试 | ★★★☆☆ | 30/30 通过，覆盖幂等/隔离/脱敏等关键行为；但 `V02BackendIntegrationTest` 789 行大杂烩、共享 H2 库存在跨类污染坑（PIT-032 已记录）；无覆盖率统计 |
| 可读性（后端） | ★★★★☆ | 方法名达意、注释解释"为什么"而非"是什么"（如脱敏约束、幂等决策均有出处）；扣一星见问题 P1-B |
| 可读性（前端） | ★★☆☆☆ | **App.tsx 647 行内嵌 30 个组件**，是全项目最大可读性债；其余分层（api/http/store/types/navigation）干净 |

## 三、架构优点（应保持的资产）

1. **分层零违例**（实测）：Controller → Service → Mapper 单向依赖，无越层取数；公共能力下沉到 `common/`（api 包装、异常、租户隔离）。
2. **端口-适配器是全项目统一语言**：外部世界（银行、金蝶、文件采集）一律接口 + Mock 实现 + 注册表/装配选择，业务逻辑不感知具体供应商。这使「真实银行调用为 0」可以渐进升级而不动业务代码。
3. **幂等、追溯、租户隔离是一等公民**：syncKey 幂等、TASK_REUSED 审计、双请求号追溯链、跨企业 404 不可区分、原始报文只出摘要——这些约束写在代码里且被测试锁定，不依赖口头约定。
4. **legacy 隔离策略正确**：旧 `bank` 支付包保留编译但默认关闭 + Deprecated 标注，v0.4 砍范围不留僵尸调用。
5. **CI 已存在**：`.github/workflows/ci.yml` 含 release-contract / mysql-migration / backend / frontend 四个 job（注意基线停在 a7528f5，见 P3-A）。
6. **DTO 白名单式脱敏**：追溯响应 7 字段固定断言，银行专有字段从契约层就进不来。

## 四、问题清单（按优先级）

### P1-A 前端 App.tsx 单文件巨石（可读性最大债）
647 行、30 个组件、23 个页面挤在一个文件。types/index.ts（354 行）同样平铺。
**影响**：任何页面改动都要在这个文件里找位置；合并冲突高发；新页面继续往里塞会指数恶化。
**建议**：v0.4 拆为 `modules/<域>/pages/`，路由骨架与 API 层不动，纯文件搬家 + `tsc` 验证，一次 commit 完成。预估半天。

### P1-B BankDataSyncService 职责过宽（已知债务，v0.3 文档已点名）
625 行、**12 个注入依赖**，一个类同时负责：触发同步、幂等解析、任务/作业查询、投影查询、连接列表、调度入口、日志写入、复用审计。
**影响**：改任何一块都要读懂整类；12 依赖是「上帝类」前兆。
**建议**：先拆查询侧（`listStatements/listBalances/queryProjection/listTasks/getTaskDetail` → `BankDataQueryService`，约 300 行），触发侧保留。聚合层与追溯服务不受影响。

### P2-A 权限编码存在三重别名
同一触发动作存在 `bankdata:sync` / `bankdata:sync:trigger` / `bank-sync:trigger` 三个编码并存（BankPipelineController 注解实测）。权限模型有历史包袱，每新增端点都要猜该用哪套。
**建议**：出一张「语义 → 唯一编码」收敛表进 docs，旧编码保留兼容但停止新增；顺手清理 V4 种子里的死权限 `bankdata:payment:view`（与 v0.4 范围冲突，之前已报告）。

### P2-B 前端 types/index.ts 平铺
354 行所有域的类型混排。拆 App.tsx 时按域同步拆分即可，不单独立项。

### P3-A CI 基线落后
ci.yml 最后更新于 a7528f5（v0.2 期间）。`mvn test` 类 job 会自动带上 V12 和新测试，但 **release-contract / mysql-migration 两个 job 是否覆盖 V12 的 `mapping_version` 列与双目录 V7，需人工核对一次**（本机无 MySQL，无法替 CI 验证）。

### P3-B ESLint 缺失
`pnpm lint` 必然失败（无任何 eslint 配置）。建议加最小 flat config（TS 推荐规则 + react-hooks），不追严格度。

## 五、扩展性专项：接入第一家真实银行的路径评估

| 步骤 | 改动面 | 评估 |
| --- | --- | --- |
| 实现 `BankDataAdapter`（拉流水/余额、方向词/币种归一） | 新增 1 个包 | ✅ 路径已验证 |
| 注册到 `BankDataAdapterRegistry` + `mappingVersion` | 1 行注册 | ✅ 零侵入 |
| 错误码 → 统一八态映射 | Adapter 内部 | ✅ 有 PENDING/UNKNOWN/EMPTY 先例 |
| **限流/重试/超时策略** | **无抽象层** | ⚠️ MOCK 不覆盖，接真实银行前需先补（当前不做是正确的 YAGNI） |
| 证书/签名 | 不做（v0.4 边界） | ✅ 边界已写进 PRD |

结论：扩展主轴健康。唯一真实缺口是真实银行的异常行为层（限流/重试），建议在接第一家真实银行时作为前置任务。

## 六、建议行动（按 ROI 排序，待拍板）

| # | 行动 | 成本 | 收益 |
| --- | --- | --- | --- |
| 1 | App.tsx 按域拆分 + types 拆分 | ~0.5 天 | 可读性最大债清偿，后续前端迭代提速 |
| 2 | BankDataQueryService 拆分查询职责 | ~0.5 天 | 消除上帝类前兆，测试可按域拆 |
| 3 | 权限编码收敛表 + 死权限清理 | ~2 小时 | 消除每次加端点的决策成本 |
| 4 | ESLint 最小配置 + CI 基线核对 | ~1 小时 | 补上质量门禁缺口 |

以上均为渐进重构，不阻塞 v0.3 收尾；建议攒批为一次 commit（符合你「大特性攒批再推」的偏好）。
