# FINFLOW UI 按钮点击测试清单

- 建立日期：2026-09-17
- 测试对象：前端 `frontend/src`（工作区当前版本，含未提交 WIP）
- 测试方式：Playwright 驱动 Chromium，**真实鼠标事件**（`Input.dispatchMouseEvent`）逐一点击，非 DOM 直接触发
- 清单状态：**v1 待执行**（后续每执行一轮更新「状态」列与底部「执行记录」）

---

## 1. 测试环境

| 项 | 值 | 备注 |
|---|---|---|
| 前端 | Vite dev server `http://127.0.0.1:5173` | `frontend/`，proxy `/api` → `localhost:8080` |
| 后端 | Spring Boot `http://127.0.0.1:8080` | profile 默认 `dev`，**H2 内存库**（`jdbc:h2:mem:finance`） |
| 数据库 | H2 in-memory + Flyway V1–V34 | 无需 RDS，重启即重置 |
| 浏览器 | Chromium（playwright build 1234） | 无头 + 有头双模式 |
| 真实外部调用 | **全部默认关闭** | `bank.citic.mock-mode=true`、`kingdee.mock-mode=true`、`BANKDATA_REAL_ADAPTERS_ENABLED=false`、`AI_ENABLED=false` |

**环境隔离说明**：本清单全程跑在本地 H2 内存库，不触碰 ECS 生产环境与 RDS。破坏性用例（删除/停用/结账）执行后如需重来，重启后端即可回到初始种子数据。

---

## 2. 用例编号规则

`TC-<页面代号>-<两位序号>`，页面代号见下表。优先级：

- **P0**：冒烟级。登录、导航可达、每页首屏无白屏/无 5xx、核心查询出数。
- **P1**：核心功能。主操作按钮、筛选、提交、弹窗确认链路。
- **P2**：边界与次要。导出、复制、空态、重置、分页细节、权限 disabled 态。

**破坏性标记**：`⚠写`（改动后端数据）、`⚠外`（触发外部系统调用）、`⚠不可逆`（删除类，本地可通过重启复位）。

---

## 3. 测试用例清单

### 3.1 登录与鉴权 `/login`（LoginPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-LOGIN-01 | 输入框 | `账号` | 可输入 | — | P0 | | 待执行 |
| TC-LOGIN-02 | 输入框 | `密码` | 密文显示 | — | P0 | | 待执行 |
| TC-LOGIN-03 | 按钮 | `进入工作台` | 凭证正确 → 跳 `/dashboard`，token 落库 | POST `/auth/login` | P0 | | 待执行 |
| TC-LOGIN-04 | 按钮 | `进入工作台` | 错误密码 → 提示 `账号或密码错误` | POST `/auth/login` | P1 | | 待执行 |
| TC-LOGIN-05 | 空提交 | — | 前端表单校验拦截，不发请求 | — | P2 | | 待执行 |

### 3.2 全局壳层 Shell（shell/Shell.tsx、navigation/productNavigation.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-SHELL-01 | 侧栏折叠触发器 | Sider 默认触发器 | 侧栏收至 64px，再点展开 | — | P1 | | 待执行 |
| TC-SHELL-02 | 一级菜单 | `工作台` | 跳 `/dashboard` | — | P0 | | 待执行 |
| TC-SHELL-03 | 一级菜单 | `银行数据` | 展开子菜单 | — | P0 | | 待执行 |
| TC-SHELL-04 | 一级菜单 | `凭证与入账` | 展开子菜单 | — | P0 | | 待执行 |
| TC-SHELL-05 | 一级菜单 | `系统管理` | 展开子菜单 | — | P0 | | 待执行 |
| TC-SHELL-06 | 二级菜单 ×16 | （见 3.4–3.20 各页） | 逐项跳转并渲染 | — | P0 | | 待执行 |
| TC-SHELL-07 | 菜单展开态持久化 | — | 刷新后 `openKeys` 保持（localStorage `finflow.product-menu-open`） | — | P2 | | 待执行 |
| TC-SHELL-08 | 用户菜单触发器 | `{username}` | 展开 Dropdown | — | P1 | | 待执行 |
| TC-SHELL-09 | 用户菜单项 | `{email}` | **无 onClick**，点击无反应（预期不是 bug，仅记录） | — | P2 | | 待执行 |
| TC-SHELL-10 | 用户菜单项 | `退出登录` | 清 token → 跳 `/login` | POST `/auth/logout` | P0 | | 待执行 |
| TC-SHELL-11 | 顶栏标题 | `FINFLOW / 企业财务工作台` + h1 | 随路由切换同步为 `pageTitles` 对应值 | — | P2 | | 待执行 |
| TC-SHELL-12 | 移动端导航按钮 | `aria-label="打开导航"` | 打开 Drawer，点链接后自动关闭 | — | P2 | | 待执行 |

### 3.3 权限矩阵（横切）

| ID | 场景 | 预期 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|
| TC-AUTHZ-01 | 无 `dashboard:view` 访问 `/dashboard` | 渲染 403 页 | P1 | | 待执行 |
| TC-AUTHZ-02 | 403 页按钮 `返回可访问页面` | 跳转到当前角色首个可访问页 | P1 | | 待执行 |
| TC-AUTHZ-03 | 无权限的菜单项 | 不渲染（菜单按 `hasPermission` 过滤，非 disabled） | P1 | | 待执行 |
| TC-AUTHZ-04 | 无权限的路由直敲 URL | 跳 `/403` | P1 | | 待执行 |

### 3.4 工作台 `/dashboard`（dashboard/pages.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-DASH-01 | 首屏 | — | 汇总卡 + 任务对账表渲染，无白屏 | GET `/reconciliation/dashboard`、`/bank-data/task-reconciliation` | P0 | | 待执行 |
| TC-DASH-02 | 链接按钮 | `前往凭证中心` | 跳 `/statements/vouchers` | — | P1 | | 待执行 |
| TC-DASH-03 | 按钮 | `刷新` | 重新拉取任务对账卡 | GET `/bank-data/task-reconciliation` | P1 | | 待执行 |
| TC-DASH-04 | 通用错误重试 | `重试` | 任一接口失败时出现，点击重发 | 同上 | P2 | | 待执行 |
| TC-DASH-05 | 空态 | `当前角色没有财务汇总查看权限` | 无 `reconciliation:view` 时显示 Empty | — | P2 | | 待执行 |

### 3.5 银行账户 `/bank-access/accounts`（bank-access/pages.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-ACCT-01 | 首屏 | — | 账户表格渲染 | GET `/bank-accounts` | P0 | | 待执行 |
| TC-ACCT-02 | 按钮 | `档案管理` | 打开 Drawer `账户档案管理` | GET `/bank-account-archive` | P1 | | 待执行 |
| TC-ACCT-03 | 按钮 | `新增账户` | 打开 Modal `新增银行账户` | — | P1 | | 待执行 |
| TC-ACCT-04 | Modal 表单 | `户名`/`账号`/`银行`/`币种`/`初始余额（可选）`/`归属公司主体（可选）` | 各字段可输入；银行识别 chip 出 `已识别：{bankName}` 或 `未识别 —— 请手动选择` | — | P1 | | 待执行 |
| TC-ACCT-05 | Modal 主按钮 | `创建账户` | 创建成功，列表刷新 | POST `/bank-accounts` | P1 | ⚠写 | 待执行 |
| TC-ACCT-06 | 行内链接 | `测试连通` | 打开 Modal `连通测试 · {bankName}`，footer 仅 `知道了` | POST `/bank-accounts/{id}/test-connection` | P1 | ⚠外 | 待执行 |
| TC-ACCT-07 | 公司主体识别 | — | 公司下拉为 `{value,label}` 映射，不出现裸 ID | — | P2 | | 待执行 |

