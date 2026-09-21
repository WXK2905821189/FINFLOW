# FINFLOW 待修清单（部署侧登记 · 交全栈工程师对话处理）

> 维护：部署工程师对话。发现代码缺陷不改代码，登记于此（文件+位置+现象+证据），由全栈工程师对话修复后出产物。
> 部署侧收到新产物时核对 MD5 + 构建时间，替换前备份旧产物。

---

## FIX-001（P0 · 线上白屏，阻塞浏览器验收）2026-09-02 16:28 登记

### 现象
- 公网 `http://101.200.72.87/` 白屏，`<div id="root">` 为空。
- 浏览器控制台：`rc-components-DV2vU3nI.js:1 Uncaught TypeError: Cannot read properties of undefined (reading 'version')`
- 影响：全部前端功能不可用（非局部页面问题，module 预加载阶段即中断）。

### 现象链 / 证据（部署侧已完成的排查）
1. **线上产物 = 本地部署包原样**：线上 index.html 引用的 chunk hash（index-CRV9K5yY / rc-components-DV2vU3nI / framework-CizkEVCI / antd-BdPy1Fz_ 等）与 `tmp/finflow-deploy-20260902/web-dist/assets` 完全一致；`rc-components-DV2vU3nI.js` 线上/本地 MD5 均 `876bf7a3e6172c3cfc7d58db7226b504`。→ 排除部署覆盖/半覆盖事故。
2. **本地原样复现**：Edge headless 打开本地静态服务的 `frontend/dist`（同源产物），console 报完全相同的错误：`Uncaught TypeError: Cannot read properties of undefined (reading 'version'), source: .../assets/rc-components-DV2vU3nI.js (1)`，root 为空。→ 产物自身运行时缺陷，与服务器/网络/用户浏览器缓存无关。
3. **报错点定位**：`rc-components-DV2vU3nI.js` 第 1 行（minified）`var Mc=Number(o.version.split(".")[0])` —— `o`（React 实例，chunk 内用作 `o.useRef`/`o.Component`）为 `undefined`，模块**顶层求值阶段**即读取 React 版本号，抛 TypeError 中断整个模块图。
4. **React 来源链**：rc-components chunk 头部 `import{... r as o ...}from"./framework-CizkEVCI.js"`（React 由 framework chunk 导出，framework 尾部 `export{... k as r ...}`，`k` 即 framework 内联的 react 对象）；另一路 `R as le` 也指向 React。跨 chunk 的 React 导出在运行时为 undefined。
5. **引入提交**：`7280e81`（2026-09-01 17:28，feat: managed adapter call executor and **frontend code splitting**）首次加入 `vite.config.ts` 的 `manualChunks`：react/react-dom/react-router → `framework`；`/rc-` → `rc-components`；antd → `antd` 等。提交说明仅验证 `vite build ok`，**未做浏览器运行时验收**（构建绿 ≠ 运行绿）。此前 f45ca7e（v0.1.0）为默认打包无 manualChunks。

### 根因线索（供全栈工程师对话参考，非结论）
- 嫌疑集中在 manualChunks 手工分桶导致的 **ESM chunk 间 React 互操作/绑定或求值顺序问题**：rc-* 库（rc-util 等）以 React default import 编译，打包后从 framework chunk 跨 chunk 取 React，运行时为 undefined。
- `version.split` 源码同类模式见于 `antd/es/config-provider/UnstableContext.js`（`Number(React.version...)` 类检查）。

### 建议修复方向（任选，需实测验证）
- A. 移除 `/rc-` 的独立分桶（rc-* 并入 antd 桶或 vendor 桶），重构建验证；
- B. 将 react/react-dom 显式拆为单一独立 chunk 并核查其 export 在 framework 内的连接方式；
- C. 核查 pnpm 依赖树 react 是否多副本（`pnpm why react`），排除 dual-package 干扰；
- D. 修完**必须浏览器实测首屏**：页面渲染非空、console 无 TypeError（部署侧会以 Edge headless + 公网双重验收兜底）。

### 验收标准
- 重新构建产物替换后：`curl http://101.200.72.87/` 引用的 chunk hash 更新；浏览器打开登录页正常渲染；console 无 `reading 'version'` 报错；`/api/health` 仍 401（链路不回归）。

### 交付要求（对全栈工程师对话）
- 新产物（web-dist 或前端 dist 目录打包）+ **目录级 MD5 清单** + 构建时间；部署侧负责上传替换 `/opt/finflow/web-dist`（替换前备份当前 web-dist）并重建 nginx 容器。

### ✅ 已修复（全栈工程师 2026-09-02 17:00 回填）

