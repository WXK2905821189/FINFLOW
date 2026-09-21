# FINFLOW 模块长期笔记（从 `.workbuddy/memory/MEMORY.md` 迁出）

> **为什么有这个文件**：`MEMORY.md` 每次会话会被注入上下文，超限即被截断（12063 字节时已发生，尾部「前端 / UI 测试」整段丢失）。
> 因此把「模块级长叙述」迁到这里，`MEMORY.md` 只留**高频硬约束 + 一行导语**。
> **开工读法**：`MEMORY.md`（必读，已注入）→ 涉及下列模块时**显式读本文件对应小节**。
> 最后更新：2026-09-21（W11 部署后）。

---

## 1. AI 能力与权限

- 权限码：`ai:use`(42) / `ai:config`(43，**仅超管**)。普通用户可制证，不可改模型配置。
- `ai_provider_config`：**单例行**表，DB 覆盖 env（env 全 false 但 `/api/ai/status` 显示 enabled=true = 正常，在线配置生效）。
- 密钥用 AES-256-GCM 加密，**只写不读**（接口永不回显明文）。
- guard **fail-closed**：三道闸默认全关，宁可不可用也不误放。

### 1.1 「AI 不可用」的真根因 = max_tokens 截断

原制证 max_tokens=512 → 中文 JSON 被从中间砍断 → 解析失败 → 业务层降级成模糊的「AI 建议不可用」。

- 已修：制证 **2048** / 分类 **4096**；网关识别 `finish_reason=length` 时返回**明确 502**（不再降级成模糊提示）。
- 排查顺序：`GET /api/ai/status` → `GET /api/ai/call-logs`（带 token）看最近调用的 `status` / `completionTokens` / `errorMessage`。
  **`completionTokens` 正好贴着上限（512/2048）= 输出被截断**。
- `ai_prompt_override`(V38)：超管可覆盖提示词。

### 1.2 AI 制证 × 规则中心联动

制证前先跑 `KingdeeVoucherMatchingService.preview(...)`，把**命中规则的分录作为强约束**注入提示词；
匹配引擎异常时降级为「空命中」（不阻断制证）；响应体含 `hitRules` 供前端展示。

### 1.3 AI 制证异步化（V40，2026-09-21 上线）

- `POST /api/bank-data/ai-voucher` 返回 `AiVoucherSubmitResult`（**不再是同步制证结果**）。
- 进度查询：`GET /api/bank-data/ai-voucher-jobs/latest`（无任务时 `data: null`，非错误）。
- 前端：`modules/voucher/AiVoucherJobBanner.tsx`；提交按钮文案 `okText: asDraft ? '提交制证任务' : …`。
- 表：`ai_voucher_job`；迁移 `V40__ai_voucher_job.sql`。

---

## 2. 银行数据 / 银行账户

- per-account「两证」判定为**三态**（不是布尔）。
- 调度按 `bank_code` 路由 **T-1** 日期。
- 直出三层证据链；`VendorStatementFields` 共 **27 字段**；CSV 输出 **BOM + CRLF**；招商 CMB 续传依赖 `ctnFlag=Y`。
- REAL 适配器测试**必须独立 Spring 上下文**（否则被 mock 上下文污染）。

### 2.1 银行账户 × 金蝶维度映射（V41，2026-09-21 上线）

- 端点：`GET /api/bank-accounts/kingdee-mapping` / `POST …/auto-match`；表列 `kingdee_mapping_*`。
- 迁移 `V41__bank_account_kingdee_mapping.sql`。
- W11 首跑实测（真实账套）：11 行 = **`AUTO_MATCHABLE` 7** / **`UNMATCHED` 4**，`gatewayMode=REAL`、`catalogAvailable=true`。
- **金蝶档案特征（只读实测，决定匹配策略）**：`CN_BANKACNT` **142 个档案分属 23 个组织**；
  其中 **102 个 `FNumber` 即账号本体**（可自动匹配），其余 40 个是虚拟账户编码
  （`admin@xiaopiu.com` 支付宝 / `分贝通平台` / `shym001` / `11111111` / `222` / `333`）须人工指定。
  **同一账号可在多个组织各有一份档案**（网商银行账号 300/400 都有）⇒ 必须**组织消歧**。
  零命中时给出的「该公司组织下全部档案」作候选池（覆盖虚拟账户场景）。