### 3.6 档案板（Drawer 内，bank-access/archive.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-ARCH-01 | 按钮 | `新建档案` | 新建公司主体成功 | POST `/bank-account-archive/companies` | P1 | ⚠写 | 待执行 |
| TC-ARCH-02 | 输入框 | `新公司主体名称，如：XX 有限公司深圳分公司` | 回车等同点击 | 同上 | P2 | | 待执行 |
| TC-ARCH-03 | 按钮 | `新增银行账户` | 打开 Modal，okText `创建` | POST `/bank-accounts` | P1 | ⚠写 | 待执行 |
| TC-ARCH-04 | 按钮 | `AI 智能归类` | 打开 Modal `AI 智能归类 · 预览确认` | POST `/bank-account-archive/ai-suggest-companies` | P1 | ⚠外 | 待执行 |
| TC-ARCH-05 | AI 预览确认 | `应用勾选行` | disabled 无勾选；勾选后应用成功 | POST `/bank-account-archive/ai-apply-companies` | P2 | ⚠写 | 待执行 |
| TC-ARCH-06 | 行内按钮 | `测试连接` | Modal `连接测试 · {accountName}`，footer=null | POST `/bank-accounts/{id}/test-connection` | P2 | ⚠外 | 待执行 |
| TC-ARCH-07 | 行内删除 | `删除` | Popconfirm `从档案移除该账户？` → `移除` | DELETE `/bank-accounts/{id}` | P2 | ⚠不可逆 | 待执行 |
| TC-ARCH-08 | 公司卡编辑图标 | （仅图标） | Modal `重命名公司档案：{name}` → `保存` | PUT `/bank-account-archive/companies/{id}` | P2 | ⚠写 | 待执行 |
| TC-ARCH-09 | 拖拽 | 账户卡 → 公司投放区 | Modal.confirm `确认账户归类` → `确认归类` | PUT `/bank-account-archive/accounts/{id}/company` | P2 | ⚠写 | 待执行 |
| TC-ARCH-10 | 拖拽 | 账户卡 → `未归属 · 待 AI 归类` 区 | Modal.confirm `确认取消归属` | POST `/bank-account-archive/accounts/{id}/unassign` | P2 | ⚠写 | 待执行 |
| TC-ARCH-11 | 只读镜像提示 | `只读镜像` | 字典中心公司主体为只读，编辑/删除入口不渲染 | — | P2 | | 待执行 |

### 3.7 同步任务 `/bank-access/tasks`（bank-access/operations.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-TASK-01 | 首屏 | — | 任务列表 + 定时计划卡渲染 | GET `/bank-sync-jobs`、`/bank-sync-schedules` | P0 | | 待执行 |
| TC-TASK-02 | 按钮 | `补拉历史数据` | 打开 Modal，okText `创建补拉任务` | — | P1 | | 待执行 |
| TC-TASK-03 | 表单校验 | `补拉日期区间` >90 天 | 报错 `单次最多补拉 90 天，窗口过长会被银行拒绝` | — | P1 | | 待执行 |
| TC-TASK-04 | Modal 主按钮 | `创建补拉任务` | Modal.confirm `确认补拉历史数据` → `确认创建` → 创建成功 | POST `/bank-sync-jobs` | P1 | ⚠写 | 待执行 |
| TC-TASK-05 | 筛选 | `任务类型`/`连接标识`/`任务状态`/`请求编号` + `查询` | 按条件过滤 | GET `/bank-sync-jobs` | P1 | | 待执行 |
| TC-TASK-06 | 筛选重置 | `重置` | 清空条件 | — | P2 | | 待执行 |
| TC-TASK-07 | 行内 | `详情` | Drawer `同步任务 · {jobNo}`，含 Timeline | GET `/bank-sync-jobs/{id}` | P1 | | 待执行 |
| TC-TASK-08 | Drawer 内链接 | `查看该请求的脱敏日志与审计追溯` | 跳 `/operations/logs?requestId=...` | — | P2 | | 待执行 |
| TC-TASK-09 | Tag 内按钮 | `停用` / `启用` | 切换计划启用态 | PUT `/bank-sync-schedules/{id}/enabled/{bool}` | P1 | ⚠写 | 待执行 |
| TC-TASK-10 | Tag 关闭叉 | （closable ×） | **无二次确认**直接删除计划 | DELETE `/bank-sync-schedules/{id}` | P2 | ⚠不可逆 | 待执行 |
| TC-TASK-11 | TimePicker + 按钮 | `新增时刻` + `添加` | 未选提示 `请先选择执行时刻`；整点/半点报错 `不能选择整点/半点...`；合法时刻创建成功 | POST `/bank-sync-schedules` | P1 | ⚠写 | 待执行 |
| TC-TASK-12 | 分页 | — | `total>size` 时才渲染 | — | P2 | | 待执行 |

### 3.8 运行日志 `/bank-access/logs`

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-LOG-01 | 首屏（免查询） | — | 打开即加载日志列表 | GET `/bank-data/sync-logs` | P0 | | 待执行 |
| TC-LOG-02 | 筛选 | `请求编号`（回车）/`级别`/`结果` + `查询` | 过滤生效 | 同上 | P1 | | 待执行 |
| TC-LOG-03 | 重置 | `重置` | 清空条件 | — | P2 | | 待执行 |

### 3.9 原始报文 `/bank-access/raw-messages`（RawMessagesPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-RAW-01 | 首屏 | — | 报文列表渲染 | GET `/bank-data-raw-messages` | P0 | | 待执行 |
| TC-RAW-02 | 筛选 | `账户标识`/`任务号`/`适配器代码（如 CMB）`/`开始时间`/`结束时间` + `查询` | 过滤生效 | 同上 | P1 | | 待执行 |
| TC-RAW-03 | 重置 | `重置` | 清空条件 | — | P2 | | 待执行 |
| TC-RAW-04 | 行内 | `查看报文` | Drawer `银行原始报文 · {id}` | GET `/bank-data-raw-messages/{id}` | P1 | | 待执行 |
| TC-RAW-05 | Tabs | `银行原文`/`请求要素`/`解析视图` | 切换正常，按数据条件渲染 | — | P1 | | 待执行 |
| TC-RAW-06 | 按钮 | `重放校验（当前规则 vs 当年视图）` | disabled=`!hasBankRaw`；点击返回校验差异 | POST `/bank-data-raw-messages/{id}/replay` | P1 | ⚠写 | 待执行 |
| TC-RAW-07 | 按钮 | `复制银行原文` | 写入剪贴板 | — | P2 | | 待执行 |
| TC-RAW-08 | 按钮 | `下载解析视图` | 本地 Blob 下载（无接口） | — | P2 | | 待执行 |
| TC-RAW-09 | 分页 | `10/20/50` | 切换页大小重查 | — | P2 | | 待执行 |

### 3.10 余额查询 `/bank-access/data/balances`（BankDataQueryPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-BAL-01 | 首屏 | — | 引导态（`submitted=false`），不发查询请求 | — | P0 | | 待执行 |
| TC-BAL-02 | 下拉 | `账户（不选=全部；跨公司视图按公司分组）` | 多选，选项按银行分组（`招商银行`/`中信银行`） | — | P1 | | 待执行 |
| TC-BAL-03 | 下拉 | `公司主体（全部）` / `公司主体（仅本公司）` | 前者仅 `bankdata:cross-company:view`；后者无权限时 disabled + Tooltip | — | P1 | | 待执行 |
| TC-BAL-04 | 输入框 | `账号后 4/6 位` | 回车触发查询，参数 `accountNoSuffix` | GET `/bank-data/balances` | P1 | | 待执行 |
| TC-BAL-05 | 下拉 | `币种` | 选项 `人民币`（CNY），即选即查 | 同上 | P1 | | 待执行 |
| TC-BAL-06 | DatePicker | `开始时间` / `结束时间` | 选完即查 | 同上 | P1 | | 待执行 |
| TC-BAL-07 | 主按钮 | `查询` | 首次查询：离开引导态并出数 | GET `/bank-data/balances` | P0 | | 待执行 |
| TC-BAL-08 | 按钮 | `重置` | 清空全部筛选并回到引导态 | — | P1 | | 待执行 |
| TC-BAL-09 | Popover | `列设置` | 打开列显隐面板 | — | P1 | | 待执行 |
| TC-BAL-10 | 复选项 | `户名`/`联机余额`/`冻结余额`/`账户状态`/`校验状态` | 勾选即时显隐列，随账号保存（服务端偏好） | PUT `/account-preferences` | P2 | | 待执行 |
| TC-BAL-11 | 链接按钮 | `恢复默认` | 重置为全可见 | — | P2 | | 待执行 |
| TC-BAL-18 | Popover 面板 | `列设置` 面板行 | 每行**无拖动手柄（⋮⋮）**；列序提示为「表头拖拽或 ↑/↓ 调列序」（W17 包 C） | — | P2 | | 待执行 |
| TC-BAL-19 | 面板按钮 | 列设置面板 `↑` / `↓` | 点击上移/下移对应列，列序即时生效并随账号保存 | PUT `/account-preferences` | P1 | | 待执行 |
| TC-BAL-20 | 面板按钮 | 列设置面板 `恢复默认` | 列显隐/列序/冻结全部还原为默认基准（含权限变化后的列集合） | PUT `/account-preferences` | P1 | | 待执行 |
| TC-BAL-12 | 按钮 | `导出 CSV` | `submitted` 后才渲染；下载 blob（BOM+CRLF） | GET `/bank-data/balances/export` | P1 | | 待执行 |
| TC-BAL-13 | 按钮 | `按所选账户创建同步任务` | 未选账户 → warning `请先在「账户」下拉中选择要同步的银行账户`；已选 → confirm `确认创建同步任务` → `确认创建` | POST `/bank-sync-jobs` | P1 | ⚠写 | 待执行 |
| TC-BAL-14 | 行内 | `查看` | 打开 Drawer `银行余额字段 · {id}`，**不发请求** | — | P1 | | 待执行 |
| TC-BAL-15 | 复制按钮 | `aria-label="复制可用余额"` | 复制成功 → message `已复制` | — | P2 | | 待执行 |
| TC-BAL-16 | 分页 | `10/20/50` | 切换重查 | GET `/bank-data/balances` | P1 | | 待执行 |
| TC-BAL-17 | 空态 | — | 无数据时 Table Empty 正常，无 JS 报错 | — | P2 | | 待执行 |

