# 权限编码目录（V13 收敛后基线）

> 状态：生效中 · 基线迁移：`V13__permission_code_convergence.sql`
> 本文是唯一权威权限清单。新增端点/页面时只允许引用本文中的编码；需要新编码时先在本文登记，再落代码。

## 命名规范

`<domain>:<resource>:<action>`（无子资源时为 `<domain>:<action>`）

- domain：业务域（bankdata / statement / validation / closing / feishu / operation / connection / user / role / audit / dashboard / bank / transfer / data）
- resource：域内资源（balance / statement / receipt / reconciliation / payroll / sync / log / monitor）
- action：view / manage / create / review / import / push / trigger / approve / execute / notify / retry / query

## 有效权限清单（35 项）

| 编码 | 名称 | 授予角色 | 引用位置 |
|---|---|---|---|
| dashboard:view | 查看工作台 | ADMIN, FINANCE_STAFF, FINANCE_MANAGER, VIEWER | 前端路由守卫 |
| transaction:view | 查看交易 | 全部角色 | 旧 bank 包（保留兼容） |
| user:manage | 用户管理 | ADMIN | /users 路由守卫 |
| bank:view | 查看银行账户 | 全部角色 | /bank-access/accounts |
| bank:manage | 管理银行账户 | ADMIN | 旧 bank 包（保留兼容） |
| transfer:create / transfer:approve / transfer:execute | 调拨发起/审批/执行 | ADMIN+FINANCE_STAFF / FINANCE_MANAGER / ADMIN+FINANCE_STAFF | 旧 bank 包（保留兼容，v0.4 不做前端） |
| role:manage | 角色管理 | ADMIN | RoleController |
| statement:view | 查看流水 | 全部角色 | 流水批次/对账页 |
| statement:import | 导入流水 | ADMIN, FINANCE_STAFF | 导入页 |
| statement:review | 流水复核 | ADMIN, FINANCE_MANAGER | 人工复核页 |
| voucher:push | 金蝶制证 | ADMIN, FINANCE_STAFF | 制证页 |
| reconciliation:view | 查看对账 | 全部角色 | 对账页 |
| connection:view / connection:manage | 连接查看/管理 | ADMIN+FINANCE_MANAGER / ADMIN | 连接配置页 |
| operation:monitor | 采集运营监控 | ADMIN, FINANCE_MANAGER | 直联监控/任务页 |
| operation:log:view | 日志查询 | ADMIN, FINANCE_MANAGER | 日志查询页 |
| data:query | 数据查询 | 全部角色 | 旧 bank 包（保留兼容） |
| bankdata:view | 银行数据总查看 | 全部角色 | 任务列表、追溯、旧版查询端点 |
| bankdata:sync:trigger | **触发银行数据同步（规范编码）** | ADMIN, FINANCE_STAFF | 手动触发同步、按筛选创建同步任务 |
| bankdata:reconciliation:view | 对账单投影 | 全部角色 | 对账单查询页 |
| bankdata:balance:view | 余额投影 | 全部角色 | 余额查询页 |
| bankdata:statement:view | 流水投影 | 全部角色 | 流水查询页 |
| bankdata:receipt:view | 回单投影 | 全部角色 | 回单查询页 |
| bankdata:payroll:view | 代发投影 | 全部角色 | 代发查询页 |
| feishu:view / feishu:manage / feishu:notify / feishu:retry | 飞书查看/管理/通知/重试 | 全部 / ADMIN / ADMIN+FINANCE_STAFF / ADMIN+FINANCE_STAFF | 飞书协同页 |
| validation:view / validation:manage | 规则查看/管理 | 全部 / ADMIN | 规则与映射页 |
| closing:view / closing:manage | 结账查看/管理 | 全部 / ADMIN+FINANCE_MANAGER | 结账管理页 |
| audit:view | 审计中心 | ADMIN, FINANCE_MANAGER, VIEWER | 审计中心页 |

## 已收敛的别名（禁止再引用）

| 废弃编码 | 原权限 ID | 语义 | 收敛目标 | 处置 |
|---|---|---|---|---|
| bankdata:sync | 21 | 触发模拟银行数据同步（V4 旧名） | bankdata:sync:trigger | V13 删除，持权角色先回填 29 |
| bank-sync:trigger | 23 | 触发受控银行同步任务（V4 旧名，域前缀不符规范） | bankdata:sync:trigger | V13 删除，持权角色先回填 29 |
| bankdata:payment:view | 27 | 查看支付投影 | 无（v0.4 范围外） | V13 删除，无任何端点引用 |

### 收敛原则（历史教训）

同一动作的三个编码并存（V4 引入两个、V9 又加一个），导致控制器、前端、种子数据三处都要写 `hasAnyAuthority(...)` 兜底，每次新增端点都要猜编码。规则：

1. **一个动作一个编码**：新需求先查本表，语义重复时收敛而非新增；
2. **迁移而非硬编码兜底**：别名问题用 Flyway 迁移收口（回填→删授予→删权限），不在 `@PreAuthorize` 里堆 `hasAnyAuthority`；
3. **兼容期可选**：若旧客户端仍在用旧编码，可保留一个发布周期的双编码 + 日志埋点统计旧编码命中，到期再删——本次三个编码均无外部客户端，直接收敛。

## 变更记录

- 2026-09-01 V13：删除 bankdata:sync(21)、bank-sync:trigger(23)、bankdata:payment:view(27)；触发同步统一为 bankdata:sync:trigger(29)；控制器（BankPipelineController / BankDataController）与前端（operations.tsx / BankDataQueryPage.tsx）同步收敛。