- **修复方案（对应建议方向 A+，取最彻底路径）**：commit `c268f38` 整段移除 `vite.config.ts` 的 `manualChunks` 手工分桶，页面级拆分保留 React.lazy 动态 import，共享依赖交 Rollup 自动分配（与 f45ca7e v0.1.0 默认打包一致——当时浏览器运行正常）。**不留任何手工桶**，根除跨 chunk React 互操作问题，而非仅在 rc-* 桶间挪移。
- **验证证据（全部实测）**：
  1. `vite build` 27.96s 成功，产物 19 个 chunk，**无 rc-components/framework/vendor 手工桶残留**（grep=0）；
  2. Edge headless + `--virtual-time-budget=8000` 打开新产物：root 渲染完整登录页（FINFLOW 品牌 + 登录表单 + "账号由管理员开通"），console 无 `reading 'version'`；
  3. 部署包内 web-dist 本体单独起服务实测同样渲染正常（DOM 205,471B 非空）；
  4. 构建绿 ≠ 运行绿已封堵：本次修复以浏览器实测首屏为验收，不再只信 vite build 成功。
- **新产物交付**：`tmp/finflow-deploy-20260902.tar.gz`（43,373,578B，16:57 重打）+ **目录级 MD5 清单** `tmp/finflow-deploy-20260902/web-dist-MD5.txt`（21 文件）。jar 未变（36fc54d6…，16:43:33）——本次为纯前端修复，**部署侧仅需替换 `/opt/finflow/web-dist` 静态资源 + 重建 nginx 容器，无需重启 app 容器**。
- **部署侧验收建议**：按上方"验收标准"公网复核；替换前备份当前 web-dist。

---
<!-- 后续缺陷继续追加：FIX-002 ... -->

## FIX-002（P2 · 技术债登记，非阻塞）2026-09-03 登记（来源：独立架构审阅 docs/architecture-review-2026-09-03.md）

> 非线上缺陷，为审阅提出的长期质量项，交全栈工程师对话排期处理。当日已同步完成的遗留项：
> P0 收尾（新增 `CmbRealPathTenantIsolationIntegrationTest`，以 FakeCmbServer 全链路覆盖真实直联路径的租户隔离）
> 与 P1-1（citic-sdk profile 由 activeByDefault 改为按 SDK jar 文件存在自动激活）不在此列——已完成并入库。

### P2-1 · CI release-contract grep 断言易碎
- **位置**：`.github/workflows/ci.yml` release-contract job，bankdata 目录 `git grep -E '(HttpClient|RestTemplate|WebClient|java\.net\.http|https?://)'` 守卫 + 机密扫描。
- **现象**：守卫靠"源码/注释无 scheme URL"等文本断言表达，35 条断言对重构极其敏感——改一行 javadoc 即 CI 红（2026-09-03 已实际踩中：CmbAdapterProperties javadoc 带 URL 导致 36618b5 才修绿）。
- **方向**：把"禁止硬编码银行网关 URL"转化为真实单测（扫描 class 常量/配置绑定而非源码文本），或建立显式豁免清单，降低误伤率。

### P2-2 · 覆盖率盲区
- **位置**：jacoco 报告——`operations` 包约 5.6%、`kingdee` 包约 8.8%。
- **现象**：两包为连接配置/金蝶凭证推送链路，主流程测试几乎为空；当前 CI 不设覆盖率门槛，盲区会随功能叠加持续扩大。
- **方向**：优先给 `ConnectionOperationsService`（overview/configuration/dataCapability 投影，已在 2026-09-03 语义改造中承担真实状态判定）补契约测试；kingdee 按推送状态机补路径测试。

### P2-3 · 前端主 chunk 偏大
- **位置**：`vite build` 输出主 chunk 约 795.70 kB（>500 kB 警告线）。
- **现象**：FIX-001 移除 manualChunks 后运行时稳定性恢复，但代价是主包体积集中；React.lazy 路由级拆分已缓解，首屏仍一次性加载 antd 主体。
- **方向**：评估 antd 按需引入 / babel-plugin-import、路由级预取策略；改动后必须浏览器实测首屏（沿用 FIX-001 验收标准），避免回归 chunk 互操作问题。

---

### ✅ FIX-002 清偿记录（全栈工程师 2026-09-04 回填）

**P2-1 · CI grep 断言易碎 → 已加固**：bankdata URL 守卫的豁免从隐式 `-vE` 正则提升为显式 `url_allowlist` 变量（XML 解析器加固 URI 白名单），报错信息从一句 echo 改为指路式（说明网关地址应登记在 docs/ 而非源码/注释）。bankdata 源码 URL 字面量已只剩 XML 安全 URI 3 处。

**P2-2 · 覆盖率盲区 → 已补 `ConnectionOperationsServiceTest`（Mockito 纯单测 8 用例）**：覆盖装配推导（REAL 装配列表→connectedBanks 文案动态化）、资源门（资源不存在→BusinessException 404）、logs 投影（状态/请求号筛选、分页、空连接）、**并抓出真实生产缺陷**：`logs()` 中 `.eq(condition, normalize(status))` 因 Java 先求值参数导致 `normalize(null)` NPE——UI 不传状态筛选即 500。已修复（`normalize` 移入条件分支前判空）。kingdee 包测试仍未覆盖（后续批次）。