### 3.11 流水查询 `/bank-access/data/statements`

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-STMT-01 | 首屏 + `查询` | `查询` | 出流水数据 | GET `/bank-data/statements` | P0 | | 待执行 |
| TC-STMT-02 | 左侧主体树 | `公司主体 / 账户`（节点 `{name}（N 户）`） | 点选账户 → 联动 `accountIds`；再点取消；`defaultExpandAll` | 同上 | P1 | | 待执行 |
| TC-STMT-03 | 主体树公司节点 | `{name}（N 户）` | 点选 → 按 `companyId` 过滤 | 同上 | P1 | | 待执行 |
| TC-STMT-04 | 表头漏斗 · 借贷 | Segmented `全部`/`收（贷）`/`付（借）` | onChange 即查 | 同上 | P1 | | 待执行 |
| TC-STMT-05 | 表头漏斗 · 金额 | `最小金额`/`最大金额` + `筛选` | 带符号金额过滤（收款正/付款负） | 同上 | P1 | | 待执行 |
| TC-STMT-06 | 表头漏斗 · 金额 | `清除` | 清空金额条件并重查 | 同上 | P2 | | 待执行 |
| TC-STMT-07 | 表头漏斗 · 流水号 | `流水号关键字` + `筛选`/`清除` | 过滤生效 | 同上 | P1 | | 待执行 |
| TC-STMT-08 | 表头漏斗 · 收付方 | `收付方名称关键字` + `筛选`/`清除` | 过滤生效 | 同上 | P1 | | 待执行 |
| TC-STMT-09 | 行内 | `查看` | Drawer `银行流水字段 · {statementNo\|\|id}` | — | P1 | | 待执行 |
| TC-STMT-10 | 行复选框 | — | 仅 `voucher:push` 时渲染；`transferred=true` 或 `accountingMode='MANUAL'` 时 disabled | — | P1 | | 待执行 |
| TC-STMT-11 | 按钮 | `AI 制证为草稿（N）` | 未勾选 disabled/warning；确认后返回结果 Modal | POST `/bank-data/statements/ai-voucher`(DRAFT) | P1 | ⚠写 | 待执行 |
| TC-STMT-12 | 按钮 | `AI 制证并推送（N）` | 确认后推送 | POST `/bank-data/statements/ai-voucher`(PUSH) | P2 | ⚠写⚠外 | 待执行 |
| TC-STMT-13 | 结果 Modal | `去「凭证草稿与制证」审核推送` | draftCount>0 时渲染，跳 `/statements/vouchers` | — | P2 | | 待执行 |
| TC-STMT-14 | 结果 Modal | `知道了` | 关闭 | — | P2 | | 待执行 |
| TC-STMT-15 | Drawer 内 | `查看本次报文` | 需 `bankdata:raw:view` 且行有 `rawMessageId`；打开报文 Drawer | GET `/bank-data-raw-messages/{id}` | P1 | | 待执行 |
| TC-STMT-16 | Collapse | `银行原始字段（技术明细，日常对账一般不用）` | 展开显示原始字段 | — | P2 | | 待执行 |
| TC-STMT-17 | 链接 | `查看脱敏审计追溯` | 跳 `/operations/logs?requestId=...` | — | P2 | | 待执行 |
| TC-STMT-18 | 报文 Tabs | `银行原文`/`请求要素`/`解析视图` | 切换正常 | — | P2 | | 待执行 |
| TC-STMT-19 | 导出 | `导出 CSV` | 36 列对账契约、2 万行上限 | GET `/bank-data/statements/export` | P1 | | 待执行 |
| TC-STMT-20 | 分页 | `10/20/50` | 切换重查 | GET `/bank-data/statements` | P1 | | 待执行 |

### 3.12 凭证中心 `/statements/vouchers`（VoucherCenterPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-VOU-01 | 首屏 + `刷新` | `刷新` | 凭证组列表渲染/重载 | GET `/statements/voucher-groups` | P0 | | 待执行 |
| TC-VOU-02 | Segmented | `全部`/`待复核`/`待推送`/`已推送`/`推送失败` | 切状态重查 | 同上 | P1 | | 待执行 |
| TC-VOU-03 | 搜索框 | `凭证号 / 流水号 / 摘要 / 对手方` | 回车或点搜索 → page=1 重查 | 同上 | P1 | | 待执行 |
| TC-VOU-04 | 行内 Link | `查看凭证` | 跳 `/statements/voucher-doc/{statementId}` | — | P1 | | 待执行 |
| TC-VOU-05 | 行内按钮 | `通过` | 仅 `PENDING`；confirm `确认通过该凭证草稿（{statementNo}）` | POST `/statements/batch-review` | P1 | ⚠写 | 待执行 |
| TC-VOU-06 | 行内按钮 | `驳回` | Modal okText `确认驳回` | POST `/statements/batch-review`(REJECT) | P1 | ⚠写 | 待执行 |
| TC-VOU-07 | 行内按钮 | `推送` | 仅 APPROVED 未推送；confirm `确认推送金蝶（{statementNo}）` | POST `/statements/batch-push` | P1 | ⚠写⚠外 | 待执行 |
| TC-VOU-08 | 行内按钮 | `追溯` | Drawer `追溯记录 · {statementNo}` | GET `/statements/{id}` | P1 | | 待执行 |
| TC-VOU-09 | 并发锁 | — | 提交中 `disabled=busyRowId!=null`，防重复提交 | — | P2 | | 待执行 |

### 3.13 凭证单据详情 `/statements/voucher-doc/:statementId`（VoucherDocPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-VDOC-01 | 首屏 | — | 单据 + 分录渲染 | GET `/statements/{id}` | P0 | | 待执行 |
| TC-VDOC-02 | 按钮 | `返回` | `navigate(-1)` | — | P1 | | 待执行 |
| TC-VDOC-03 | 按钮 | `打印` | 触发 `window.print()` | — | P2 | | 待执行 |
| TC-VDOC-04 | 借贷平衡提示 | `借贷平衡` / `借贷不平衡` | 按金额计算正确显示 | — | P1 | | 待执行 |

### 3.14 大类规则 `/voucher-rules`（CategoryRulesPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-RULE-01 | 首屏 + `刷新` | `刷新` | 22 条规则清单渲染 | GET `/kingdee/voucher-rules` | P0 | | 待执行 |
| TC-RULE-02 | 行内 | `模板` | Modal `模板详情 · 规则 {ruleNo} {businessType}`，footer `知道了` | — | P1 | | 待执行 |

### 3.15 科目与往来规则（规则中心 `/voucher-rules` 的「校验与入账映射」页签）

> **2026-09-21 校准**：原独立页 `/validation` 已在 W4（2026-09-18）合并进规则中心，该路由只保留兼容重定向（见 3.23）。
> 下表用例改为**在规则中心内切到该页签后执行**；`statements/ValidationPage.tsx` 现只导出内嵌组件 `ValidationEmbedded`，不再导出页面组件（空壳 `ValidationPage` 已删除）。

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-VALID-01 | 首屏 | — | 默认为 `校验规则` Tab | GET `/validation/rules` | P0 | | 待执行 |
| TC-VALID-02 | Tab 按钮 | `入账映射` / `校验规则` | 切换列表 | GET `/validation/mappings` | P1 | | 待执行 |
| TC-VALID-03 | 按钮 | `新建草稿` | 打开 Modal `新建校验规则草稿` 或 `新建入账映射草稿` | — | P1 | | 待执行 |
| TC-VALID-04 | 表单字段 | `规则编码`/`规则名称`/`规则类型`/`表达式/说明`/`优先级` | 可输入，必填校验生效 | — | P1 | | 待执行 |
| TC-VALID-05 | 表单字段 | `映射编码`/`映射名称`/`方向`/`对方关键字`/`借方科目`/`贷方科目`/`凭证模板` | 可输入 | — | P1 | | 待执行 |
| TC-VALID-06 | 行内 | `启用` | 仅 `validation:manage` 且非 ACTIVE；激活成功 | POST `/validation/rules/{id}/activate` | P1 | ⚠写 | 待执行 |
| TC-VALID-07 | 分页 | — | 翻页重查 | — | P2 | | 待执行 |

