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
- **前端已于 2026-09-21（W11b）上线**：银行中文名改由字典 `bank` 驱动，覆盖
  ①余额查询主体树账户节点（「银行-尾号」+ 悬浮提示完整户名/账号）②网格「银行」列（含导出与筛选候选）
  ③余额详情抽屉 ④银行账户页（列 / 新增下拉 / 连通测试标题）⑤公司档案页（摘要 + 下拉）。
  线上字典数据尚未维护 ⇒ 当前走代码兜底常量（`CMB`/`CITIC`），**新增银行在字典中心加项即可、无需发版**。
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

---

## 12. 后端工程规范细则（从 `MEMORY.md` 下沉，避免注入超限）

### 12.1 CI 保证边界
- backend job = `mvn verify -P '!citic-sdk'`，**覆盖率只上报、不设阈值**。
- citic / kingdee profile **按 file-exists 激活**；CI 环境没有 vendor jar ⇒
  `src/{citic-sdk,kingdee-sdk}/java`（8 文件 1334 行）**在 CI 里根本不编译**。
  ⇒ **CI 绿 ≠ 全路径可编译**。

### 12.2 覆盖率与 JaCoCo
见 §10（覆盖率盲区 / 假盲区、JaCoCo 本地跑法、`eslint src` 口径）。

### 12.3 CI shell 断言块的本地真验
- 把 `run: |` 块按**首行缩进**左移后写入 `tmp/x.sh` → `bash tmp/x.sh` 本地真跑。
- **必须配反证（变异）测试**：把断言放宽一点，看它是否仍会通过——放宽后仍通过说明它是**死守卫**。
- 详细流程见 skill `ci-assertion-block-verification`。
- **断言引用未跟踪文件 ⇒ 单独提交该 yml 必红**（用 `git ls-files <path>` 核对）。

### 12.4 统计与文件操作纪律
- `find` 命中 ≠ 已入库：用 `git ls-files` / `git check-ignore -v` 复核。
- 目录体积排序用 `du -sk` + `sort -k1 -rn`（`du -sh` 配 `sort -rn` 会把 `1.2G` 排到 `900M` 前，误排）。
- 删超大目录用 `robocopy <空目录> <目标> /MIR`（比 `rm -rf` 快且可控）。
- 同一文件多次 Edit 必须**串行**；pathspec 提交后用 `git status` + `git show HEAD:file` 复核。

### 12.5 MyBatis-Plus 约定
- 置 NULL 必须用 `LambdaUpdateWrapper.set(field, null)`（`updateById` 会忽略 null）。
- 加 DTO/实体字段后 **grep 全部 `new Xxx(...)` 重建点**。
- **写操作后必须断言影响行数**（`updated == 1`）——FINFLOW 已有的"假成功"事故全部出自
  「update 影响 0 行却照样返回成功」（见 `docs/issue-diagnosis-20260921.md`）。

### 12.6 测试纪律
- 本地全量：`~/.m2` 离线 `mvn.cmd test -o -Djacoco.skip=true`。
- **用例数随并行会话持续上涨**（同日 386 → 423 → 429），别拿旧数字当基线；数字**变小**才要查。
- H2 共享断言自带过滤；vendor SDK 边界 catch-Throwable → `BusinessException(502)`。

### 12.7 交付验证的三个「假绿」陷阱（2026-09-22 W14 实测）

1. **本地 `mvn test` 的增量编译会假绿**：改了 `record` 组件 / 构造器签名后，
   `mvn -o test-compile` 报 `Nothing to compile - all classes are up to date`，测试类**没重编**，
   看起来全绿；`mvn -o clean test-compile` 才暴露 `COMPILATION ERROR`。
   ⇒ **凡改动 public 签名/record 组件/构造器，本地验证一律先 `clean`。**