**P2-3 · 主 chunk 798→578 kB（gzip 203→192 kB）**：Shell / Login / Forbidden 三者 React.lazy 化（Login、Forbidden 从 `auth/pages.tsx` 拆为独立文件，避免同文件守卫组件拖累拆分）。无 manualChunks（FIX-001 红线），纯页面级 lazy。Edge headless 实测首屏登录页完整渲染（FINFLOW 品牌/表单/文案全在 DOM，无 Uncaught）。剩余 578 kB 为 react/antd 基座，继续压缩只能上 manualChunks——已被 FIX-001 教训否决，警告线调至 600 kB 并在 vite.config.ts 注释说明理由。

---

## FIX-003（P2 · 报文证据链与接口补全，非阻塞）2026-09-04 登记（来源：招行云直联文档对照审查）

> 对照 openbiz.cmbchina.com 三份接口文档（NTQADINF 余额 / trsQryByBreakPoint 流水 / NTQABINF 历史余额）与线上 raw 数据发现。当日已顺手完成的部分不在登记范围（NTQADINF 漏解析的 stscod/opndat/inttyp/dpstxt 四个 Y 必返字段已随 V21 补齐）。

### P2-4 · raw-message 报文体存的不是银行原始响应
- **位置**：`BankRawMessage.payload` —— 当前存的是 FINFLOW 入库后的 `BankDataCollection.toJson()`（约 300B），不是招行解密后的原始响应 body（几 KB 级）。
- **现象**：用户在「原始报文」/行级报文抽屉看到的不是"银行真的回答了什么"，而是"我们解析后保留了哪些字段"。适配器丢字段（如 V21 前的 stscod）raw 里也丢——报文证据链与解析正确性耦合，违背该模块"报文体才能证明银行真的回答了"的自身定位。
- **方向**：DB 加列 `raw_response_body`（或复用 payload 双视图：原始 + 入库后），适配器解密后先落原始 body 再投影；需评估体积增长与保留策略联动、敏感字段脱敏范围（现在 sanitize 只在日志层）。涉及迁移 + 双写 + 隐私评估，故排 P2。

### P2-5 · NTQABINF 历史余额接口未实现
- **位置**：招行云直联 7 号接口 NTQABINF（6 响应字段：accnbr/accnam/onlblv/avlblv/dat/hour 之类按文档），FINFLOW 未实现。
- **现象**：余额页只有"当下快照"（NTQADINF），查不到历史某日的余额；对账场景如需 T-1/T-N 余额只能靠流水倒推。
- **方向**：非 v0.2 必需（测试用户申请表未开通）。若 M2/M3 阶段对账需要历史余额锚点，再按文档实现（接口简单：单账户 + 日期 → 一行余额）。



## FIX-004（P3 · 文案误导，非阻塞）2026-09-04 登记 · **2026-09-07 已修复（commit 随下次部署生效，见文末）**（来源：用户问询采集数据口径时发现）

- **位置**：`backend/.../bankdata/BankDataSyncExecutor.java:280`
- **现象**：同步完成日志 `SYNC_COMPLETED` 的 message 是写死的 "Bank data synchronization completed without external network calls"——模拟数据时代的遗留文案。真实银行调用成功（task 5/6，reqid 均为真实招行 reqid）也打印这句，采集失败日志页会误导运维以为"没走外网"。
- **方向**：改为中性文案（如 "Bank data synchronization completed"）或带上真实调用计数；随手可改，随下一次构建携带，无需单独部署。
- **附带说明**：任务计数口径——raw/normalized 计数**含余额快照**（STATEMENT 拉取每窗口附 1 条 NTQADINF 余额），所以 raw=10=9 流水+1 余额；重复判定键为 company_id+bank_account_id+statement_no+transaction_time+amount 五元组。前端任务详情"服务端摘要"如需易读可一并展示该口径。

> **FIX-004 处置记录（2026-09-07）**：`BankDataSyncExecutor.java` SYNC_COMPLETED 文案已改为中性 "Bank data synchronization completed"（含注释溯源）。无测试断言该文案，改动零波及。已提交 master；按既定判断不单独部署，随下一次构建自然生效。

---

## FIX-006（P1 · 真实环境推送失败：自动建档的基础资料未审核）2026-09-21 登记

### 现象
- 真实账套（xyrc.ik3cloud.com）推收款单失败，凭证单据详情显示：
  `AR_RECEIVEBILL: 字段"往来单位"是必填项`（推送状态 FAILED）。

