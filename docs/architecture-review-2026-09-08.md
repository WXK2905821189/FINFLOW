# FINFLOW 项目结构审阅 · 2026-09-08（下午）

> 基准：master @ `bc416d5` + 19 项未提交（18 项文档/制品未跟踪 + 1 项 PRD 修改）。全部结论现场实测。
> 距上午全量测试（@ `6e848ef`，192 项全绿）过去 5.5 小时，期间另一会话交付了 6 个提交。

## 一、总体结论

**结构健康且仍在爬坡，可继续盖楼。** 批次 1/2（一键转标准流水 + 可配置同步计划）落地质量良好：新代码自带测试、覆盖率不降反升（79.7% → 80.2%）、CI 在两轮自愈后回到绿。唯一 P1 是工作区卫生：`tmp/` 已堆积 **1.2 GB / 1357 个文件**，需要一次确认后清理。

## 二、自上午以来的变化（另一会话交付）

| 提交 | 内容 |
| --- | --- |
| `06fe772` | 银行账户档案管理：拖拽式账户归类（原生 DnD 零依赖）；改挂公司时事务内迁移历史流水/余额 company_id |
| `96bd35c` | 批次 1：银行流水一键转标准流水（行多选 + 幂等去重 + 溯源）+ 账户多选两级分组 |
| `d3e14e6` | 批次 2：可配置定时同步计划（**V25 bank_sync_schedule** + 种子 02:10）——调度器改 60s 心跳命中触发；禁整点半点护栏 |
| `7883d46` | 心跳测试改固定时钟，消除 CI runner 慢机竞态 |
| `749475f` | 同步计划方案 v1.1 文档 |
| `bc416d5` | 补漏：批次 1 漏 add `BankPipelineController`，CI 编译错修复 |

## 三、实测证据

| 项 | 命令/方式 | 结果 |
| --- | --- | --- |
| 后端全量测试（双真实 SDK profile 激活） | `mvn.cmd test`（全局 ~/.m2） | **203 项 / 0 失败 / 1 跳过**，BUILD SUCCESS 1m13s ✅ |
| 跳过项归因 | `UserAdminAndRbacIntegrationTest` | `Assumptions.assumeTrue` 前置条件，有意设计 ✅ |
| CI（已提交 HEAD） | `gh run list` | `bc416d5` 绿（run 34188075685）✅；此前 3 红（04:24–04:42）为批次 2 交付过程中的自愈链，见第五节 |
| 前端 tsc / eslint | `npx tsc -b` / `npx eslint .` | 双 0 错误 ✅ |
| 前端生产构建 | `npm run build` | 9.65s 成功；主 chunk 498.60 kB（gzip 167.74 kB）✅ |
| JaCoCo 覆盖率 | ASCII agent + report | **80.2%**（79.7% → 80.2%）；<40% 盲区与上午完全一致（legacy + 真网路径），无新增盲区 ✅ |
| 分层纪律 | Controller 注入 Mapper grep | **0 违例** ✅ |
| 迁移完整性 | V1–V25 连续（V7 方言目录），V25 就位；CI 迁移 job 绿 = 6 处断言同步到位 ✅ |
| 安全扫描 | AKID/LTAI/私钥 grep | 无泄漏，敏感值全 env ✅ |

## 四、问题清单

### P1 · `tmp/` 垃圾堆积 1.2 GB / 1357 文件

构成（按体积）：`deploy-bee5ca9` 193M、7 个历史后端 jar 各 80M（09-03/09-04 的交付产物）、release 镜像 81M×2、金蝶 SDK 解压包 8.5M×2、若干部署目录与日志。另有根目录残留 `tmp-mvn-full0917.txt`（0 字节）。**均未被 git 跟踪，删除零代码风险**，待确认后执行（清单见第七节）。

### P2 · 根目录 4 个部署包散落

`finflow-web-dist-20260907-batch4/fix.tar.gz`、`finflow-web-dist-20260908-archive/syncplan.tar.gz`（共 1.9M）——部署交付产物落在仓库根目录且未跟踪。建议统一挪入 `tmp/deploy-artifacts/`（或 `tmp/` 并入 P1 清理），并考虑 gitignore 加 `finflow-web-dist-*.tar.gz` 防误提交。

### P2 · 上帝类持续增长，建议纳入下批拆分