- 已知判定缺陷已修：候选池曾被 `MatchOutcome.ambiguous()` 误判为「多义」，导致 `autoMatch` 的 unmatched 恒为 0；
  `MatchOutcome` 拆成 `ambiguousHits` / `candidates` 两字段（由 `KingdeeAccountMappingServiceTest` 抓出）。
- `KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER` 降级为「流水无账户归属时的兜底」。

---

## 3. 权限与公司主体

- `bankdata:raw:view`(39) 是**唯一返回完整响应体**的权限码。
- **`company` 表是公司主体的唯一源**；字典类型 `company_entity` 是它的**只读镜像**
  （V30 曾因「字典描述过度承诺可写」被用户打回，措辞务必克制）。
- 跨公司下拉**必须** `{value, label}` 结构。

---

## 4. 凭证规则引擎与凭证中心

- 表：`statement/voucherrule` + `vouchergroup`；端点 `POST /api/kingdee/voucher-rule/{preview,push}`。
- **`KingdeeOrgResolver` 的组织映射是内置常量**：
  即设 300 / 雪云 400 / 海南 900 / 长沙 710 / 广州 720；
  ⚠️ **分公司关键词必须先于主公司匹配**（否则被主公司吞掉）。
  ⇒ **新增主体必须改代码**，这是字典化价值最高的候选。
- **规则/凭证中心已 Excel 内核化**：列定义 `CategoryRulesGridColumns.ts`(13 列) / `VoucherCenterGridColumns.ts`(10 列)。
- 拖「⠿」到左侧分组即换组；`PUT /kingdee/voucher-rules/{id}` 是**全量语义**，局部改必须走 `rulePayload(rule, patch)`（否则未传字段被清空）。

---

## 5. 凭证状态机与撤回（V39）

- `POST /api/statements/{id}/withdraw`：**仅未成功推送可撤**；已推送成功返回 **409**。
- 撤回动作 = 标记 `review_status='WITHDRAWN'` + `withdrawn_at` / `withdrawn_by` + 写审计；**记录与凭证号保留**（不物删）。
- 银行数据层「已转入」口径**排除 WITHDRAWN** → 流水回到池中可重新制证。
- ⚠️ `uk_statement_record_company_no` 唯一键 ⇒ **重新制证是「复活同一条记录」**，不是新建。
- ⚠️ 写查询条件 `ne(reviewStatus,'WITHDRAWN')` 时**必须显式包含 NULL**（SQL `<>` 对 NULL 不成立，会漏掉未评审记录）。

---

## 6. 金蝶 REAL 对接

- 域 `xyrc.ik3cloud.com`，账套 `20210801002010962`，组织 **400**。
- AppSec 凭据**只存 ECS `.env`**，严禁入库、严禁进报告。
- `kingdee.auto-audit` 默认 **FALSE**（不自动审核）。
- REAL 类缺失 = 首启 502 事故重演 ⇒ jar 取证必须确认 `KingdeeSdkConfig.class` / `KingdeeSdkClient.class` 在包内。

---

## 7. 字典中心（V26）

- 模型：`sys_dict_type` → `sys_dict_item`。
- 管理端点 8 个，权限码 `system:dict:manage`(id=41)，授予角色 1/2。
- 消费端点 `GET /api/system/dicts/{typeCode}/items`：**仅需登录态**；类型不存在时**返回空数组、不抛错**。
- 生产已有 2 个类型：
  - `company_entity` —— `company` 表的只读镜像
  - `bank` —— `CMB`=招商银行、`CITIC`=中信银行
- 前端取数层 `bank-access/useBankNames.ts`：模块级缓存 + inflight 去重 + **`resolveBankName` 恒定引用** +
  `bankRevision` 驱动重建 + `bankAccountLabel`（「银行-尾号」）；覆盖 6 处调用，`BANK_NAME_TEXT` 退役为兜底。
  **内核侧必须把解析器作为参数传进列工厂**（不能在列定义里 import 单例）。
- 升级方案见 `docs/dict-center-plan-20260921.md`（准入四问 + 四形态）。
  现状缺口：无缓存、无审计、`updateItem` 可改 `itemCode` → 产生孤儿项、`extraJson` 只能手写。

---

## 8. 币种中文名（三处重复、口径不一）

| 位置 | 口径 | 用途 |
|---|---|---|
| `frontend/.../grid/kernel.ts:194` | 仅 ISO 码 | **死代码** |
| `frontend/.../bankQueryTexts.ts:31` | `10` / `01` + CNY | 屏幕显示 |
| `backend/.../BankDataExportService.java:56` | 仅 `10` | CSV 导出 |

