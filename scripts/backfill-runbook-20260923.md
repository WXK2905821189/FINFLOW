# 历史数据回补 Runbook（2026-09-23，W17 包 F）

> **执行时点：部署收口之后跑一次。** 本文档只是操作手册，不在 CI/部署流程中自动执行。
> 回补端点：`POST /api/bank-data/backfill`（权限 `bankdata:sync:trigger`），受理后后台推进，
> 进度看 `bank_data_sync_task` 表 `trigger_type='BACKFILL'` 的记录。

## 0. 代码侧事实（本 runbook 的依据）

| 项 | 值 | 出处 |
|---|---|---|
| 流水分片 | ≤90 天/片，逐片调既有同步管道，requestId=`backfill:{accountId}:{chunkStart}:{chunkEnd}` | `BankDataBackfillService.STATEMENT_CHUNK_DAYS` |
| 中信流水 | DLTRNALL 追溯 3 年、窗口 ≤92 天、单页 ≤20 条 | `docs/citic-bank-interface-dev-guide.md` §6 |
| 中信历史余额 | DLHBLQRY 窗口 ≤30 天/次 | 同上 §5.4 |
| 招行流水 | trsQryByBreakPoint 近 13 个月；更早需客户经理开通（默认有效期 1 年） | `docs/w17-workpackages-20260923.md` F0 |
| 招行历史余额 | NTQABINF 区间 ≤31 天/次且**必须早于当日**（切片上界=昨日） | `docs/cmb-clouddc/markdown/7.查询账户历史余额NTQABINF.md` |
| 限速 | 片间 sleep 2s + 单账户串行；聚合层另有 60 req/min 护栏 | `BankDataBackfillService.chunkIntervalMillis`、`BankAdapterCallProperties.maxRequestsPerMinute` |
| 去重 | 流水:复合键(company,account,statementNo,transactionTime,amount)；余额:(company,account,asOfTime) 唯一键 → 同区间重跑幂等 | `V4/V5` 迁移唯一键 |

## 1. 执行前置（代码之外的动作，缺一不可）

1. **招行 13 个月以外的流水**：先找招行客户经理开通历史流水查询权限（默认 1 年有效期）。
   未开通前，招行回补区间不要超过 13 个月，否则更早的片会整体报错（片失败不影响后续片，但浪费时间）。
2. **各账户接入日**：等包 A4 的分布查询产出后填进下面 §3 的表格。回补区间 = 接入日 − 1 天
   往前推到银行上限（中信 3 年 / 招行 13 个月或开通后上限）。
3. 确认线上 `bankdata.adapter.{citic,cmb}.real-enabled=true` 且
   `bankdata.adapter.call.real-adapters-enabled=true`（真实调用总闸）。

## 2. 拿 token

```bash
# ⚠️ 登录会踢掉该账号的现有会话——用专门的运维账号，不要用正在用系统的同事账号。
TOKEN=$(curl -s -X POST https://<host>/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<运维账号>","password":"<密码>"}' | jq -r '.data.accessToken')
```

## 3. 按账户回补（区间 = 接入日 − 1 天往前推）

> 接入日待包 A4 产出后填写；下表先占位。**historyEnd 缺省 = 昨日**（银行侧约束），
> 也可显式传 `"historyEnd"` 覆盖。

| 账户 | accountId | 银行 | 接入日（A4 填） | historyStart（=接入日−1 天往前推上限） |
|---|---|---|---|---|
| 待填 | | 中信 | ____ | ____（约接入日 − 3 年） |
| 待填 | | 招行 | ____ | ____（约接入日 − 13 个月，开通后可再往前） |

单账户回补（流水 + 历史余额一起；只跑其一就传 `"statements":false` 或 `"balances":false`）：

```bash
curl -s -X POST https://<host>/api/bank-data/backfill \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "accountId": <accountId>,
    "historyStart": "2024-09-23",
    "historyEnd": "2026-09-22",
    "statements": true,
    "balances": true
  }'
```

全部 ACTIVE 账户一次回补（省略 accountId）：

```bash
curl -s -X POST https://<host>/api/bank-data/backfill \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"historyStart": "2024-09-23", "historyEnd": "2026-09-22"}'
```

响应是受理即返回：`{"data":{"runTaskId":<id>, "message":"Backfill accepted ..."}}`。

## 4. 执行时间预估（中信为例）

- 流水：3 年 ÷ 90 天/片 ≈ 13 片；每片还要按 20 条/页翻页（页数取决于流水密度）。
- 历史余额：3 年 ÷ 30 天/片 ≈ **36+ 片/账户**。
- 片间 sleep 2s + 每片银行响应 0.5–2s，纯余额 36 片 ≈ 2–3 分钟/账户；流水页数多时按
  每页 ~1s 估算。中信查询限频 400 笔/小时（dev-guide §7），编排层 60 req/min 护栏之下
  不会触发银行侧限频，但**不要并发跑多个账户**——编排层已经单线程串行，别再开第二个 run。

## 5. 进度观测

```sql
-- 受理任务（汇总行）
SELECT id, task_no, adapter_code, status, error_message, window_start, window_end
FROM bank_data_sync_task WHERE trigger_type = 'BACKFILL' ORDER BY id DESC;

-- 各片明细（error_message 是汇总文本；流水片 status 直接来自同步管道）
SELECT status, COUNT(*) FROM bank_data_sync_task
WHERE trigger_type = 'BACKFILL' GROUP BY status;
```

全部片完成后汇总行 `status=SUCCEEDED`（有失败片则 `PARTIAL`，失败明细看各片 `error_message`）。
失败片修复条件后**直接重跑同区间即可**——requestId 幂等 + 落库去重保证不产生重复行。

## 6. 验收

1. **查询页**：流水/余额查询页把时间筛选拨到接入日以前（如 2025-06）能看到历史数据；
2. **任务表**：`bank_data_sync_task` 有 `trigger_type='BACKFILL'` 记录；
3. **幂等**：重跑同区间后
   ```sql
   -- 应为 0
   SELECT bank_account_id, as_of_time, COUNT(*) c FROM bank_data_balance
   GROUP BY bank_account_id, as_of_time HAVING c > 1;
   SELECT bank_account_id, statement_no, transaction_time, amount, COUNT(*) c
   FROM bank_data_statement GROUP BY bank_account_id, statement_no, transaction_time, amount
   HAVING c > 1;
   ```

## 7. 回滚/止损

回补只写既有表（流水/余额/任务/日志/原始报文），无迁移、无 schema 变更。异常时先停
（无独立开关——重启应用即中断后台线程，未完成片保持 RUNNING，重跑同区间会复用已完成片）；
需要清数据时按 `task_id = <BACKFILL 任务 id>` 反查 `bank_data_statement` /
`bank_data_balance` / `bank_data_raw_message` 删除后重跑。
