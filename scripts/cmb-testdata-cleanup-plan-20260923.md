# 招行测试数据清理计划（2026-09-23，W17 包 A4）

> **状态：计划文档，本批未执行任何 SQL、未连任何数据库。** 执行须由用户确认待删清单后按 §4 流程进行。
>
> 依据：`docs/w17-workpackages-20260923.md` 包 A4（先查分布 → 清单确认 → 备份 → 删除 → 复查 → 记录）。

## 0. 关键事实（实地核实）

| 事实 | 值 | 出处 |
|---|---|---|
| 招行真实接入全链路闭环日 | **2026-09-03 17:18**（SUC0000 修复 jar 上线，重触发任务 id=3 SUCCEEDED，`bank_data_statement` 落库 9/2 真实流水 4 笔合计 0.27 元） | `docs/archive/2026-09-03-cmb-session/FINFLOW-CMB接通-部署工程师执行回执-20260903.md` §结论/§11 |
| 首批真实落库数据本身 | 9/2 的 4 笔流水（0.01/0.02/0.22/0.02 元），已被当时交付单确认为「招行测试环境真实数据」 | `docs/FINFLOW-银行连接模块-去模拟化清理-交付单-20260904.md` §结论 |
| 分界线口径 | `created_at < '2026-09-03'` 的招行测试数据为**待删候选**；接入闭环日（09-03 17:18）之后的数据一律不碰 | 本计划 §4 红线 |
| 表结构 | 四表均含 `bank_account_id`、`created_at`（`bank_data_raw_message` 的时间列名为 **`received_at`**，无 `created_at`） | `V4__bank_data_access_foundation.sql` / `V5__bank_data_balance_and_adapter_port.sql` |
| 账户映射现状 | 招行 7 个账户已 MAPPED（真实账户）；4 个中信测试账户 UNMATCHED | `docs/deploy-w12-report-20260921.md` §81 |

## 1. 第一步：只读分布查询（识别测试账户 vs 真实招行账户）

SSH 到 ECS 后进入 MySQL（容器名以线上为准），**全部只读 SELECT**：

```sql
-- 1a. 余额表：按 账户 × 月份 计数（识别每个账户的数据起止与体量）
SELECT bank_account_id,
       DATE_FORMAT(created_at, '%Y-%m') AS ym,
       COUNT(*) AS cnt
FROM bank_data_balance
GROUP BY bank_account_id, ym
ORDER BY bank_account_id, ym;

-- 1b. 流水表：同口径
SELECT bank_account_id,
       DATE_FORMAT(created_at, '%Y-%m') AS ym,
       COUNT(*) AS cnt
FROM bank_data_statement
GROUP BY bank_account_id, ym
ORDER BY bank_account_id, ym;

-- 1c. 同步任务：按 账户 × 月份（任务表是流水/余额的上游，删除范围须与其对齐）
SELECT bank_account_id,
       DATE_FORMAT(created_at, '%Y-%m') AS ym,
       COUNT(*) AS cnt,
       SUM(status = 'SUCCEEDED') AS succeeded_cnt
FROM bank_data_sync_task
GROUP BY bank_account_id, ym
ORDER BY bank_account_id, ym;

-- 1d. 原始报文：报文表无 bank_account_id，经 task 关联到账户（received_at 是它的时间列）
SELECT t.bank_account_id,
       DATE_FORMAT(m.received_at, '%Y-%m') AS ym,
       COUNT(*) AS cnt
FROM bank_data_raw_message m
JOIN bank_data_sync_task t ON t.id = m.task_id
GROUP BY t.bank_account_id, ym
ORDER BY t.bank_account_id, ym;

-- 1e. 账户清单：对照账户名/银行编码/状态，圈定「招行测试账户」实体
--     （测试账户通常 accountName 带测试字样、或 accountNumber 明显非真实 12 位招行账号）
SELECT id, bank_code, account_name, account_number, status, created_at
FROM bank_account
WHERE bank_code = 'CMB'
ORDER BY id;
```

**判定规则**（结合 1a-1e 输出人工判定）：

- 数据最早月份 **≥ 2026-09-03** 且账户属于已 MAPPED 的 7 个真实账户 → **真实账户，不碰**；
- 数据早于 2026-09-03、或账户名带测试字样、或映射 UNMATCHED 的 CMB 账户 → **测试账户，进入待删候选**；
- 9/2 那 4 笔流水（合计 0.27 元）属于当时的联调验证数据，虽在接入闭环日附近，按「接入日之前测试数据」口径一并列入候选，**是否删由用户在清单确认环节拍板**。

## 2. 第二步：生成待删清单（给用户确认）

用 §1 确认的 `<测试账户ID列表>`（下文以 `:acc_ids` 占位）生成清单：

```sql
-- 2a. 待删余额
SELECT bank_account_id, COUNT(*) AS rows_to_delete,
       MIN(created_at) AS earliest, MAX(created_at) AS latest
FROM bank_data_balance
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03'
GROUP BY bank_account_id;

-- 2b. 待删流水
SELECT bank_account_id, COUNT(*) AS rows_to_delete,
       MIN(created_at) AS earliest, MAX(created_at) AS latest
FROM bank_data_statement
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03'
GROUP BY bank_account_id;

-- 2c. 待删同步任务
SELECT bank_account_id, COUNT(*) AS rows_to_delete,
       MIN(created_at) AS earliest, MAX(created_at) AS latest
FROM bank_data_sync_task
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03'
GROUP BY bank_account_id;

-- 2d. 待删原始报文（经 task 关联；取任务 id 集合后按 task_id 删）
SELECT t.bank_account_id, COUNT(*) AS rows_to_delete
FROM bank_data_raw_message m
JOIN bank_data_sync_task t ON t.id = m.task_id
WHERE t.bank_account_id IN (:acc_ids)
  AND t.created_at < '2026-09-03'
GROUP BY t.bank_account_id;
```