| 文件 | 09-01 | 09-08 上午 | 现在 | 驱动 |
| --- | --- | --- | --- | --- |
| `BankDataQueryService` | — | 915 | **949** | 跨公司查询 + 真直联门控持续往里堆 |
| `StatementService` | 415 | 415 | **509** | 批次 1「一键转标准流水」落点 |
| `BankDataQueryPage.tsx` | — | 543 | **588** | 离散筛选 + 计划卡片 |

均有测试兜底、不阻塞，但 `BankDataQueryService` 已逼近千行红线，建议下个特性批次开头先拆查询组装层。

### P2 · pathspec 漏 add 再次发生（流程问题，非技术问题）

`bc416d5` 与 09-07 V24 批次是**同一类错误复发**：攒批提交凭记忆列 pathspec，漏掉 `BankPipelineController`，CI 编译红一轮才补。技能/记忆里已有「pathspec 提交必须配 `git status --short` 复核」的规则，但未被并行会话遵守。**建议**：攒批提交改为 `git add -A` 前先 `git status` 全量过目（项目已无不该入库的目录——厂商二进制已外置），或至少在提交命令里把「status 复核」固化为习惯。

### 观察 · 并行会话遗留的 WIP

`docs/product-requirements.md` 有 143+/127- 未提交修改（PRD v0.4 → v0.5 真实直联阶段版升级稿），属另一会话工作成果，本次审阅**未动、未提交**，由其自行收尾。

## 五、CI 三红过程复盘（04:24 → 04:44）

```
d3e14e6 批次2 push     → 红（心跳竞态：慢机 runner 上建计划在当前分钟、fireIfDue 前翻页）
7883d46 心跳修固定时钟  → 红（漏 add BankPipelineController，Long→List 编译错）
749475f docs push      → 红（同一编译错，docs 提交未修代码）
bc416d5 补漏           → 绿（run 34188075685）
```

两轮失败均为交付过程问题（竞态 + 漏 add），非设计缺陷；心跳竞态的修法（覆写时钟消除翻页依赖）是正确模式。当前 HEAD 绿 + 工作树实测绿，**处于可提交状态**。

## 六、结构观察（非阻塞）

- **规模**：backend main 261 java / test 43；frontend 36 文件；docs 139 md。测试 203 项对 261 源文件，覆盖密度持续改善。
- **盲区不变**：`bank.citic` 14.5%、`bank.cmb` 29.4%（一代 legacy）、`dlink` 25.1%（真网路径）、`statement.collector` 36.5% —— 与上午一致，低风险；dlink 契约测试仍是下一档补测方向。
- 前端主 chunk 稳定在 ~498 kB / gzip 167 kB（600 kB 警告线内），无回归。
- `tmp/` 的 `ci-verify.log`、`test-sched6.log`（12:38）说明并行会话有自己的验证流程在跑。

## 七、待确认：`tmp/` 清理清单（P1）

以下全部未被 git 跟踪，删除零代码风险（历史交付 jar 均已在 ECS 部署，无需本地保留）：

| 类别 | 项 | 体积 |
| --- | --- | --- |
| 历史后端 jar ×7 | FINFLOW-backend-*.jar（09-03/09-04 各批次） | ~560M |
| 部署目录 ×3 | deploy-bee5ca9 / finflow-deploy-20260902 / finflow-release-20260904-mockclean | ~320M |
| release 镜像 | finflow-release-20260903-1745 / finflow-backend-jar-20260903(+.tar.gz) | ~230M |
| 金蝶 SDK 解压 | kd-sdk-java11/ + .zip（已 vendor 进 ~/.m2） | ~17M |
| 杂项 | 各类 *.log、tmp-mvn-full0917.txt、cmb-to-md.py 等过程脚本 | 少量 |
| 根目录部署包 ×4 | finflow-web-dist-*.tar.gz | 1.9M |

保留建议：今天（09-08）的部署目录若对应线上当前版本可暂留备份；其余可全清。**确认后我按清单执行（走回收站机制）。**

## 八、环境备忘（本次新增/复证）

- 漏带 `-DargLine` 的 fork VM 崩溃再次复现（本次审阅自己踩中）——`Tests run: 0` + `forked VM terminated` ≠ 代码问题。
- vite build 的 emptyDir 被本机 safe-delete shim 拦截 → `mv dist tmp-dist-old` 移开再 build 可绕过。
- 技能 `finflow-architecture-review` 已同步修订：citic-sdk profile 描述从 `activeByDefault` 更新为按 jar 存在激活；本地推荐裸 `mvn.cmd test`（全局 ~/.m2 双 SDK 全覆盖）。