### 3.16 账期结账 `/closing`（closing/pages.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-CLOSE-01 | 首屏 | — | 账期列表渲染 | GET `/closing/periods` | P0 | | 待执行 |
| TC-CLOSE-02 | 输入 + 按钮 | `账期 YYYY-MM` + `检查账期` | 返回检查结果 | POST `/closing/periods/{period}/check` | P1 | ⚠写 | 待执行 |
| TC-CLOSE-03 | 按钮 | `确认结账` | `closing:manage`；confirm `确认结账 {period}` | POST `/closing/periods/{period}/close` | P1 | ⚠写⚠不可逆 | 待执行 |
| TC-CLOSE-04 | 分页 | — | 翻页 | — | P2 | | 待执行 |

### 3.17 审计中心 `/audit`（audit/pages.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-AUDIT-01 | 首屏 | — | 事件列表渲染 | GET `/audit/events` | P0 | | 待执行 |
| TC-AUDIT-02 | 筛选（onChange 即查） | `动作`/`对象类型`/`请求编号` | 输入即过滤，无查询按钮 | 同上 | P1 | | 待执行 |
| TC-AUDIT-03 | 分页 | — | 翻页 | — | P2 | | 待执行 |

### 3.18 用户与角色 `/users`（admin/UsersPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-USER-01 | Tab | `账号管理` | 账号列表渲染 | GET `/users` | P0 | | 待执行 |
| TC-USER-02 | 按钮 | `刷新` | 重载 | 同上 | P2 | | 待执行 |
| TC-USER-03 | 按钮 | `新增账号` | Modal `新增账号` → `保存` | POST `/users` | P1 | ⚠写 | 待执行 |
| TC-USER-04 | 表单 | `用户名`/`邮箱`/`手机号`/`状态`/`角色（至少一个）`/`初始密码` | 校验生效 | — | P1 | | 待执行 |
| TC-USER-05 | 行内 | `编辑 / 重置密码` | Modal `编辑账号：{username}` → `保存` | PUT `/users/{id}` | P1 | ⚠写 | 待执行 |
| TC-USER-06 | 行内 | `停用` / `启用` | Popconfirm `停用该账号？`/`启用该账号？` | PUT `/users/{id}` | P1 | ⚠写 | 待执行 |
| TC-USER-07 | Tab | `角色与权限` | 角色列表渲染 | GET `/rbac/roles` | P0 | | 待执行 |
| TC-USER-08 | 按钮 | `新建角色` | Modal `新建角色` → `创建` | POST `/rbac/roles` | P1 | ⚠写 | 待执行 |
| TC-USER-09 | 行内 | `编辑权限` | Drawer `编辑角色权限：{roleLabel}` → `保存` | PUT `/rbac/roles/{id}` | P1 | ⚠写 | 待执行 |
| TC-USER-10 | ADMIN 行 | `编辑权限` | **disabled** + Tooltip（超管角色不可改） | — | P1 | | 待执行 |

### 3.19 字典中心 `/system/dicts`（admin/DictionaryPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-DICT-01 | 首屏 | `字典类型` | 类型列表渲染 | GET `/system/dicts/types` | P0 | | 待执行 |
| TC-DICT-02 | 图标按钮 | 刷新（仅图标） | 重载类型 | 同上 | P2 | | 待执行 |
| TC-DICT-03 | 按钮 | `新建类型` | Modal `新建字典类型` → 保存 | POST `/system/dicts/types` | P1 | ⚠写 | 待执行 |
| TC-DICT-04 | 行内 | `查看项` | 右侧渲染字典项列表 | GET `/system/dicts/types/{id}/items` | P1 | | 待执行 |
| TC-DICT-05 | 行内 | `编辑` | Modal `编辑字典类型` → 保存 | PUT `/system/dicts/types` | P2 | ⚠写 | 待执行 |
| TC-DICT-06 | 行内 | `删除` | Popconfirm 动态文案（含项数） | DELETE `/system/dicts/types/{id}?force=` | P2 | ⚠不可逆 | 待执行 |
| TC-DICT-07 | 按钮 | `新建字典项` | 未选类型/只读镜像时 disabled | POST `/system/dicts/types/{typeId}/items` | P1 | ⚠写 | 待执行 |
| TC-DICT-08 | 行内 | `属性` | 有 extraJson 才渲染；Drawer `扩展属性 · {label}` | — | P2 | | 待执行 |
| TC-DICT-09 | 行内 | `编辑`（项） | Modal `编辑字典项` → 保存 | PUT `/system/dicts/items/{id}` | P2 | ⚠写 | 待执行 |
| TC-DICT-10 | 行内 | `删除`（项） | Popconfirm `确定删除该字典项？` | DELETE `/system/dicts/items/{id}` | P2 | ⚠不可逆 | 待执行 |
| TC-DICT-11 | 只读校验 | `company_entity` 类型 | 显示 `只读镜像`，编辑/删除不渲染 | — | P1 | | 待执行 |

### 3.20 AI 设置 `/system/ai-settings`（admin/AiSettingsPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-AISET-01 | 首屏 + `刷新` | `刷新` | 配置表单渲染 | GET `/ai/config` | P0 | | 待执行 |
| TC-AISET-02 | Switch | `AI 总开关` | 切换启用/停用态 | — | P1 | | 待执行 |
| TC-AISET-03 | 输入框 | `接入点（OpenAI 兼容 Base URL）` | 失焦自动拉模型 | POST `/ai/config/models` | P1 | ⚠外 | 待执行 |
| TC-AISET-04 | 密码框 | `API 密钥` | **只写不读**，回显为空/掩码 | — | P1 | | 待执行 |
| TC-AISET-05 | AutoComplete + 按钮 | `模型` + `拉取模型` | 未填 Base URL → 提示 `请先填写接入点 Base URL` | POST `/ai/config/models` | P1 | ⚠外 | 待执行 |
| TC-AISET-06 | 数字输入 | `超时（毫秒，留空用默认 30000）`/`失败重试次数（留空用默认 1）` | 可输入 | — | P2 | | 待执行 |
| TC-AISET-07 | Switch ×3 | `连通性自检（self-test）`/`智能入账建议（accounting-suggestion，A1：AI 只建议不执行）`/`公司主体归类建议（company-classification，归档页「AI 智能归类」）` | 可切换 | — | P1 | | 待执行 |
| TC-AISET-08 | 按钮 | `保存并生效` | 保存成功 | PUT `/ai/config` | P1 | ⚠写 | 待执行 |
| TC-AISET-09 | 按钮 | `测试连接（用表单当前值，可未保存）` | 返回连通结果 | POST `/ai/config/test` | P1 | ⚠外 | 待执行 |

### 3.21 AI 状态 `/system/ai`（admin/AiStatusPage.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-AISTAT-01 | 首屏 + `刷新` | `刷新` | 状态卡 + 调用审计表 | GET `/ai/status`、`/ai/call-logs?limit=50` | P0 | | 待执行 |
| TC-AISTAT-02 | 按钮 | `连通性自检` | 未启用时 disabled + Tooltip `AI 总开关未启用——请先到 AI 设置页开启` | POST `/ai/self-test` | P1 | ⚠外 | 待执行 |

### 3.22 飞书配置 `/feishu`（feishu/pages.tsx）

| ID | 控件 | 定位文案 | 预期 | 接口 | 级别 | 标记 | 状态 |
|---|---|---|---|---|---|---|---|
| TC-FEISHU-01 | 首屏 | — | 表单 + Steps 渲染 | GET `/feishu/app-config` | P0 | | 待执行 |
| TC-FEISHU-02 | 输入框 | `App ID`（`cli_xxxxxxxx`）/ `App Secret` | 可输入，Secret 掩码 | — | P1 | | 待执行 |
| TC-FEISHU-03 | 按钮 | `仅保存` | 保存成功 | PUT `/feishu/app-config` | P1 | ⚠写 | 待执行 |
| TC-FEISHU-04 | 按钮 | `保存并验证（真实调飞书接口）` | 真实调飞书（无凭证应失败并给明确错误） | POST `/feishu/app-config/verify` | P1 | ⚠外 | 待执行 |
| TC-FEISHU-05 | 无权限态 | `需要 feishu:manage 权限才能保存与验证凭证。` | 只读提示 | — | P2 | | 待执行 |
| TC-FEISHU-06 | 复制项 | `点击复制提示词` / `开放平台入口` | 复制成功 | — | P2 | | 待执行 |

### 3.23 下线路由重定向（回归）

