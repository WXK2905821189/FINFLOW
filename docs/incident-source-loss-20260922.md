# 后端源码消失事件 · 快照与恢复手册（2026-09-22 18:15）

> **2026-09-22 20:15 事件闭环更新**：本报告写于事故进行中，下述「不可恢复」结论
> **已被推翻——最终零净损失**。保留原文供过程复盘，结论以本框为准。
>
> - 18:06 首轮异动：`backend/src/main` 417 文件消失。恢复：核对陈旧 index.lock
>   （时间戳=事故时刻、无 git 进程）→ 删锁 → `git checkout -- backend/src/main/`
>   全部恢复（HEAD = dc869ff，与 GitHub 远端一致）。
> - 18:39 二轮异动：恢复文件再现 143 D + 7 AD + 3 RD，`.git/index` mtime=18:39:40
>   ——查明为**并行会话（W16-A1）正在同一工作区实时 git 操作**（形成
>   AiVoucherJob→BankPushJob 重命名暂存）。立即停止写操作并汇报；用户确认并行会话
>   属正常行为后核实：并行会话已将 V43 等 10 个「未跟踪新文件」重新暂存进 index，
>   19 文件整齐入暂存区、main 树 375 文件完好。
> - **误判修正**：①「V43 等 10 文件不可恢复」→ 实为已被并行会话重新暂存；
>   ②「EngineService -388 行丢失」→ 实为暂存区 +17 行（新增 previewOne 重载）。
> - 防线沉淀：git bundle 快照（`C:/tmp/finflow-safety-bundle-20260922-1808.bundle`，
>   17.8MB）兜底；并行会话期间对暂存清单之外的文件才可安全写入。

## 事件
18:04–18:06 之间，`backend/src/main/java`（375 个文件）与 `backend/src/main/resources/db`（42 个 Flyway 迁移）
从磁盘消失。`backend/src/main` 目录 mtime = 18:06:09。本会话（WB）同期只做只读操作；
并行会话（W16 批次）此前也在同一工作区工作。

## 损失评估（原文，已被上述闭环更新推翻）
- ✅ **可完整恢复**：全部已提交内容（HEAD = dc869ff，与 GitHub 远端 master 一致）
- ❌ **不可恢复（git 无副本）**：并行会话的未提交工作——
  - 未跟踪新文件 10 个：BankPushJob.java、BankPushJobMapper.java、BankDataPushController/Service.java、
    BankPushJobService.java、dto/PushBatchRequest、PushBatchResult、PushJobResponse、PushRowResult、
    PushSubmitResponse、V43__bank_push_job_drop_ai_voucher_job.sql
  - 修改未提交：KingdeeVoucherEngineService.java（-388 行）、BankDataExportService、BankDataQueryService、
    BankPipelineController、module-notes.md、pending-fixes.md、frontend 5 文件（后 5 个已在磁盘幸存）
- target/classes 里无 BankPushJob 编译产物（未曾编译成功或已清理），无悬空 git 对象、无 stash。

## 立即已做的保护
- git bundle 全量快照：`C:/tmp/finflow-safety-bundle-20260922-1808.bundle`（17.8 MB，--all）
- 本文件本身是恢复手册。

## 恢复步骤（原文，实际执行见顶部闭环更新）
1. 恢复已提交内容（安全、无损）：
   `git checkout -- backend/src/main/`
   （工作区其他幸存的修改文件不受影响；-M 状态文件不会被 checkout 覆盖——git 只恢复 D 状态路径）
   注意：checkout 会把 parallel 会话的 10 个未跟踪文件需求「复活为 HEAD 版本」的旧文件恢复，
   但 KingdeeVoucherEngineService.java 在 git status 是 D（消失）→ 恢复为 HEAD 版（含 AI 制证逻辑），
   丢 -388 行改动（无法避免）。
2. 恢复后 `git status` 应只剩：M（幸存修改）+ ??（docs 三份报告）。
3. 离线跑 `mvn.cmd test -o -Djacoco.skip=true` 验证全绿。
4. V43 迁移号在恢复后空缺（HEAD 最新为 V42）——并行会话回来后重取号。

## 待用户回答（已闭环）
- ~~18:04–18:06 用户或另一会话是否有删除/移动/杀毒扫描动作？~~ → 二轮异动证实为并行会话 git 操作
- ~~并行会话（W16 批次）是否还在运行？~~ → 在运行且已完成暂存（用户确认属正常行为）