2. **Spring Boot jar 的 MD5 天生不可复现**：同源代码两次构建，大小相同、
   `unzip` 后 `BOOT-INF/classes` **逐字节一致**（`diff -rq` 0 差异），但 MD5 不同
   （zip 条目内嵌构建时间戳）。
   ⇒ **判「后端有没有变」要比内容**（`diff -rq` 解出的 classes）；MD5 只适用于
   「同一份文件在本地/ECS/容器内三处一致」的传输校验，不能用来判两次构建是否同源。

3. **前端改动可能落在不可达文件里（tree-shake）**：CI 全绿、`vite build` 成功，
   但新功能在构件里 0 命中。判据与做法：
   - 先 grep **本批独有的业务文案**（不是入口 hash —— 改动可能落在独立 chunk，入口 hash 本就不变）；
   - 0 命中时**先查可达性**：`grep -rn "<组件名>" frontend/src`，若只有定义、没有引用
     ⇒ 该文件是死代码（实例：`statements/VoucherDraftDrawer.tsx` 在 V34 ⑦ 后已无引用，
     只有同文件的 `AuditDrawer` 被 `VoucherCenterPage` 引用）；
   - 确认可达后再怀疑「构件陈旧 / gh 拉错 run」。
   - 交付前必须做「构件探针」：把本次新增的**用户可见文案**逐个 grep 线上 chunk（详见 skill `finflow-deploy`）。

---

## 14. 金蝶科目可用性与制证兜底策略（W14，全部实测）

### 14.1 四条实测铁律（真实账套 400，保存成功即删）
| # | 用例 | 结果 |
|---|---|---|
| 1 | 借方 `FACCOUNTID.FNumber=""`（空值） | ❌「请输入凭证数据，凭证分录不合法！」 |
| 2 | 借方完全不写 `FACCOUNTID` | ❌ 同上 |
| 3 | 借 `2241` 其他应付款（**父科目**，`BD_Account.FIsDetail=false`） | ❌ 同上（**父科目不允许记账**） |
| 4 | 借 `2241.99` 其他应付款-其他（明细 + 无必录维度） | ✅ 接受 |
| 5 | 借 `1901` 待处理财产损溢（明细） | ✅ 接受 |
| 6 | 借 `2241.05` 内部往来（明细但有必录维度） | ❌「必录维度未录入或不可用：组织机构」（对照组） |

⇒ ①「科目录空推上去」**不成立**；②只能换成账套里**存在且可记账**的科目；
③**科目名称不参与推送**（报文只发 `FNumber`）⇒ 名称不一致不该阻断。

探测脚本（可复用到其它账套）：`tmp/kd-probe-empty-account.py`、
`tmp/kd-probe-fallback-candidates.py`、`tmp/kd-find-fallback.py`
（在 ECS 上跑：凭据只从本机 `/opt/finflow/.env` 读，不外传；`SERVER_URL` 已含 `/k3cloud/`，别再拼一次）。

### 14.2 落地策略（代码位置）
- `KingdeeProperties.fallbackAccount`（默认 `2241.99`）/ `lowConfidenceThreshold`（默认 0.6），
  对应 `application.yml` 的 `kingdee.fallback-account` / `low-confidence-threshold`（env 同名大写），
  并已加入 `deploy/docker-compose.yml` 白名单。
- `KingdeeAccountCatalogService.check()`：名称不一致 → **以账套名称为准**返回带 note 的结果（不抛错）。
- `AiGlVoucherAssembler`：编码不存在/反查不到/置信度低于阈值 → 替换为兜底科目 + warning；
  兜底科目自身不存在 → 维持原拦截。
- 前端：`VoucherDocPage`（live 单据详情页）置信度可编辑 + 「保存置信度」→ `PUT /statements/{id}/voucher-draft`。

### 14.3 推送链路与金蝶侧校验顺序（原登记于 `pending-fixes.md`，条目闭环后下沉）
- **落点分流要覆盖所有推送入口**：AI 制证 PUSH、凭证中心 `StatementService.pushVoucher`、规则引擎，
  三条链路都必须按 `kingdee.voucher-target`（默认 GL）分流；漏一条即报「当前组织未启用出纳」
  （FIX-009 的成因就是凭证中心那条没分流，现已对齐）。