> **注意**：raw_message 的删除边界跟随**任务**的 created_at（报文自身只有 received_at，且报文与任务是一对一的写入批次），保证外键引用链（statement/balance → raw_message_id → task_id）删除后不留孤儿。
>
> 若标准流水 `statement_record` / `bank_data_statement`（银行流水转入标准流水）中已有由这些测试流水转入的行，**一并列入清单**向用户说明（`WHERE bank_account_id IN (:acc_ids) AND created_at < '2026-09-03'` 同口径），由用户决定是否同删。

## 3. 第三步：备份（必须先行，删前不可跳过）

```bash
# ECS 上执行；备份四张表，按日期命名，保留至复查完成后再决定清理
mysqldump -h <RDS_HOST> -u <USER> -p <DBNAME> \
  bank_data_balance bank_data_statement bank_data_sync_task bank_data_raw_message \
  > /opt/finflow/backup/cmb-testdata-backup-$(date +%Y%m%d-%H%M%S).sql

# 校验备份非空且可读（行数 > 0、文件大小 > 1KB）
ls -lh /opt/finflow/backup/cmb-testdata-backup-*.sql
grep -c "INSERT INTO" /opt/finflow/backup/cmb-testdata-backup-*.sql
```

## 4. 第四步：执行删除（逐条精确 WHERE + 断言影响行数）

**红线**：

1. 只删 `bank_account_id IN (:acc_ids) AND created_at < '2026-09-03'` 的行——**不碰接入闭环日（2026-09-03 17:18）之后任何真实数据**；
2. 备份先行（§3 校验通过才允许执行 DELETE）；
3. 每条 DELETE 执行后**断言影响行数与 §2 清单完全一致**，不一致立即 STOP 并回查；
4. 删除顺序遵守外键依赖：`bank_data_statement` / `bank_data_balance` → `bank_data_raw_message` → `bank_data_sync_task`。

```sql
-- 4a. 流水（先记 affected rows，与 2b 清单比对）
DELETE FROM bank_data_statement
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03';
-- 断言：affected == 2b 各账户 rows_to_delete 之和

-- 4b. 余额
DELETE FROM bank_data_balance
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03';
-- 断言：affected == 2a 各账户 rows_to_delete 之和

-- 4c. 原始报文（跟随任务边界）
DELETE m FROM bank_data_raw_message m
JOIN bank_data_sync_task t ON t.id = m.task_id
WHERE t.bank_account_id IN (:acc_ids)
  AND t.created_at < '2026-09-03';
-- 断言：affected == 2d 各账户 rows_to_delete 之和

-- 4d. 同步任务（最后删，上游引用已清空）
DELETE FROM bank_data_sync_task
WHERE bank_account_id IN (:acc_ids)
  AND created_at < '2026-09-03';
-- 断言：affected == 2c 各账户 rows_to_delete 之和
```

## 5. 第五步：复查 + 记录

```sql
-- 复查：四表在该范围 count 必须全部为 0
SELECT
  (SELECT COUNT(*) FROM bank_data_balance  WHERE bank_account_id IN (:acc_ids) AND created_at < '2026-09-03') AS balance_left,
  (SELECT COUNT(*) FROM bank_data_statement WHERE bank_account_id IN (:acc_ids) AND created_at < '2026-09-03') AS statement_left,
  (SELECT COUNT(*) FROM bank_data_sync_task WHERE bank_account_id IN (:acc_ids) AND created_at < '2026-09-03') AS task_left,
  (SELECT COUNT(*) FROM bank_data_raw_message m JOIN bank_data_sync_task t ON t.id = m.task_id
     WHERE t.bank_account_id IN (:acc_ids) AND t.created_at < '2026-09-03') AS raw_left;
-- 断言：balance_left / statement_left / task_left / raw_left 全部 = 0

-- 真实数据完好性抽查：接入日之后数据条数与删除前 §1 分布一致
SELECT COUNT(*) FROM bank_data_statement WHERE created_at >= '2026-09-03';
SELECT COUNT(*) FROM bank_data_balance  WHERE created_at >= '2026-09-03';
```

- 复查通过后，将（账户清单、各表删除条数、备份文件路径与大小、执行时间、操作人）写入部署日志（追加到当次部署报告或 `docs/` 下新部署记录）。
- 备份文件保留至下一次部署复核无误后再按保留策略清理。

## 6. 遗留说明

- `bank_data_sync_task` 的 `request_id` 幂等键在删除后自然释放，同窗口重新同步会重新落库——属预期行为。
- 若测试账户本身已无保留价值，可另行走「档案移除」（`DELETE /api/bank-accounts/{id}` 软删，@TableLogic 过滤），**不在本计划范围内**，需用户单独确认。
- W17 包 F 的历史回补以本计划 §1 的分布输出作为「各账户实际接入日」的输入，执行本计划时请把 §1 查询结果一并留存。
