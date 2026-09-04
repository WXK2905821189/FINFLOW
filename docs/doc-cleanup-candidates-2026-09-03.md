# docs 文档清理候选清单

> 状态：**✅ 已执行（2026-09-03 12:01，用户拍板删除全部 12 份）** ｜ 编写：WB · 2026-09-03
> 背景：docs/ 现有 30 份 git 跟踪文档 + 若干近期新增。以下为经引用核查（grep 全 docs）确认**无外部引用断链风险**的纯历史/已被替代文档。
> 删除方式：`rm` 直接删除；git 跟踪内，可用 `git restore docs/` 完整恢复。

## 一、推荐删除（12 份，均为历史一次性产物）

| # | 文件 | 大小 | 类型 | 删除理由 |
| --- | --- | --- | --- | --- |
| 1 | `architecture-review-2026-09-01.md` | 7.0K | 评审快照 | 2026-09-01 架构审阅报告；结论已由 09-01 independent 复核版确认债务清偿，且 v0.3 模块化已落地，内容被超越 |
| 2 | `architecture-review-2026-09-01-independent.md` | 4.3K | 评审快照 | 同上（独立复核版），仅互相引用 |
| 3 | `deployment-aliyun-2026-09-01.md` | 20.2K | 部署手册旧版 | 文档头部自标"无控制台权限请改用新版"；权威版为 `deployment-aliyun-2026-09-02-noconsole.md`。本版为"有控制台"参考，与用户实际（无控制台权限）不符 |
| 4 | `v0.1.0-release-notes.md` | 7.3K | 版本发布记录 | v0.1 历史发布记录，版本已迭代至 v0.3；pending-fixes 中引用仅为文字提版本号，非链接 |
| 5 | `v0.1.0-test-report.md` | 13.2K | 测试报告 | v0.1 一次性测试报告 |
| 6 | `v0.2/devops-phase1-release-validation.md` | — | 发布验证报告 | v0.2 第一阶段 DevOps 验证记录（执行日 08-27），一次性 |
| 7 | `v0.2/devops-phase2-release-validation.md` | — | 发布验证报告 | v0.2 第二阶段验证记录，一次性 |
| 8 | `v0.2/phase-1-quality-security-retest-report.md` | — | 复验报告 | v0.2 第一阶段质量安全复验，一次性 |
| 9 | `v0.2/system-test-plan.md` | — | 测试方案 | v0.2 全量测试方案；引用 bank-data-test-plan（同批删，不断链） |
| 10 | `v0.2/v0.2-system-test-report.md` | — | 测试报告 | v0.2 系统测试执行报告，一次性 |
| 11 | `v0.2/bank-data-test-plan.md` | — | 测试方案 | v0.2 银行接入测试计划（SANDBOX 用例），随 v0.2 验证批次过时 |
| 12 | `v0.2/bank-data-ui-checklist.md` | — | UI 验收清单 | v0.2 银行管道 UI 验收清单（适用版本 v0.2），无人引用 |

## 二、建议保留（勿动）

| 文档 | 保留理由 |
| --- | --- |
| `product-requirements.md` | PRD，长期维护 |
| `quality-security-checklist.md` | 质量门禁清单；显式引用 auto/operations test-plan（§159/§163） |
| `ui-specification.md` / `permission-catalog.md` | 规格与权限目录，随版本维护 |
| `pending-fixes.md` | 活跃待修清单（部署侧登记/全栈侧回填） |
| `deployment-aliyun-2026-09-02-noconsole.md` / `deployment-guide.md` / `maintenance-sop.md` / `environment-inventory.md` | 部署运维权威文档 |
| `auto-accounting-test-plan.md` / `operations-module-test-plan.md` | 被 quality-security-checklist 引用，属活跃执行指引 |
| `function-points.md` / `tech-architecture.md` / `v0.3-module-architecture.md` | 当前功能/架构基线 |
| `bank-connect-schedule-2026-09-03.md` / `citic-bank-interface-dev-guide.md` / `kingdee-openapi-knowledge-base.md` / `kingdee-openapi/` | 银行/金蝶接入进行中资产 |
| `v0.2/bank-data-runbook.md` / `common-pitfalls.md` / `bank-data-delivery-backlog.md` / `product-module-gap-closure.md` / `phase-2-product-scope.md` | v0.2 运行手册/避坑/backlog，仍有运维参考价值 |
| `frontend-engineering-plan.md` / `backend-engineering-plan.md` | 工程计划（v0.2 时代）；无引用但含路线上下文，**低确定项**，建议保留或人工复核 |

## 三、执行建议（拍板后）

```bash
# 推荐删除集（git 跟踪内，可恢复）
cd docs
rm architecture-review-2026-09-01.md architecture-review-2026-09-01-independent.md \
   deployment-aliyun-2026-09-01.md v0.1.0-release-notes.md v0.1.0-test-report.md \
   v0.2/devops-phase1-release-validation.md v0.2/devops-phase2-release-validation.md \
   v0.2/phase-1-quality-security-retest-report.md v0.2/system-test-plan.md \
   v0.2/v0.2-system-test-report.md v0.2/bank-data-test-plan.md v0.2/bank-data-ui-checklist.md
# 恢复：git restore docs/
```

若确认执行，回复"删除"即可；也可指定只删其中若干份。