- **「总账已开通」≠「出纳已开通」**：`GL_VOUCHER`（总账）与 `AR_RECEIVEBILL` / `AP_PAYBILL`
  （应收/应付 + 出纳）是两套模块的单据，互不代表对方已开通。问金蝶管理员要问到点上
  （「出纳管理模块在境内主体是否已启用」）。
- **金蝶侧校验顺序**（实测得出，排查按此顺序看）：
  我方银行账户可用性 → 出纳模块启用 → 币别/汇率 → 其他字段。
  账户与组织不匹配时会先报「银行业务的结算方式，我方银行相关信息必录！」，
  **遮住**后面的检查 ⇒ 先确认账户与组织匹配，再看是否出纳/汇率问题。
- **对手方自动建档须三步**：`Save` + `Submit` + `Audit`。只 `Save` 的档案是暂存态
  （`FDocumentStatus='A'`），被单据引用时金蝶判「往来单位必填」——不是字段漏传（FIX-006 根因）。
  实现见 `RealKingdeeVoucherGateway.ensureBaseDataAudited`（建档后、复用历史档案前统一补审核 + 回查）。
- **公司域校验口径有两处实现**（`AccountingSuggestionService.requireInCompanyScope` 与
  `BankDataAccountingService.refreshAiSuggestion`）⇒ 改一处必须同步另一处，否则跨公司流水会被误拒
  （FIX-008 的成因）。放行凭据：`bankdata:cross-company:view`。

---

## 15. 模块结论明细（从 `MEMORY.md` 下沉，2026-09-22）

### 15.1 AI 制证
- **max_tokens（W12b 口径）**：制证阶段**不设上限** —— 网关仅当调用方显式传值时才发 `max_tokens`；
  分类能力仍显式 4096。排查：`GET /api/ai/call-logs` 看 `completionTokens` 与 `finish_reason`。
- **失败不再伪装成功（W12b）**：DRAFT 模式 AI 调用失败 ⇒ 行级 `FAILED` + 原因写
  `statement_record.review_comment` + `AI_VOUCHER_DRAFT` 失败审计；流水保持 `PENDING`
  ⇒ 凭证中心「待复核」仍看得到（旧行为 `DRAFT_CREATED` + 「AI 建议不可用」已废弃）。
- **异步化（V40）**：`POST /api/bank-data/ai-voucher` 返 `AiVoucherSubmitResult`；
  进度 `GET /api/bank-data/ai-voucher-jobs/latest`（无任务 `data:null`）。

### 15.2 凭证中心状态桶（W13 修正）
6 桶：待复核(`PENDING`) / 待推送(`APPROVED` 且未推送) / 已推送 / 失败(`push_status ∈ FAILED|GL_FAILED`) /
**已撤回(`WITHDRAWN`)** / ALL。
**ALL 条件 = `voucher_no 非空 OR review_status ∈ (PENDING, APPROVED, WITHDRAWN, REJECTED)`** ——
W13 之前只认前两者，导致线上 16 条记录（11 撤回 + 5 驳回）**全部落在桶外**、
驳回行的「重新打开」按钮不可达（实测：旧桶 0 行 → 新桶 16 行）。

### 15.3 假成功的通用纪律（W12b + W13 两次事故）
所有「假成功」都出自同一模式：**update 影响 0 行却照样返回成功**。
- W12b：AI 调用失败被 catch 后仍返回 `DRAFT_CREATED`；
- W13：写入条件 `.eq(reviewStatus, PENDING)` 对撤回态命中 0 行，仍返回「草稿已生成」。
⇒ **任何写操作后都断言 `updated == 1`**；撤回态重新制证要先 `reviveWithdrawn()` 复位为待复核。