| ID | 场景 | 预期 | 级别 | 状态 |
|---|---|---|---|---|
| TC-REDIR-01 | 访问 `/statements/import` | 重定向 `/bank-access/data/statements` | P2 | 待执行 |
| TC-REDIR-02 | 访问 `/statements/batches` | 重定向 `/statements/vouchers` | P2 | 待执行 |
| TC-REDIR-03 | 访问 `/bank-access/connections`、`/agreements`、`/preferences` | 重定向 `/bank-access/accounts` | P2 | 待执行 |
| TC-REDIR-04 | 访问 `/bank-access/monitoring`、`/operations/tasks` | 重定向 `/bank-access/tasks` | P2 | 待执行 |
| TC-REDIR-05 | 访问 `/reconciliation/dashboard` | 重定向 `/dashboard` | P2 | 待执行 |
| TC-REDIR-06 | 访问未知路径 `/not-exist` | 重定向 `/dashboard` | P2 | 待执行 |
| TC-REDIR-07 | 访问 `/validation` | 重定向 `/voucher-rules` | P2 | 待执行 |

---

## 4. 执行约束与风险

### 4.1 执行顺序（避免状态污染）

1. **只读轮**：全部 P0 + 只读 P1/P2（查询/筛选/查看/切换 Tab/分页）→ 记录基线
2. **写操作轮**：新增类（新建账户/角色/字典项/规则草稿）→ 验证后清理
3. **破坏性轮**（最后做）：删除类、停用、结账、推送
4. **外部调用轮**（单独隔离）：连通测试、飞书验证、AI 拉模型、金蝶推送

### 4.2 明确不做的事

- 不在 ECS 生产环境执行任何写操作
- 不触发真实银行报文发送（`BANKDATA_REAL_ADAPTERS_ENABLED=false` 兜底）
- 不触发真实金蝶推送（`kingdee.mock-mode=true` 兜底）
- 不修改 `frontend/`、`backend/` 源码来"让测试通过"

### 4.3 失败判定标准

| 现象 | 判定 |
|---|---|
| 点击后出现 5xx | 失败（记录接口与响应体） |
| 点击后出现 4xx 且非权限语义 | 失败 |
| 控制台 `Uncaught` / React error | 失败 |
| 点击无任何反馈（既无请求也无 UI 变化）且预期应有反馈 | 失败 |
| 按钮 `disabled` 且符合权限/状态规则 | 通过（不算失败） |
| 页面白屏 / 路由不可达 | 失败 |

### 4.4 执行工具

- `tmp/ui-click-test/click-all.mjs`：Playwright 主脚本，登录后逐页遍历控件
- 每轮输出：`tmp/ui-click-test/report-<timestamp>.json` + 截图 `tmp/ui-click-test/shots/`
- 采集：控制台错误、`pageerror`、失败请求（4xx/5xx）、每步耗时

---

## 5. 执行记录

| 轮次 | 日期 | 范围 | 结果 | 报告 |
|---|---|---|---|---|
| R1 | 2026-09-17 | 17 页首屏加载 + 导航菜单 + 安全控件点击 | **17/17 页通过**：0 控制台错误、0 pageerror、0 个 4xx/5xx、无白屏 | `tmp/ui-click-test/out/report-20260917-184501.json` |
| R2 | 2026-09-17 | 弹窗链路（7 个）+ 真实创建账户 + 查询页 + 行内按钮 | 功能链路 **15/15 通过**；发现 **7 项缺陷**（1×P1、4×P2、2×P3），详见 §7.2 | `tmp/ui-click-test/out/round2-1789642100176.json` |
| R3-a | 2026-09-18 | 灌业务数据后首轮：账户 / 任务 / 日志 / 报文 / 凭证 / 字典 / 用户 / 大类规则 | 页面与行内按钮全部触达；**发现 2 项 P1 缺陷**（大类规则整页不可用、结账恒不可用），详见 §8.3 | `tmp/ui-click-test/out/r3-1789642846837.json` |
| R3-b | 2026-09-18 | 凭证中心 5 个状态桶逐个切换 | 5/5 桶渲染正确，行内按钮随状态正确增减 | `tmp/ui-click-test/out/r3b-1789693701571.json` |
| R3-c | 2026-09-18 | **修复后回归**：大类规则 / 结账（含中文化）/ 校验中心新建草稿+启用 | **全部通过**：22 条规则渲染、账期 READY→CLOSED、BLOCKED→409、confirm 按钮中文、草稿保存+启用成功 | `tmp/ui-click-test/out/r3c-1789694247345.json` |
| R3-d | 2026-09-18 | 剩余行内按钮：报文抽屉 3 按钮 / 字典 / 用户 / 凭证 通过·驳回·推送·追溯 / 同步任务详情 | **零真实错误**（仅 favicon 404 + antd 弃用告警） | `tmp/ui-click-test/out/r3d-1789694463303.json` |
| R4 | 2026-09-18 | **告警归属探针**：逐页单独开记录窗口，抓 React 组件栈 | 定位到 `destroyOnClose` 首屏告警只来自 3 页（accounts/tasks/validation）；`useForm` 告警**首屏 0 次** → 只由点击路径触发 | `tmp/ui-click-test/out/r4-warn-attrib-*.json` |
| R4-b | 2026-09-18 | **点击路径归属**：逐按钮 点击→等待→取消，统计每步新增告警 | 命中 3 处「新增/编辑」按钮（accounts/dicts/users）；并发现**同一步骤可复现性不稳定** → 判定为竞态 | `tmp/ui-click-test/out/r4b-useform-*.json` |
| R4-c | 2026-09-18 | **预填回归**：先点「编辑」（不先点新增）读取弹窗字段值 | 推翻「重挂载清空表单」假设：字典/用户编辑弹窗**预填正常**，无潜在 bug | `tmp/ui-click-test/out/r4c-prefill-*.json` |
| R5 | 2026-09-18 | **A1/B1/C1 修复验收**（测试连通 / 导出门控 / 告警清零 + 预填回归） | **全项通过**：探测 3 账户全部 200+DISABLED、导出按钮 disabled+Tooltip、useForm 告警 **0**、destroyOnClose 告警 **0**、Spin 告警 **0**、新增 4xx/5xx **0** | `tmp/ui-click-test/out/r5-accept-*.json` |

### 变更日志

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-17 | 初始清单，覆盖 23 个页面分组、约 120 条用例 |
| v2 | 2026-09-17 | 补 R1 执行结果；新增 §6 实测渲染核对、§7 R2 结果；记录 antd 双字插空格等定位陷阱 |
| v3 | 2026-09-18 | 补 R3(a–d) 执行结果与 2 项 P1 修复；新增 §8 R3 实测结果；§9 修复建议改为带状态跟踪 |
| v4 | 2026-09-18 | 补 R4/R4-b/R4-c 告警归属结果与 R5 验收；新增 §9.2 剩余三项的处置与背景；记录「告警是竞态、非必然」这一诊断结论 |

---

## 6. R1 实测结果（2026-09-17）

### 6.1 结论

- **17 个页面全部加载成功**，无白屏（`bodyLen` 最小 186，均正常渲染）
- **全轮 0 个 console error、0 个 pageerror、0 个 4xx/5xx 响应**
- 登录链路通过（`admin` / `Admin@123` → 跳转 `/dashboard`）
- 侧栏一级菜单「银行数据」默认展开，6 个子项全部渲染

### 6.2 各页实测按钮清单（真实渲染）

| 页面 | 实测渲染按钮（除用户菜单 `admin` 外） |
|---|---|
| 工作台 | `前往凭证中心`、`刷新` |
| 银行账户 | `档案管理`、`新增账户` |
| 同步任务 | `补拉历史数据`、`停用`、`添 加`、`查询`、`重 置` |
| 运行日志 | `查询`、`重 置` |
| 余额查询 | `列设置`、`按所选账户创建同步任务`、`查询`、`重 置` |
| 流水查询 | `按所选账户创建同步任务`、`查询`、`重 置` |
| 原始报文 | `查询`、`重 置` |
| 凭证中心 | `刷新` |
| 大类规则 | `刷新`、`重试` |
| 科目与往来规则 | `新建草稿`、`校验规则`、`入账映射` |
| 账期结账 | `检查账期`、`确认结账` |
| 用户与角色 | `刷新`、`新增账号`、`编辑 / 重置密码`、`停 用` |
| 字典中心 | `新建类型`、`查看项`、`编辑`、`删除`、`新建字典项` |
| AI 状态 | `连通性自检`、`刷新` |
| AI 设置 | `刷新`、`启用/停用`开关、`拉取模型`、3 个开关、`保存并生效`、`测试连接（用表单当前值，可未保存）` |
| 审计中心 | （仅用户菜单，事件列表为空） |
| 飞书配置 | `点击复制提示词`、`仅保存`、`保存并验证（真实调飞书接口）`、`Copy` |

### 6.3 已确认的「非缺陷」项（勿误报）