### 根因（只读实测证据，非推测）
- `RealKingdeeVoucherGateway.resolveCounterparty` 走通了：对手方解析/自动建档成功，payload 里
  FCONTACTUNIT/FRECTUNIT 均已按解析结果写入 —— 所以**不是字段漏传**。
- 实测 `ExecuteBillQuery(BD_Customer, FilterString="FName like '%北分%'")` 返回：
  `['FINFLW42bd000fc3', '北分108', 'A', 'A']` → **FDocumentStatus='A'（暂存，未提交未审核）**。
- 金蝶业务单据只能引用**已审核**的基础资料；暂存态档案被引用时，单据校验把「往来单位」判定为未填，
  故报「必填项」。`autoProvision` 目前只调 `Save`，缺 `Submit` + `Audit` 两步。

### 方向
1. `autoProvision` 建档成功后补 `Submit` + `Audit`（链路已在 demo 环境实证：单据 submit/audit 全通）；
   幂等分支（重复建档报错）回查后同样要确认文档状态，必要时补审核。
2. 兼容历史脏数据：解析到已存在但 `FDocumentStatus='A'` 的档案时，先补审核再推送，避免用户手工去金蝶点。
3. 前端把该报错翻译成可执行指引（单据详情诊断区：原文 + 根因 + 三步处置），见
   `docs/ui-voucher-doc-demo-20260921.html` 改造稿。
4. demo 环境（apiexp）不校验基础资料状态，故此前未暴露——真实联调的遗留验证点之一。

### 验收
- 新对手方首次推送：自动建档 → 自动审核 → 收款单保存成功，返回凭证号；
- 已存在暂存档案：推送前自动补审核，不再报「往来单位必填」。

### ✅ 处置记录（2026-09-21 回填，代码已完成、真实账套待验）
- **实现**：`RealKingdeeVoucherGateway` 新增 `ensureBaseDataAudited(formId, number, name, knownStatus)`——
  建档成功后、复用历史档案前统一补 `Submit` + `Audit`，并回查 `FDocumentStatus`；回查仍非 `C`（已审核）
  则抛可执行错误（含编码/名称/已尝试动作/处置步骤），**不带病推单**。
  配套把 `queryCounterpartyNumber` 升级为 `queryCounterpartyRef`（多取一列 `FDocumentStatus`）。
- **测试**：`RealKingdeeVoucherGatewayTest` 18 → **21 例**（新增：建档后补审核、历史暂存档案复用前补审核、
  补审核无效则 FAILED 且不调 save、已审核档案跳过冗余审核调用）；全量后端 **351 测试全绿**。
- **边界**：集成用户（王一霏）在金蝶侧需具备**基础资料审核权限**才能自动补审核；
  无权限时用户会看到明确错误 + 「去金蝶提交审核」指引，而不是金蝶的「往来单位必填」。
- **真实账套验证（2026-09-21 已执行，结果见下）**：账套内 `FINFLW42bd000fc3`（北分108）仍是暂存态，
  对它执行提交+审核后重推那张失败单即可端到端验收——属写操作，等确认。

### ✅ 真实账套验收结果（2026-09-21 10:30，用户授权后执行）
- **档案状态已修正**：`FINFLW42bd000fc3`（北分108）`FDocumentStatus` **A（暂存）→ C（已审核）**，
  Submit/Audit 均返回 `IsSuccess:true`（Id 9172304）。⇒ 集成用户**具备基础资料审核权限**，
  FIX-006 的自动补审核机制在真实账套成立。
- **原报错已消除**：用与 `KingdeeBillPayloadBuilder` 同构的 payload 重试保存收款单，
  `AR_RECEIVEBILL: 字段"往来单位"是必填项` **不再出现**——FIX-006 修复有效。
- **但暴露出下游阻塞（非本缺陷范围，见 FIX-007）**：新报错为
  「当前组织未启用出纳，请启用后再进行操作！」——收付款单属「出纳管理」子系统。
- **副产物**：真实账套 `AR_RECEIVEBILL` 元数据（189 字段 / 114KB）已归档
  `docs/kingdee-openapi/openapi-docs/AR_RECEIVEBILL/QueryBusinessInfo.json`（原快照仅 2KB 残缺）。
  实测确认 `RECEIVEBILLENTRY` **没有 EXCHANGERATE 属性**（汇率在表头），字段名以归档快照为准。

---

## FIX-007（P1 · 阻塞：收付款单路线在真实账套不可用）2026-09-21 登记

### 现象
- 入库组织 + 我方银行账户都正确后，保存收款单仍被拒：
  `当前组织未启用出纳，请启用后再进行操作！`（FieldName=FDATE，MsgCode=11）