⇒ **屏幕与导出可能显示不同**。这是已登记的口径风险，动币种显示前先统一三处。

---

## 9. 表格内核（V35）

- 位置：`frontend/src/components/grid/`（`kernel.ts` 自管 DOM + `ExcelGrid` 壳 + `useGridPreference`）。
- 四个口径：
  1. 排序**只作用本页**（不请求后端重排）
  2. **双合计并存**（本页合计 + 全部合计）
  3. 偏好存**服务端、账号级**
  4. 导出走**全量**；另有「导出选区」
- scope key：`bankdata.balances` / `bankdata.statements`；表 `account_preference(user_id, scope_key, payload)`。
- React 壳**只在挂载时** `createGrid` 一次（值类型 prop = 挂载时快照，见 MEMORY.md「前端」节）。

---

## 10. 测试覆盖率口径与 JaCoCo 本地跑法

**覆盖率盲区（真问题）**：`feishu` 50.1% / `citic.dlink` 21.1% / `statement.collector` 36.5%。
**假盲区（别误判）**：`rbac` / `audit` / `validation` / `user` / `config` 没有独立测试类，但实际覆盖率 **80–95%**
——它们靠**根目录下的跨模块集成测试**覆盖。
⇒ **「测试类数量」不能当覆盖率代理值**，会得出完全相反的结论。

**CI 日志里的 `IllegalClassFormatException ... jsqlparser` 是已知良性告警**，不要当失败处理。

**JaCoCo 本地出报告（易崩）**：需把 `agent.jar` 放在**纯 ASCII 路径**，并**显式给 `-DargLine=...`**；
漏掉 argLine 会表现为 forked VM 崩溃 + `Tests run: 0`（看起来像「测试没跑」，实际是 JVM 起不来）。

**本地 lint 口径**：用 `eslint src`，**不要用 `eslint .`**（eslint 不读 `.gitignore`）。

---

## 11. 前端工程配方（本地不可构建时的替代验证）

### 11.1 本地 `node_modules` 是空壳（2026-09-21 实测确认）

pnpm 在 `frontend/node_modules/.pnpm/...` 建的符号链接指向 `/c/Users/...`，
在 Windows 被解析成 `C:\c\Users\...` 而**断裂**；`typescript` 包实体是 **4.0K 空目录**。

- `test -d frontend/node_modules` **判不出来**（目录在）；唯一可靠探测是**真跑**：`./node_modules/.bin/tsc --version`。
- 结论：**tsc / eslint / vite build 一律不可用** ⇒ **前端 lint 与类型错误本地拦不住，只能靠 CI**（首轮 CI 前端红是常态，做好两轮准备）。
- CI 报错详情取法：run 未完成时 `gh run view --log-failed` 返回空 ⇒ 改用
  `gh api --allow-escape-sequences .../jobs/<id>/logs | sed 's/\x1b\[[0-9;]*m//g'`。

### 11.2 不跑构建时的替代校验

- `tsc --noEmit --ignoreConfig --noResolve` **只够查语法**（能抓 0 个 TS1xxx 之外的错，
  但真实类型错误会混进「缺依赖噪声」被忽略）。
- 隔离校验三步：①把同目录**真实被依赖的文件**一起传；②第三方用 ambient 桩
  `declare module 'react' { … }`（`--noResolve` 下仍生效）；③加 `--strict` 后
  **若只剩 TS2307「无法解析第三方」，即证明无内在类型错误**。
- **纯逻辑必须真跑**：tsc 转译产物 + 桩依赖 + `node_modules/react` 桩，用 node 断言。
  范例：`tmp/bankname-check/`（19/19 通过）。

### 11.3 其它前端硬约束

- **antd 中文化**：`ConfigProvider.config({ holderRender })` 管静态方法（`message`/`Modal` 等）；
  dayjs 须在 `main.tsx` 里 `import 'dayjs/locale/zh-cn'` + `dayjs.locale('zh-cn')`，否则 DatePicker 仍是英文。
- **React Router v7 future flag 有意未开**（开了会改渲染时序，影响内核挂载时机）。
- **本地 `vite build` 清空 `dist/` 会被 safe-delete 守卫拦**（9000+ 文件 > 阈值 50）⇒ 先 `mv dist tmp/` 再 build。
- **主 chunk 与 manualChunks**：主 chunk **禁 manualChunks**（白屏红线）；体积口径**只认 `vite build` 输出值**（base-1000），
  用「字节 ÷1024」会得 506.90 KiB 而误判「已回落」。