| 现象 | 判定 | 原因 |
|---|---|---|
| 按钮文案显示为 `重 置` / `添 加` / `停 用` | **非缺陷** | antd `Button` 对两字中文自动插入空格（`autoInsertSpace`）。自动化定位需容忍空格。 |
| 侧栏「凭证与入账」「系统管理」展开前子项 DOM 为空 | **非缺陷** | antd 内联菜单对收起状态做懒渲染，展开后才挂载。 |
| 点击「银行数据」后子项消失 | **非缺陷** | 该组默认展开，点击等于折叠。测试脚本已记录该误判并修正。 |
| 余额查询首屏无 `导出 CSV` | **符合设计** | 该按钮仅在 `submitted=true`（已查询）后渲染。 |
| 流水查询首屏无行内 `查看`、无行复选框 | **符合设计** | 引导态未查询，且 H2 空库无数据行。 |
| 审计中心无分页控件 | **符合设计** | 无审计事件数据。 |

### 6.4 R1 暴露的真实缺口

**H2 空库导致行内按钮无法覆盖。** 19 类行内操作（`查看`/`详情`/`测试连通`/`通过`/`驳回`/`推送`/`追溯`/`属性`/`启用`/`删除`/`应用勾选行`/`导出 CSV`/`AI 制证` 等）在无数据行时根本不渲染，R1 无法触及。→ 这是 R2 的主攻方向。

---

## 7. R2 实测结果（2026-09-17，弹窗链路 + 数据构造 + 行内按钮）

报告：`tmp/ui-click-test/out/round2-1789642100176.json`，截图：`tmp/ui-click-test/out/shots/`

### 7.1 功能验证通过项

| 用例 | 实测结果 |
|---|---|
| 银行账户 → `档案管理` | Drawer「账户档案管理」打开 ✓，可关闭 ✓ |
| 银行账户 → `新增账户` | Modal「新增银行账户」打开 ✓，`取消` 关闭 ✓ |
| 银行账户 → **创建账户（真实写）** | 选「招商银行」→ 点 `创建账户` → 弹窗关闭、新账户出现在列表 ✓ |
| 银行账户 → 行内 `测试连通` | Modal「连通测试 · 招商银行」打开 ✓（后端 400，见 §7.2） |
| 同步任务 → `补拉历史数据` | Modal「补拉历史数据」打开 ✓，可关闭 ✓ |
| 科目与往来规则 → `新建草稿` | Modal「新建校验规则草稿」打开 ✓ |
| 用户与角色 → `新增账号` | Modal「新增账号」打开 ✓ |
| 用户与角色 → 行内 `编辑 / 重置密码` | Modal「编辑账号：admin」打开 ✓ |
| 字典中心 → `新建类型` | Modal「新建字典类型」打开 ✓ |
| 字典中心 → `查看项` | 右侧渲染 2 行字典项 ✓ |
| 账期结账 → `确认结账` | confirm 弹窗「确认结账 2026-09」打开 ✓（见 §7.2 #4） |
| 余额查询 / 流水查询 → `查询` | 空态 `ant-empty` 正常渲染，无 JS 错误 ✓ |
| 余额/流水 → `导出 CSV` | 按钮按设计在 `submitted` 后出现 ✓（点击失败，见 §7.2 #1） |
| 凭证中心 → 状态 Segmented 切换 | `待复核/待推送/已推送/推送失败/全部` 全部可切 ✓ |
| 大类规则 → 行内 `模板` | 无数据行，跳过（H2 空库） |

### 7.2 发现的缺陷（按严重度排序）

| # | 现象 | 判定 | 严重度 | 证据 |
|---|---|---|---|---|
| 1 | `GET /api/bank-data/{balances\|statements}/export` 返回 **HTTP 500**，响应体业务码却是 **503**「真实银行直联未连接：服务端未启用真实银行适配器，无法导出」 | **契约缺陷**：`GlobalExceptionHandler.resolveStatus()` 的 switch 只有 400/401/403/404/409/429/501/502，**无 503 分支**，落到 `default -> INTERNAL_SERVER_ERROR`。调用方无法区分「依赖未就绪」与「服务端崩溃」 | **P1** | curl 复现 + `GlobalExceptionHandler.java:76-86` |
| 2 | 真实适配器未启用时，「导出 CSV」按钮仍渲染且可点，一点必失败，无任何前置引导 | **产品体验缺陷**：应按适配器可用性禁用按钮或给出说明 | **P2** | R2-BAL-query / R2-STMT-query |
| 3 | `POST /api/bank-accounts/{id}/test-connection` 返回 **HTTP 400** + `Bank data adapter is not available` | **契约语义缺陷**：适配器不可用属「服务不可用」(503)，用 400「客户端请求错误」表达会让前端误判为入参问题 | **P2** | curl 复现 |
| 4 | 账期结账 confirm 弹窗按钮显示为英文 **`Cancel` / `OK`** | **国际化缺陷**：全站无 `ConfigProvider locale={zhCN}`，`main.tsx` 未配置；未显式传 `okText/cancelText` 的 antd 组件一律回退英文。同源影响 DatePicker 面板、Pagination「条/页」、Select 空态等 | **P2** | 截图 `R2-CLOSE-confirm.png` + 全仓 grep 无 ConfigProvider |
| 5 | React 警告 `Instance created by 'useForm' is not connected to any Form element`（打开 Modal/抽屉流程中出现 4 次） | **代码质量**：`form` 实例在 Form 未挂载时被调用（常见于 `resetFields()` 写在关闭回调） | **P2** | R2 console |
| 6 | `[antd: Modal] destroyOnClose is deprecated. Please use destroyOnHidden instead.`（多处） | 依赖升级债（antd 5.26 弃用告警） | **P3** | R2 console |
| 7 | `[antd: Spin] tip only work in nest or fullscreen pattern.` | 依赖升级债 | **P3** | R2 console |

### 7.3 R2 未覆盖（留待 R3）

H2 库除 `admin` 用户、22 条凭证规则、字典种子外**无业务数据**，下列行内按钮仍无法触达：

- 流水查询：行复选框、`AI 制证为草稿/并推送`、行内 `查看`、详情 Drawer 内 `查看本次报文`、`查看脱敏审计追溯`、导出（有数据时）
- 凭证中心：`查看凭证`/`通过`/`驳回`/`推送`/`追溯`
- 原始报文：`查看报文`、`重放校验`、`复制银行原文`、`下载解析视图`
- 同步任务：行内 `详情`、定时计划 `停用/启用`、Tag 删除、`新增时刻 + 添加`
- 大类规则：行内 `模板`
- 用户与角色：`通过/停用/启用`、角色 Tab `新建角色`/`编辑权限`
- 字典中心：行内 `属性`/`编辑`/`删除`、`新建字典项`
- 审计中心：分页（需审计事件）

→ **R3 需要先经后端 API 灌入业务数据**（账户/流水/余额/任务/凭证），再重跑行内点击。

---

## 8. R3 实测结果（2026-09-18，灌数据 + 行内按钮 + 修复回归）

### 8.1 测试环境

R1/R2 受限于 H2 空库，19 类行内按钮根本不渲染。本轮改为**文件模式 H2**（`jdbc:h2:file:C:/Users/Public/finflow-r3/db;MODE=MySQL;AUTO_SERVER=TRUE`），用 `org.h2.tools.RunScript` 直接灌业务数据，再跑浏览器点击：

| 灌入数据 | 数量 | 脚本 |
|---|---|---|
| 银行账户 / 同步任务 / 报文 / 余额 / 流水 | 3 / 3 / 3 / 2 / 4 | `C:/Users/Public/finflow-r3/seed-r3.sql` |
| 凭证单据（4 种状态组合） | 4 | 同上 |
| 同步计划 / 字典 / 凭证规则 | 3 / 2 / 22（既有 seed） | 同上 |
| 结账账期流水（2026-08 全已推送、2026-09 混合） | 2 + 4 | `C:/Users/Public/finflow-r3/seed-r3b.sql` |

关键约束（决定数据怎么造）：`BankDataQueryService` 在 `realDirectConnected=false` 时直接返回 `notConnectedPage()`，**余额/流水查询页在 dev 下必然空页——这是设计行为，不是缺陷**；因此这两个页面的行内按钮本轮仍无法覆盖，需真实适配器环境。

### 8.2 行内按钮覆盖结果（R3-a / R3-b / R3-d）