### 15.4 同步计划（V25）
- `BankDataScheduledSyncService.scheduledRequestId` = f(公司, 账户, adapter, **T-1 全天窗口**)；
  窗口在同一自然日内恒定 ⇒ **一天内第二个计划时刻必然被幂等复用**（`BankDataSyncService` 命中同
  requestId 直接 `return 旧任务`：不新建、不执行、不打日志）⇒ 用户感受为「计划没启动」。
- 语义 = **一天只真正拉一次**（界面已写明）；W13 起调度补 `scan done` 汇总日志 + 下发被拒 WARN。
- 开关 `BANKDATA_SYNC_SCHEDULE_ENABLED=true`（线上已开）；禁选整点/半点（银行并发高峰）。

### 15.5 字典中心（V26）
- 管理端点 8 个（`system:dict:manage` id=41）；消费端点 `GET /api/system/dicts/{typeCode}/items`
  仅需登录态，**类型不存在返空数组、不抛错**。
- **前端已上线（W11b）**：银行中文名由字典 `bank` 类型驱动（`useBankNames`，覆盖 6 处），
  `BANK_NAME_TEXT` 降级为兜底 ⇒ **新增银行在字典中心加项即可、无需发版**。
- 方案：`docs/dict-center-plan-20260921.md`。

### 15.6 余额查询分组汇总口径（2026-09-21）
**不显示「可用余额合计」** —— 同一账户在筛选区间内可能有多天余额，逐行求和属重复计入，
且余额可能是多币种、不可相加；「M 个账户」改为**按账户去重**（原先用行数）。
流水侧「N 笔 · 借 X / 贷 Y」口径正确（一行即一笔），不变。




---

## 13. 金蝶核算维度与弹性域槽位（V42）

### 13.1 槽位**不必盲试**（方法论突破，2026-09-21）
`QueryBusinessInfo(GL_VOUCHER)` 返回的每个 `FDETAILID__FFxxxx` 字段**自带 `Name=维度名`** ——
一次请求就能拿到维度 ↔ 槽位的完整对照，不需要逐个字段试探。

实测拿到的槽位：`FFLEX4` 供应商 / `FFLEX5` 部门 / `FFLEX6` 客户 / `FFLEX7` 员工 /
`FF100002` 银行账号 / `FF100003` 项目 / `FF100004` 投资者 / `FFLEX13` 客户分组等。
实证：凭证 **16059**（供应商 FFLEX4，基线报错点就是「供应商」）、**16060**（供应商+客户+员工三槽位同凭证）
保存成功后即时删除并回查为空 ⇒ **3 次 Save 请求搞定全部槽位**。

台账：`docs/kingdee-openapi/kingdee-dimension-slot-ledger-20260921.md`。

### 13.2 配置化（V42 两表 + 界面）
- 表：`kingdee_dimension_slot`（15 条 seed = 14 维度 + 业务线）、`kingdee_dimension_mapping`（值映射，空表待导入）。
- 界面：规则中心 › **核算维度配置**（槽位配置 / 值映射 / 批量粘贴导入）。
- 多维度注入：一个科目可挂**多个必录维度** ⇒ 分录需**联合注入**（`extraDimensions`）；
  未就绪的维度**推送前拒绝**并给出补齐指引（报告式口径，不做静默降级）。
- 3 个匹配算子：`IN_SUPPLIER_LIST` / `IN_EMPLOYEE_LIST` / `IN_CUSTOMER_MAPPING`。

### 13.3 已知缺口
- **账套没有「业务线」维度**（`BD_FLEXITEMPROPERTY` 14 个维度类型里没有）⇒ 已拍板：用**项目 ZDY0002** 承载。
- **合同号槽位**（ZDY0004）该账套未在凭证模板启用，待确认。
- 值映射表为空：供应商 412 条 / 员工 354 行等需财务整理成「来源值 + 编码」后批量粘贴导入。
- 图虫侧 19 条规则仍未入库（能力已齐，见 `docs/pending-fixes.md` FIX-010 前置清单）。