- 穷举探测（用各组织**自己的**银行账户，失败的保存不落任何数据）：
  | 组织类型 | 结果 |
  |---|---|
  | 境内主体 300/400/410/411/500/710/720/900/301/412/413/420/421/600/700/800 | **全部「未启用出纳」** |
  | 境外/外币主体 100/110/200/201/203/210 | 报「汇率必须大于0」（汇率校验先于出纳检查，未能判定） |
  | 202 | `值为202的收款组织(FPAYORGID)赋值失败`——集成用户对该组织无权 |

### 根因
「出纳管理」模块在该账套的境内主体**全部未启用**（模块授权/启用属金蝶侧配置，非代码问题）。

### 校验顺序（本日实测得出，对后续排查有用）
`我方银行账户可用性 → 出纳模块启用 → 币别/汇率 → 其他字段`
- 我方账户与入账组织**不匹配**时先报「第1行分录，银行业务的结算方式，我方银行相关信息必录！」
  （账户解析失败，会**遮住**后面的出纳检查——排查时先确认账户与组织匹配，再看是否出纳问题）
- 组织与账户均正确后，才暴露「未启用出纳」

### 处置选项（需业务拍板）
- **A. 请金蝶启用「出纳管理」模块**（境内主体），保留现有收付款单落点；
  可能涉及模块购买，且启用后仍需逐账户映射 FACCOUNTID。
- **B. 把 AI 制证落点切到总账凭证 GL_VOUCHER**：9/11 已在**同一真实账套**实测
  保存/删除/查询全通（见 `docs/kingdee-openapi/kingdee-voucher-selfdev-feasibility-20260911.md`），
  **不需要出纳模块**，且与「凭证规则引擎」方向一致；代价是「制证」而非「出纳单」，
  出纳端银行日记账不再自动生成。

### ✅ 选 B 并已实施（2026-09-21，用户拍板「先按 B 行动」）
代码侧完成，**375 后端测试全绿**；真实账套维度形态已实测打通（见下）：

| 实施项 | 内容 |
|---|---|
| 落点开关 | `kingdee.voucher-target`（env `KINGDEE_VOUCHER_TARGET`，**默认 GL**）；`BILL` 保留，出纳启用后可一键切回 |
| GL 路由 | `BankDataAccountingService` PUSH 模式 → `KingdeeVoucherEngineService.pushAiVoucher()`：读 `ai_suggestion_json` 分录 → 组装 → GL_VOUCHER Save → 回写 `GL_PUSHED/GL_FAILED` + 审计 |
| 分录组装 | `AiGlVoucherAssembler`：科目编码缺失→按名称在账套科目表反查（唯一命中即用，重名列出候选拒绝）、方向/金额/平衡校验、银行类科目注入维度 |
| 科目校验 | `KingdeeAccountCatalogService`（BD_Account 只读缓存 600s）：科目不存在或**名称与账套不符即拒绝**（防静默记错账，实测 2232=应付股利）；目录不可用时降级放行并在消息里标注「未校验」 |
| 维度注入 | 形态 `"FDetailID": {"FDETAILID__FF100002": {"FNumber": "<CN_BANKACNT 账号>"}}`；槽位可配 `kingdee.gl.bank-dimension-slot` |
| 失败可见 | GL 组装/推送失败 → 写 `pushStatus=GL_FAILED` + `push_message` + 审计，凭证中心直接展示原因 |

**实测校准（真实账套，凭证 16043 保存成功后即时回滚）**：
- 维度二层形态确认（内层键必须是带前缀的完整字段名；裸槽位名与数组形态均被拒）；
- **FDC 贷方值修正为 2**（原代码用 -1，从未在真实环境验证；两次实测成功保存用的都是 2）；
- 维度可选值 = `CN_BANKACNT` 账号档案（用户截图确认）。

**待办**：① 部署后在用户环境跑一次真实端到端（界面点「AI 制证并推送」→ 金蝶出现 GL 草稿）；
② 出纳模块启用后决定是否切回 BILL；③ 账户映射的自动匹配命中率需在**用户真实账户集**上实测
（本地无法验证——FINFLOW 侧账户数据在用户库中）。

### ✅ 待办 ③ 已落地：银行账号维度改为账户级映射（2026-09-21，用户要求「和银行账号模块联动」）

原先维度值取全局 `KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER`（一个值打天下）属联调期占位。
现改为**按该笔流水所属的我方账户**取值——一家公司有多个银行账户，凭证里银行存款分录记的
只是其中某一个。