| 页面 | 行内按钮 | 实测结果 |
|---|---|---|
| 银行账户 | `测试连通` | Drawer「连通测试 · 招商银行」打开 ✓；后端 400（见 §9 #3） |
| 同步任务 | `详情` | Drawer「同步任务 · SYNC-R3-0003」打开 ✓（首屏需先点 `查询` 才出数，属设计） |
| 报文留存 | `查看报文` | Drawer「银行原始报文 · 1003」打开 ✓ |
| 报文留存 · 抽屉 | `重放校验（当前规则vs当年视图）` | 执行成功 ✓，返回「该报文不是真实银行直联产生」提示 ✓ |
| 报文留存 · 抽屉 | `复制银行原文` | Toast「报文已复制到剪贴板」✓ |
| 报文留存 · 抽屉 | `下载解析视图` | 触发下载 `bank-raw-message-1003.json` ✓ |
| 凭证中心 | `通过` | Popconfirm「确认通过该凭证草稿（STMT-R3-0001）」✓（`取消` / `确认通过`） |
| 凭证中心 | `驳回` | Modal「驳回凭证草稿（STMT-R3-0001）」✓（`取消` / `确认驳回`） |
| 凭证中心 | `推送` | Popconfirm「确认推送金蝶（STMT-R3-0002）」✓ |
| 凭证中心 | `追溯` | Drawer「追溯记录 · STMT-R3-0004」✓（空态「该流水暂无追溯事件」） |
| 字典中心 | `查看项` | 右侧内联渲染字典项 ✓（非浮层，`overlayOpened=false` 属正常） |
| 字典中心 | `编辑` | Modal「编辑字典类型」✓ |
| 字典中心 | `删除` | Popconfirm 弹出 ✓，`取消` 关闭 ✓ |
| 用户与角色 | `编辑 / 重置密码` | Modal「编辑账号：admin」✓，含重置密码输入与「会话立即失效」说明 |
| 用户与角色 | `停用` | Popconfirm 弹出 ✓，`取消` 关闭 ✓ |
| 大类规则 | `模板` | Modal 打开 ✓，展示借/贷分录明细（修复后，见 §8.3 #8） |
| 校验中心 | `新建草稿` → 行内 `启用` | 表单提交 → Toast「草稿已保存」→ 行内「启用」→ Toast「版本已启用」→ 状态转 `ACTIVE` ✓ **全链路** |
| 账期结账 | `检查账期` / `确认结账` | 2026-08 `READY` → 结账成功转 `CLOSED` ✓；2026-09 `BLOCKED`(待复核1/异常1/未制证1) → 结账被 409 拒并给出中文原因 ✓ |

凭证中心状态桶（R3-b/R3-d）：`待复核`1 行（`通过`/`驳回`/`追溯`）、`待推送`1 行（`推送`/`追溯`）、`已推送`3 行（仅 `追溯`）、`推送失败`1 行（`推送`/`追溯`）、`全部`6 行 —— 按钮随状态正确增减，无越权渲染。

### 8.3 R3 新发现并修复的缺陷

| # | 现象 | 根因 | 级别 | 状态 |
|---|---|---|---|---|
| 8 | **大类规则页整页不可用**：标题栏显示「共 0 条」并弹出红色「数据暂不可用 / 请求未能完成」，而 curl 直连接口返回 200 + 22 条 | `KingdeeVoucherRuleController.list()` 返回 `ResponseEntity<List<...>>` **裸数组**，是全仓唯一未走 `ApiResponse` 信封的 JSON 端点；前端拦截器 `if (body?.code !== 0) throw` 把 `undefined` 判为失败 | **P1** | ✅ 已修复（改回 `ApiResponse.success(...)`），R3-c 实测渲染 22 条 + 模板弹窗可用 |
| 9 | **结账功能对任何含流水的账期恒不可用**：检查账期把账期内**每条**流水都计入「异常」，账期永远 `BLOCKED`，「确认结账」永远 409 | `ClosingService.refresh()` 用 `"VALID"` 判定流水校验状态，而流水域真实字面量是 `"PASSED"`（`StatementService.VALID = "PASSED"`，`"VALID"` 是 `bank_data_*` 投影层的写法）；同时只认 `"PUSHED"`、不认规则引擎路径的 `"GL_PUSHED"`，导致已成功推送的流水又被计入「未制证」 | **P1** | ✅ 已修复 + 新增 `ClosingServiceTest`（7 用例，`Tests run: 7, Failures: 0`） |

> 全仓同类排查：除 #8 外，仅 `CiticCertificateController.download()` 也返回裸 `Map`（未被前端消费，属运维端点）与 `BankPipelineController.export()` 返回 CSV（=blob，前端按 `responseType: 'blob'` 直通，正确）。其余 controller 均已走信封。

### 8.4 R3 全轮错误统计

| 轮次 | 真实错误 | 说明 |
|---|---|---|
| R3-c | 0（2 条属预期业务拒绝：`409 POST /closing/periods/2026-09/close` 是正确拦截；1 条 favicon 404） | — |
| R3-d | **0** | 9 条 console 均为噪声：1× favicon 404 + 8× antd `destroyOnClose` 弃用告警；**HTTP 4xx/5xx 响应数为 0** |

---

## 9. 缺陷修复建议与状态

| # | 缺陷 | 建议 | 改动面 | 状态 |
|---|---|---|---|---|
| 1 | 导出接口业务码 503 被返回成 HTTP 500 | `GlobalExceptionHandler.resolveStatus()` 增加 `case 503 -> HttpStatus.SERVICE_UNAVAILABLE;` | 1 行 | ✅ **已修复**；curl 实测导出接口现返回 503（修复前 500）。全仓无断言 500 的测试用例，零涟漪 |
| 4 | `Modal.confirm` 按钮显示英文 `Cancel` / `OK` | `App.tsx` 包 `<ConfigProvider locale={zhCN}>` + `main.tsx` 引入 `dayjs/locale/zh-cn`；另用 `ConfigProvider.config({ holderRender })` 覆盖 `Modal.confirm` 等**静态方法**（v5 中 `ConfigProvider` 对静态方法默认不生效） | 3 处 | ✅ **已修复**；R3-c 实测「确认结账」弹窗按钮为 `取消` / `确定` |
| 8 | 大类规则页整页「数据暂不可用」 | `KingdeeVoucherRuleController.list()` 从裸 `ResponseEntity<List<...>>` 改为 `ApiResponse<List<...>>` 信封 | 1 文件 | ✅ **已修复**；R3-c 实测渲染 22 条规则 + 行内 `模板` 弹窗可用 |
| 9 | 结账对任何含流水的账期恒 `BLOCKED` | `ClosingService.refresh()`：`"VALID"` → `"PASSED"`；「已制证」判定同时接受 `PUSHED` 与 `GL_PUSHED` | 1 文件 + 新增 7 条单测 | ✅ **已修复**；2026-08 `READY`→结账成功，2026-09 计数由「异常 4」修正为「待复核 1/异常 1/未制证 1」 |
| 3 | `test-connection` 用 HTTP 400 表达「适配器不可用」 | ~~需在 `BankDataAdapterRegistry` 上层区分语义~~ → **修正**：探测路径的 `adapterCode` 完全由服务端账户档案决定（`resolveCode(null, bankCode)`），无客户端输入，400 在此 100% 误用；改由 `BankConnectionTestService` 就地翻译为 **200 + `DISABLED`**（复用前端已有渲染），**registry 一行未动** | 1 文件 + 1 测试用例改写 | ✅ **已修复**；三个账户（CMB/CITIC/ICBC）实测 `HTTP 400 → 200` + `result=DISABLED`（见 §9.2） |
| 2 | 适配器未启用时导出按钮仍可点，一点必失败且无引导 | **探测结论：无需新增端点** —— `BankDataQueryService.notConnectedPage()` 早已返回 `enabled=false`，前端亦已在空态文案里消费该字段。仅需拿它门控按钮 | 1 行条件 + Tooltip | ✅ **已修复**（禁用 + Tooltip）；R5 实测 balances/statements 两页按钮均 `disabled=true`，Tooltip 文案为「真实银行直联未连接：服务端未启用真实银行适配器，暂无可导出的数据。」 |
| 5 | `useForm is not connected to any Form element` 警告 | **探测结论：这是竞态，非必然触发** —— rc-field-form 用 `setTimeout(0)` 检查 `formHooked`，与 React 首次挂载 commit 抢时序；且 rc-util 对同一文案做**会话内去重**（故"只在首次点击"是假象）。修法须消除竞态：给承载表单的 Modal 加 `forceRender` | 5 个 Modal 各加 1 个 prop | ✅ **已修复**；R5 实测 useForm 告警 **0**，且字典/用户编辑弹窗预填未被破坏 |
| 6 | `[antd: Modal] destroyOnClose is deprecated` | 全仓替换为 `destroyOnHidden`（antd ≥5.25 的官方改名，语义相同） | 7 文件 12 处 | ✅ **已修复**；R5 实测 destroyOnClose 告警 **0** |
| 7 | `[antd: Spin] tip only work in nest or fullscreen pattern` | 去掉 `tip`，把文案改为 `Spin` 的同级文本（不依赖 antd 的 nest 语义） | 2 处 | ✅ **已修复**；R5 实测 Spin 告警 **0** |

