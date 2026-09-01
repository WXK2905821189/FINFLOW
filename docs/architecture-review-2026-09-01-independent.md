# FINFLOW 项目结构独立审阅（复核版）

日期：2026-09-01 18:30 ｜ 审阅人：WB（独立会话）｜ 方式：不依赖前一份审阅结论，逐项代码实测

## 一、总体结论

**结构健康，可继续在其上盖楼。** 上一轮审阅（`architecture-review-2026-09-01.md`）指出的最大债务（前端巨石、后端上帝类）已确认清偿；当前剩余问题均为低优先级清理项，无阻塞性风险。

## 二、独立实测证据

| 核验项 | 实测结果 |
| --- | --- |
| 前端拆分 | App.tsx 647→**123 行**，types/index.ts 354→**41 行**，28 个文件按域拆入 `modules/`（最大单文件 157 行） |
| 后端拆分 | BankDataSyncService 625→**284 行**，拆出 Executor(396)/QueryService(386)/Assembler/Trace 等专职类 |
| 分层纪律 | 12 个 Controller **0 处**直接注入 Mapper（grep 实测） |
| 权限收敛 | `V13__permission_code_convergence.sql` 已存在，并纳入 CI 契约检查 |
| 质量门禁 | ESLint flat config 已就位（`frontend/eslint.config.js`） |
| 测试 | 7 个测试类 **34 用例全绿**（surefire 报告 2026-09-01 18:02–18:03） |
| 前端构建 | `npm run build` 14.7s 通过，路由懒加载+分包生效（antd 主 chunk 553kB 有超限警告，可接受） |
| CI | 四 job 存在；release-contract 对迁移版本序列（V1–V13）、脱敏行为、prod 配置做 grep 级契约断言 |

## 三、评分卡

| 维度 | 评价 | 备注 |
| --- | --- | --- |
| 分层纪律 | ★★★★★ | 零违例，公共能力下沉 common/ |
| 可扩展性（银行接入） | ★★★★★ | Adapter SPI + 注册表 + 统一调用边界，主轴健康 |
| 配置/安全 | ★★★★★ | profile 三分离，敏感值全走环境变量，CI 有防泄漏断言 |
| 数据库演进 | ★★★★☆ | V1–V13 全版本化，V7 方言分目录；仍缺 ER 图文档 |
| 测试 | ★★★☆☆ | 关键行为有锁定，但 34 例 vs 215 文件、无覆盖率统计、集成测试偏大杂烩 |
| 可读性（前端） | ★★★★☆ | 拆分后达标；`services/api.ts`(136 行) 集中所有 API 调用，暂可接受 |

## 四、遗留问题（按优先级）

1. **P2 工作区垃圾**：根目录 `domain/`、`tmp/` 空目录残留（未被 git 跟踪）；`frontend/node_modules.corrupt2/` 为 gitignored 损坏依赖残留。建议清理。
2. **P2 包归属轻微不一致**：RbacService/SysUserService/BootstrapDataInitializer 位于 `domain/service`，与其业务模块包（rbac/user）割裂。
3. **P3 antd 主 chunk 553kB** 超 500kB 警告，暂不必处理。
4. **P3 测试覆盖盲区**：statement/closing/feishu/operations 无独立单测，仅靠 V02 集成测试兜底。

## 五、跟进行动记录（同日执行）

| # | 行动 | 状态 |
| --- | --- | --- |
| 1 | 报告存档（本文件） | ✅ 完成 |
| 2 | RbacService→`rbac/`、SysUserService→`user/`、BootstrapDataInitializer→`config/`，4 个引用文件 import 同步更新；grep 确认 0 处 `domain.service` 残留 | ✅ 源码层完成，**编译/测试验证待并行会话改动落定后补跑**（18:38 起有另一会话正在删除 legacy 支付代码，期间编译必然失败，非本次改动导致） |
| 3 | JaCoCo 0.8.12 加入 `backend/pom.xml`（prepare-agent + report 绑定 test 阶段），`mvn test` 后报告输出至 `backend/target/site/jacoco/` | ⏳ 配置完成，基线报告待首次运行生成 |
| 4 | 清理空目录与 node_modules.corrupt2 | ⏸ 待用户确认清单 |