| 实施项 | 内容 |
|---|---|
| 迁移 | **V41** `bank_account.kingdee_account_number`（金蝶 CN_BANKACNT 档案编码，可空=未映射） |
| 网关 | `KingdeeVoucherGateway.queryBankAccountCatalog()` 只读拉 CN_BANKACNT（编码/名称/所属组织） |
| 匹配服务 | `KingdeeAccountMappingService`：账号规范化精确匹配 → 多命中用「公司→金蝶组织」消歧 → **唯一命中才自动写回**；多义与零命中留给人工 |
| 制证取值 | `BankAccountDimensionResolver` 按流水所属账户取；**未映射即阻断**（400 + 指明处置入口，用户拍板口径） |
| 接口 | `GET /api/bank-accounts/kingdee-mapping`（只读预演）、`POST .../auto-match`（写唯一命中）、`PUT /api/bank-accounts/{id}/kingdee-mapping`（人工指定/清除） |
| 前端 | 银行账户页新增「金蝶账户」列 + 「金蝶账户映射」抽屉（一键自动匹配 + 候选点选 + 手工输入） |
| 口径 | `accounting_mode=MANUAL` 账户标记 `NOT_REQUIRED` 不需要映射；全局默认值降级为「流水无账户归属时的兜底」 |

**真实账套实测依据（只读）**：142 个档案分属 23 个组织；其中 **102 个 FNumber 就是账号本体**
（可自动匹配），其余为虚拟账户编码（支付宝邮箱 / 薪福通 / 分贝通 / 携程商旅）须人工指定；
同一账号可能在不同组织各有一个档案 ⇒ 必须用组织消歧。

**实施中发现并修正的判定缺陷**：「零命中时给出的组织候选池」被 `ambiguous()` 误判为「多义」，
导致未命中行统计与展示错误；由 `KingdeeAccountMappingServiceTest` 抓出，`MatchOutcome` 拆成
`ambiguousHits` 与 `candidates` 两个字段后修正。

### 附带配置项
- `accounting_mode=MANUAL` 与「出纳模块未启用」是两回事，勿混。
- 我方银行账户必须与入账组织匹配：`CN_BANKACNT.FCreateOrgId` 需等于推送组织
  （实测 142 个账户分属 23 个组织，境内各主体都有自己的账户可用）。

### 澄清：「总账已开通」≠「出纳已开通」（2026-09-21 用户问询后只读核实）

用户提出「9/11 推测试凭证成功过、我在金蝶里看到了，为什么现在说要启出纳」。核实结论：
**两次推送的是两套模块的单据**，互不代表对方已开通。

| 单据类型 | 所属模块 | 9/11 实测 | 2026-09-21 只读核实 |
|---|---|---|---|
| **记账凭证** `GL_VOUCHER` | **总账** | ✅ 保存/删除/查询全通（记-15948、记-15949） | **账簿 400 有 2016 年起大量凭证**（状态 C 已审核）⇒ 总账确实已开通且在用 |
| 收款单 `AR_RECEIVEBILL` | 应收 + 出纳相关 | 仅在演示环境（apiexp）通过 | **账套内该表为空**（从未建成过一张） |
| 付款单 `AP_PAYBILL` | 应付 + 出纳相关 | 演示环境即受 apiexp 缺陷阻塞 | **账套内该表为空** |

- 另外核实：`GL_VOUCHER` 中已查不到 9/11 的 15948/15949（两张测试凭证现均已不在账套，属后续清理）。
- 账簿（`BD_AccountBook`）只有 8 个：400/100/200/300/500/600/700/800。
- 因此「要问金蝶管理员」的问题应收窄为一句：**「出纳管理模块在境内主体（300/400/410 等）是否已启用？」**
  —— 问「我模块开了没」会得到「总账开了」的肯定答复，与本次阻塞不是同一件事。

---

## FIX-005（P3 · 架构演进预留：金蝶凭据多账号化，非阻塞）2026-09-16 登记

> 触发：用户提出「未来多账号之后，不同人登录系统使用的金蝶云凭证不同」。经鉴权模型澄清后定调为**公司级凭据 + 用户名映射**，当前单账套阶段不实现，多公司接入时启动。

### 现状
- `KingdeeSdkClient` 用全局 `KingdeeProperties`（环境变量）构建唯一 `IdentifyInfo`（AcctID/AppID/AppSec/王一霏）——全系统单身份。
- 接缝已就绪：`pushVoucher(id, operatorId)` 链路自带 `companyId`（companyScope 解析）+ `operatorId`；`StatementRecord` 自带 `companyId`，网关接口 `push(statement)` **无需改动**。

### 鉴权模型结论（设计依据）
| 层 | 归属 | 多账号方案 |
|---|---|---|
| AppID/AppSec | 第三方应用级（金蝶管理员建一次） | **按公司/账套分**（不同账套=不同 AcctID），不按人分 |
| 授权用户名 | 决定金蝶侧操作身份（权限+审计日志） | **按人映射**：同一 AppID 绑定多个金蝶用户，`FINFLOW user → kingdee_username` 映射表 |
| 账簿/组织 | 凭证挂哪个主体 | 已按 companyId，改 env 为按公司解析 |