### 9.2 剩余三项的处置说明（2026-09-18）

**① `test-connection`（#3）——原判断被推翻，成本比预想低。**
上一轮记录的是「该异常在 `BankDataAdapterRegistry` 有两个触发点，需先在上层区分语义」。实读代码后确认：探测路径调用的是 `registry.resolveCode(null, account.getBankCode())`，`requested` 传 `null`，**没有任何客户端输入**；真正需要保留 400 的是另一条路径（`BankDataSyncService` 的补拉表单 `request.adapterCode`）。
因此**不动 registry**，只在 `BankConnectionTestService` 就地捕获并翻译为 `200 + DISABLED`——前端 `CONNECTION_TONE` 本来就有 `DISABLED: { title: '真实适配器未启用' }`，**前端零改动**。
> 语义理由：这是**探测**端点，用户点「测试连通」要的就是"能不能连"这个结论。"本环境没启用"是合法探测结果，不该表现为运维无法处置的错误状态码。

**② 导出按钮（#2）——同样无需新增端点。**
`BankDataPageResponse` 早已带 `enabled` 字段（`notConnectedPage()` 传 `false`），前端 `BankDataQueryPage` 也已在空态文案里使用 `data?.enabled === false`，只是没拿来门控导出按钮。补上门控即可。

**③ 告警清理（#5/#6/#7）——已全清，且新增一类告警的处置结论。**
R4 探针额外发现 **React Router v7 future flag 提示**（`v7_startTransition` / `v7_relativeSplatPath`，每页 2 条）。**本轮有意不动**：这两个 flag 是 react-router v7 的行为开关，开启会真实改变渲染与时序行为（本应用存在 `path="*"` splat 路由，`v7_relativeSplatPath` 会影响其下相对路径解析）。把它当"告警清理"顺手打开等于**借清理之名做行为变更**，应另开一批并做回归。
> 另注：R2/R3 的历史报告只采集 `console.error`，故这类 `console.warn` 从未进入此前统计。

### 9.1 实际改动文件

**上一批（P1/P2，2026-09-18）**

| 文件 | 改动 |
|---|---|
| `backend/.../common/exception/GlobalExceptionHandler.java` | `resolveStatus()` 增加 503 分支（#1） |
| `backend/.../statement/voucherrule/KingdeeVoucherRuleController.java` | 裸数组改 `ApiResponse.success(...)` 信封（#8） |
| `backend/.../closing/ClosingService.java` | 校验状态字面量 `VALID`→`PASSED`；推送状态接受 `PUSHED`/`GL_PUSHED`（#9） |
| `backend/src/test/java/.../closing/ClosingServiceTest.java` | **新增**：7 条 Mockito 单测，锁死 #9 的口径（closing 包此前无任何测试） |
| `frontend/src/App.tsx` | `<ConfigProvider locale={zhCN}>` + `ConfigProvider.config({ holderRender })`（#4） |
| `frontend/src/main.tsx` | `import 'dayjs/locale/zh-cn'` + `dayjs.locale('zh-cn')`（#4） |

验证：`mvn -o -Dtest=ClosingServiceTest test` → `Tests run: 7, Failures: 0`；`tsc -b` 通过；导出接口 curl 实测 503；R3-c 浏览器实测三项修复全部生效。

**本批（A1/B1/C1，2026-09-18）**

| 文件 | 改动 |
|---|---|
| `backend/.../bankdata/aggregation/BankConnectionTestService.java` | 探测路径无注册适配器时返回 `200 + DISABLED`（含可读说明），不再抛 400（#3） |
| `backend/.../bank/dto/BankConnectionTestResponse.java` | `result` 字段 javadoc 补充 `DISABLED` 的第二种来源（#3） |
| `backend/src/test/java/.../BankConnectionTestServiceTest.java` | 用例改写：`unregisteredBankCodeIsRejected`（断言抛异常）→ `unregisteredBankCodeReportsDisabledInsteadOfFailing`（断言 200+DISABLED） |
| `frontend/src/modules/bank-access/BankDataQueryPage.tsx` | 导出按钮按 `data.enabled === false` 禁用 + Tooltip 说明（#2）；`Spin tip` 改同级文本（#7） |
| `frontend/src/modules/bank-access/pages.tsx` | 新增账户 Modal 加 `forceRender`（#5）；`Spin tip` 改同级文本（#7） |
| `frontend/src/modules/admin/DictionaryPage.tsx` | 2 个 Modal 加 `forceRender`（#5）；`destroyOnClose`→`destroyOnHidden` ×2（#6） |
| `frontend/src/modules/admin/UsersPage.tsx` | 2 个 Modal 加 `forceRender`（#5）；`destroyOnClose`→`destroyOnHidden` ×2（#6） |
| `frontend/src/modules/bank-access/operations.tsx`、`archive.tsx`、`ValidationPage.tsx`、`VoucherDraftDrawer.tsx` | `destroyOnClose`→`destroyOnHidden`（#6，共 7 处） |

验证：`mvn -o -Dtest=BankConnectionTestServiceTest test` → `Tests run: 7, Failures: 0`；后端全量测试通过；`tsc -b` 0 错误、`eslint` 0 错误；R5 浏览器实测 6 项断言全绿（见 §5 执行记录）。

---

## 10. 测试环境与复现步骤

```bash
# 后端（dev profile = H2；要灌数据用文件库模式）
cd backend && mvn.cmd -o spring-boot:run \
  "-Dspring-boot.run.arguments=--server.port=8080 --spring.datasource.url=jdbc:h2:file:C:/Users/Public/finflow-r3/db;MODE=MySQL;AUTO_SERVER=TRUE"
# 前端（5173，proxy /api → 8080）
cd frontend && ./node_modules/.bin/vite --host 127.0.0.1 --port 5173
# 灌数据（AUTO_SERVER 允许多进程，后端运行中也能灌）
java -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar org.h2.tools.RunScript \
  -url "jdbc:h2:file:C:/Users/Public/finflow-r3/db;MODE=MySQL;AUTO_SERVER=TRUE" -user sa -password "" \
  -script C:/Users/Public/finflow-r3/seed-r3.sql -showResults
# 点击测试
cd tmp/ui-click-test && node r3c-fixes.mjs   # 或 r3d-rest.mjs / click-all.mjs / round2.mjs
```

环境坑（已踩）：

| 坑 | 现象 | 处理 |
|---|---|---|
| 本机 52257 端口被别的服务占用 | Spring 读到该端口启动 | 必须显式传 `--server.port=8080` |
| Git Bash 调 `corepack.cmd` | 路径 `/c/` 被转成 `C:\c\` | 改用 PowerShell 调用 |
| `frontend/node_modules` 曾被破坏 | `.pnpm` 符号链接失效、`.bin` 缺失、vite 解析落到 legacy `index.js` | `mv node_modules` 移开后 `corepack pnpm install --frozen-lockfile` |
| Chromium `ERR_NETWORK_IO_SUSPENDED` | headless 页面网络 IO 被 Windows 原生遮挡检测/后台节流挂起（服务端正常） | 启动参数加 `--disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling` 等 |
| `vite build` 清空 `dist` 被拦 | `SAFE_DELETE_BULK_CONFIRM_REQUIRED` | 先把 `dist` 移走再 build |
| H2 元数据查询查不到表 | `INFORMATION_SCHEMA.COLUMNS.TABLE_NAME` 存**大写**，小写字面量比较不命中 | 用 `UPPER(TABLE_NAME) IN (...)` |
| `vite build` 主 chunk 逼近 500 kB | 加 locale 后会涨 | 构建后核对 `dist/assets` 体积 |

---

## 11. 未覆盖项（需真实适配器环境）

| 页面 | 未覆盖控件 | 原因 |
|---|---|---|
| 余额查询 / 流水查询 | 行复选框、行内 `查看`、详情抽屉、`AI 制证为草稿/并推送`、`导出 CSV`、`列设置` | `BankDataQueryService` 在 `realDirectConnected=false` 时返回 `notConnectedPage()`，页面必然空态（**设计行为**） |
| 银行账户 | `编辑` / `停用` / `删除` | 本轮未逐个点击（写操作，需拍板是否在 dev 库执行） |
| 同步任务 | 定时计划 `停用/启用`、Tag 删除、`新增时刻 + 添加` | 已在 R3-a 触达入口，未做全组合 |
| 用户与角色 | 角色 Tab `新建角色` / `编辑权限`、`通过/启用` | 需补充角色数据 |
| 审计中心 | 分页 | 需审计事件数据 |
| 飞书配置 | `保存并验证（真实调飞书接口）` | 需真实飞书凭证 |

> 验收口径：本清单是**活文档**。每轮执行后在 §5 追加一行、在 §9 更新状态列；`非缺陷` 项记录在 §6.3，避免后续误报。