### 实施方向（方案 A，已对比否决纯用户级 B / 维持现状 C）
1. 新增 `kingdee_provider_config` 表按公司存 AcctID/AppID/AppSec（AES-256-GCM 加密落库，复用 V29 飞书向导的加密存储模式），查不到回落 env 默认（向后兼容）。
2. 新增 `FINFLOW 用户 → 金蝶用户名` 映射表；金蝶管理员在 Finflow 应用下绑定多用户即可，AppSec 不动。
3. 连接测试按钮（`pingKingdee`）同步按公司探测。
4. 工作量预估 2~3 天；触发时机 = 第二家真实账套接入时，或财务要求金蝶侧审计区分到人时（仅做第 2 步，半天）。

### 验收标准（实施时）
- 不同 companyId 的流水推送走各自账套（凭据加密落库、接口回显仅尾 4 位 hint）；
- 未配置的公司回落现有 env 凭据不中断；
- `pingKingdee` 按公司返回各自连接状态。

---

## FIX-008（P1 · 跨公司流水的 AI 建议被误拒 → 降级「AI 建议不可用」）2026-09-21 登记并处置

### 现象
用户报「很多流水 AI 制证了，但结果提示 AI 建议不可用」（线上 W11）。

### 根因（线上只读实测取证，非推测）
`AccountingSuggestionService.requireInCompanyScope` 硬校验「流水 companyId == 操作人 companyId」，
**漏了跨公司放行**——而同项目的 `BankDataAccountingService.refreshAiSuggestion`（W3，2026-09-18）
已有正确口径（`record.getCompanyId() != companyId && !crossCompany`）。两个类各自实现了一遍同样的
校验，W3 只修了其中一处。

实测对照（线上 `/api/statements` + `/api/ai/call-logs`）：

| 流水 | 所属账户 | 账户 companyId | 结果 |
|---|---|---|---|
| id=10 / 13（C0947J…） | 账户 9（CMB 尾号 0201） | **2 上海图虫网络科技** | ❌ 404「流水不存在或不在当前公司域内」→ 降级「AI 建议不可用」 |
| id=12（BG1F07…） | 账户 7（CITIC 尾号 8042） | **1 测试公司**（= 操作人） | ✅ AI 建议正常（call-log id=18 成功） |

`ai_call_logs` 佐证：最近调用全部 `SUCCEEDED`（completionTokens 727 / 818，远未贴顶 2048）
⇒ **不是模型、密钥或截断问题**，是业务层的公司域校验误拒。且只有 `REVIEW_PENDING` 的流水会走到
AI 建议（PUSHED / REJECTED / APPROVED 都被前置分支短路），所以「很多流水」= 全部待复核的
**跨公司**流水。

### 修复
`requireInCompanyScope` 与 `refreshAiSuggestion` 口径对齐：持 `bankdata:cross-company:view`
的用户可对他司流水取 AI 建议；无权限用户对非本公司流水仍 404（保持不暴露存在性）。

### 验收
- `AccountingSuggestionRuleInjectionTest` 新增 2 例（跨公司放行 / 无权限拒绝）→ 该类 5 例全绿；
- 全量 **388 测试 0 失败**；
- 线上验收（部署后）：对账户 9 的流水点「AI 制证为草稿」，复核意见应出现正常 AI 建议内容
  而非「AI 建议不可用」。

---

## FIX-009（P1 · 凭证中心的推送未切总账落点 → 仍报「未启用出纳」）2026-09-21 登记并处置

### 现象与**此前的误判纠正**
用户报「推送凭证时依然提示组织未开启出纳模块」。我先前判断为「版本落后、W11 未部署」——
**该判断错误**。带 token 探端点证实线上**已是 W11**：
`GET /api/bank-data/ai-voucher-jobs/latest` → 200（V40）、`GET /api/bank-accounts/kingdee-mapping`
→ 200（V41）、`GET /api/statements/kingdee/ping` → 200（mode=REAL 已连真实账套）；
对照组 `GET /api/definitely-not-real-xyz` → 500（符合「未注册路径」口径）。

### 真根因
W11 只把**「一键 AI 制证」的 PUSH 模式**切到了总账；而**凭证中心的推送按钮**走的是
`StatementService.pushVoucher()` → `kingdeeGateway.push()`（出纳收付款单），**没有按
`kingdee.voucher-target` 分流**。

线上实证（流水 id=12 审计轨迹）：

```
IMPORT            SUCCESS  None -> PASSED
AI_VOUCHER_DRAFT  SUCCESS  PENDING -> PENDING      ← AI 建议正常
REVIEW_APPROVE    SUCCESS  PENDING -> APPROVED
PUSH_VOUCHER      FAILED   NOT_PUSHED -> FAILED    ← AR_RECEIVEBILL: 当前组织未启用出纳
WITHDRAW          SUCCESS  APPROVED -> WITHDRAWN
```

`action=PUSH_VOUCHER` 即凭证中心路径（与 AI 制证的 `AI_VOUCHER_*` 区分），坐实分流缺失。

### 修复
`StatementService.pushVoucher` 在 CAS 置 `PROCESSING` 之后按 `kingdeeProps.isGlTarget()` 分流：
GL（默认）→ 委托 `KingdeeVoucherEngineService.pushAiVoucher()`（与 AI 制证 PUSH 同一条组装/校验/
状态机链路，产物是 `GL_VOUCHER` 草稿）；BILL → 保留原出纳单逻辑。
失败语义保持「不抛业务异常、返回当前态」，且 CAS 仅在仍处 `PROCESSING` 时置 FAILED，
因此 GL 链路已写的 `GL_FAILED`（含金蝶原文）不会被覆盖——凭证中心据此能区分
「总账推送失败」与「前置校验未通过（缺分录 / 账户未映射金蝶档案）」。

### 验收
- 新增 `StatementServiceGlPushIntegrationTest`（2 例）：GL 落点走 GL（`GL_PUSHED` + `GL-MOCK-` 凭证号）、
  账户未映射时阻断且 `pushMessage` 带回可执行提示；
- 全量 **390 测试 0 失败**；
- 线上验收（部署后）：对已映射账户的流水在凭证中心点推送 → 金蝶出现 `GL_VOUCHER` 草稿，
  不再出现「未启用出纳」。

### 附带现状（部署后须知）
账户映射已配置：招行 7 个账户全部 `MAPPED`；4 个中信测试账户为 `UNMATCHED`
（账号为测试号，非真实银行账号）——对这些账户的流水推送会被**阻断并提示补映射**，属设计口径。

---

## FIX-010（P2 · 图虫侧规则入库前置清单）

**背景**：图虫侧 19 条流水映射规则（`voucher-rules-toochong-20260921.json`，id 23–41）已结构化，
但按用户 2026-09-21 拍板「**先配齐能力，再入库规则**」，规则暂不入库。以下为入库前必须清掉的前置项，
按依赖顺序排列；能力侧（维度映射表 / 多维度注入 / 槽位）已于本轮完成，见
`docs/kingdee-openapi/toochong-rules-assessment-20260921.md` 第九节。

### 前置 1：匹配算子（技术侧）—— ✅ 已完成（2026-09-21）
- `IN_SUPPLIER_LIST` / `IN_EMPLOYEE_LIST` / `IN_CUSTOMER_MAPPING` 三个算子已实现：
  语义为「来源值在 `kingdee_dimension_mapping` 里有启用行即命中」（组织消歧走同一 `resolveValue`），
  因此**名单月更不必改代码**；未知算子 fail-closed（绝不自动命中）。单测覆盖 4 例。

### 前置 2：「业务线」维度决策（业务侧，阻塞 12 条规则）
- 账套 `BD_FLEXITEMPROPERTY` 的 14 个维度类型里**没有「业务线」**，而图虫规则大量引用。
- 三选一：A 用「项目 `ZDY0002` / `FF100003`」承载 / B 请金蝶新增该维度并补槽位（填进
  `kingdee_dimension_slot` 即可，无需改代码）/ C 不落凭证维度。
- 决策记录位置：`kingdee-dimension-slot-ledger-20260921.md` 第四节。

### 前置 3：R35 社保 8 科目 / R36 公积金 2 科目的金额拆分规则（财务侧）
- 财务未给拆分口径 → **暂按人工填写（MANUAL）**：推送前在确认页逐行补金额，系统不猜。
- 财务补齐后改规则 JSON 的 `share`（`EQUAL` 均分或具体比例），无需改代码。

### 前置 4：附件数据导入（财务侧）
- 附件 1.x（供应商 412 条）/ 附件2（员工 354 行）需整理成「来源值 + 金蝶档案编码」两列，
  在「规则中心 › 核算维度配置 › 值映射」用**批量粘贴导入**（制表符分隔）一次灌入。
- 附件3（客户 mapping）为空 → R41 销售回款暂缓（用户拍板「先别管」）。

### 前置 5：R33/R34 优先级确认（业务侧，**用户未答**）
- 两条同为摘要「往来款」，R34 靠对手方「招行清算款」区分，评估建议 R34 排在 R33 之前。
- 待确认后写入规则表 `priority`。

### 前置 6：主体名核对（技术侧）
- `KingdeeOrgResolver` 已加入图虫系 4 主体（映脉 411 / 浙江北分 421 / 浙江 420 / 图虫 410），
  顺序按「具体者在前」。需核对 FINFLOW `company.name` 实际写法，确认不存在「一名命中两关键词」
  （如「浙江图虫…」）导致误判。

### 验收标准
前置 1~6 清完后，19 条规则一次入库：规则中心可见、`preview` 对图虫系流水命中、
推送产出 GL 凭证草稿（含供应商/部门等多维度），无「配好但推不通」的静默失效。
