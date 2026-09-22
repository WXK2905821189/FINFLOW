# W17 用户反馈 6 条 · 更新方案（2026-09-22）

> **本文件只给方案，未改任何代码。**批次号暂定 W17（若与并行会话冲突，只需改文件名，正文无硬引用）。
> 核实方式：每条都**打开现场**（代码文件 + 行号）后再定性，不采信文档自述。
> 结论口径：**已定案** = 根因有代码级铁证，可直接排期；**待实证** = 差一次真实调用/UI 复现才能定案，不猜。

## 0. 结论速览

| # | 你的描述 | 定性 | 状态 | 影响面 | 建议批次 | 量级 |
|---|---|---|---|---|---|---|
| 1 | 选账号后仍显示全部账号；建议删「全部主体」；余额页加主体树 | **前端逻辑缺陷**（选择分支被公司分支抢走）+ 一处产品判断需澄清 | 已定案 | 流水/余额两页共用同一页 | W17-A | 小（约 0.5 天） |
| 2 | 规则中心能否用、AI 制证是否遵守网页改的规则 | **功能正常**；但「遵守」是**提示词级**而非硬约束 | 已定案 | 规则中心 + AI 制证 | W17-C（增强） | 中（约 1 天） |
| 3 | 筛选功能都不太正常；规则中心「筛选-规则号」不能工作 | **银行数据页：口径错配（铁证）**；规则中心：高置信假设，待 5 分钟复现 | 部分定案 | 全部 Excel 内核表格 | W17-A | 小~中（0.5~1 天） |
| 4 | 操作轨迹要中文；推送成功显示蓝点而非绿点 | **前后端字面量不一致（铁证）** + 缺中文映射层 | 已定案 | 凭证详情 / 追溯抽屉 | W17-B | 小（约 0.5 天） |
| 5 | FINFLOW 的金蝶凭证号与金蝶系统对不上 | **取值字段取错（铁证）**：报文已请求凭证字号却只解析内码字段 | 已定案，**修法需 1 次真实报文实证** | 推送回写 + 凭证中心展示 | W17-B（含实证前置） | 中（1~1.5 天） |
| 6 | 角色权限限定为「能看到什么页面」 | **设计类**：现状已基本一页一权限，但管理界面按技术域平铺 36 项 | 待拍板 | 用户与角色页 + 权限目录 | W17-D | 中（1~2 天） |

**一句话**：6 条里 4 条可直接修（1、3a、4、5），1 条是增强（2），1 条要先拍板（6），另有 1 项需 5 分钟 UI 复现（3b）。

---

## 1. 主体树与账号筛选（反馈 1）

### 现象
流水查询页左侧主体树点了某个**账户**节点，表格仍显示全部账号。

### 实测证据
- 余额与流水**共用同一组件**（`BankDataQueryPage.tsx`，靠 `resource` 区分），后端账户过滤**已实现**：`BankDataQueryService.java:203`（流水）/ `:298`（余额）按 `bankAccountIds` 过滤 —— 所以不是「后端没支持」。
- 树是**受控多选**：`selectedKeys={treeSelectedKeys}`（`:546`），而 `treeSelectedKeys` 由「全局主体范围 + 页内账号筛选」**合并**而成（`:145-148`）：

  ```ts
  const treeSelectedKeys = [
    ...filters.accountIds.map((id) => `account:${id}`),
    ...(canCrossCompany ? scopeCompanyIds.map((id) => `company:${id}`) : []),
  ];
  ```
- 选择分支判定用的是**全部已选 key**，不是「本次点击的节点」（`:155-172`）：

  ```ts
  if (companyKeys.length) {            // ← 只要 keys 里还有公司节点，就永远走这里
    setScopeCompanies(...);
    applyFilter({ accountIds: [] });   // ← 账户筛选被清空
    return;
  }
  if (accountKeys.length) { applyFilter({ accountIds: [accountKeys.at(-1)] }); return; }
  ```

### 根因（两条，必须分开看）
1. **代码缺陷**：全局主体范围非空时，公司节点会一直留在 `selectedKeys` 里 ⇒ 点账户节点时 `keys` 同时含 `company:*` 与 `account:*`，永远命中第一个分支 ⇒ `accountIds` 被清空。表现就是「选了账号还是全部账号」。
2. **产品判断需澄清**（你的假设有偏差）：右上角「全部主体」与账号筛选**不冲突**。二者是**两个维度、AND 关系**：
   - 顶栏（`shell/SubjectScope.tsx`）= **公司主体范围**，写入全站 store（`store/scope.ts`），空数组 = 全部主体；
   - 左侧树账户节点 = **本页账号过滤**（`accountIds`）。
   - 实测：`useSubjectScope` 的消费者**只有银行数据查询页**（全仓检索仅 `BankDataQueryPage.tsx` + `SubjectScope.tsx` 两处），所以删掉顶栏在技术上影响面很小——但它同时是无权限用户「仅本公司数据」提示位、也是跨公司权限（`bankdata:cross-company:view`）的唯一可视入口。

### 方案

**1-A 修选择分支（必做，改法确定）**
用 `onSelect` 的第二参数 `info.node.key` 判定「本次点的是哪种节点」，而不是用全部 `selectedKeys`：
- 点账户 → 只改 `accountIds`（单选，点同账户 = 取消），**不动**主体范围；
- 点公司 → 只改主体范围 + 清空 `accountIds`；
- 点空白/取消 → 清空 `accountIds`，主体范围不变。

**1-B 余额页加同一棵树（建议做）**
现在树被两处 `isStatement` 守卫挡在余额页之外（`:459` 工具栏开关、`:528` 树容器）。放开为两页都显示即可——数据源（公司→账户）与两个 tab 通用，`showGroupSwitch` 余额侧本就已支持「按主体分组」。

**1-C 「全部主体」的去留（需你拍板，二选一）**

| 选项 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **留（推荐）** | 顶栏保留为**全站主体范围唯一入口**；左侧树顶那行「（范围：全部主体）」改成**可点按钮**，点开复用顶栏同一个下拉。树只负责账户筛选 | 不改全站设计；权限入口不丢；两个 tab 行为一致 | 页内仍有两个控件，靠文案/联动消除歧义 |
| **删** | 顶栏在银行模块内不再渲染，主体范围收进左侧树（点公司节点即改范围） | 一个地方控制，最符合你的直觉 | 全站顶栏少一个能力位；将来别的模块要用主体范围得重做；需同时改 `Shell.tsx` |

> 我的建议：**先按「留」实现 1-A/1-B**（这两条能直接消掉你遇到的问题），「删」作为独立小项等你确认——因为问题 1 的根因是**分支抢占**，不是「两个入口」本身。

### 验收
1. 流水页点某账户 → 表格只剩该账户行，公司节点选中态不误导；再点同账户 → 恢复全部（本页）。
2. 流水页点公司节点 → 主体范围变化，账号筛选被清空（口径可见）。
3. 余额页出现同一棵树，行为与流水页一致。
4. 无跨公司权限用户：树仍显示本公司公司节点+账户（现状不变），顶栏仍是「仅本公司数据」。

---

## 2. 规则中心与 AI 是否遵守规则（反馈 2）

### 可直接答复你的结论
1. **规则中心可正常使用**：`CategoryRulesPage` 走 `ExcelGrid`，配 `POST/PUT/DELETE /kingdee/voucher-rules`，创建/更新有 ruleNo 重复校验（409，`KingdeeVoucherRuleService.java:166-195`）并写审计日志。分组、拖拽换组、Excel 导入（两步确认）都在。
2. **网页改完立即生效，无需重启**：取数入口 `KingdeeVoucherRuleService.listEnabled()`（`:59-64`）**每次直接查库**，全类无缓存（该类只有 `pushLocks` 一个 `ConcurrentHashMap`，与规则数据无关）。规则中心的展示列表同理实时。
3. **AI 制证确实「读」规则**，而且是当作强约束注入的：`AccountingSuggestionService.hitRules()`（`:152-168`）先跑规则引擎预检 → `buildUserPrompt()`（`:197-208`）把命中规则拼进提示词：

   > 「本笔流水命中的企业入账规则（服务端规则引擎判定，**分录必须采用**）…请以上述规则的科目名与方向为准输出 entries，并在 rationale 开头注明命中规则号。」

### 但要注意一个**真实落差**（这是你会踩的点）
「提示词里写了必须采用」≠「模型一定照做」。当前是**软约束**：
- 走 **AI 制证**（`/api/bank-data/ai-voucher`）→ 规则只进提示词，模型仍可能换科目、改摘要、拆错金额；
- 走 **规则制证**（按 `ruleNo` 确定性生成分录，`KingdeeVoucherEngineService`）→ 才是**硬约束**，与模型无关；
- 规则引擎异常会**静默降级为「空命中」**（`:164-167` catch 后返回空列表）⇒ 表现是「AI 没理我的规则」，但日志里只有一条 WARN。

### 方案（三选，建议 A+C）
- **2-A 服务端硬校验（推荐，成本最低）**：AI 生成分录后，若该笔流水**命中规则**且 AI 分录的科目集合与规则不一致 ⇒ 直接以规则分录**替换** AI 分录（或标记「与规则不符」并强制人工复核，由你定口径）。位置：`AccountingSuggestionService` 组装建议处。
- **2-B 规则优先开关**：规则中心加「强制模式」——命中规则直接走规则引擎生成分录，AI 只补摘要。最彻底，但改变了 AI 制证的定位，需产品拍板。
- **2-C 可观测性（建议一起做）**：规则预检降级不再静默 —— 写入 `review_comment` / 推送消息，AI 建议里带「命中规则 R44」字段，凭证详情页可见。

### 验收
- 命中规则的流水：AI 分录科目与规则一致（或明确标红「与规则不符」），`rationale` 含规则号；
- 规则预检降级时，界面能看到「本次未应用规则（原因）」而不是静默；
- 改一条规则科目 → 下一笔命中流转 AI 制证即看到新科目（验证「无缓存、即时生效」）。

---

## 3. 筛选异常（反馈 3）

分两块，**银行数据页已定案，规则中心待 5 分钟复现**。

### 3-A 银行数据页：勾选值 = 完全没筛（已定案，铁证）

三个事实拼在一起就是 bug：
1. 列声明的可筛方式决定筛选面板**默认模式**：`filter:'text'` ⇒ 面板有「按值勾选 / 文本包含」两个页签，**默认落在「按值勾选」**（`kernel.ts:798-803`：`kinds=['values','text']`，`kind = kinds[0]`）。
2. 服务端化列在本地被**跳过过滤**（`kernel.ts:328-331`：`filterServer` 列不进本地 `filtered()`）。
3. 页面只认「文本包含」模式的参数（`BankDataQueryPage.tsx:203-232`）：

   ```ts
   const textOf = (key) => (f.kind === 'text' ? f.q.trim() : '');          // ← values 模式一律 '' 
   const singleValueOf = (key) => (f.kind === 'values' && f.set.length===1 ? f.set[0] : '');  // 只给 loanCode 用
   ```

⇒ 对 `filter:'text' + filterServer:true` 的列（账号 `account`、收付方 `counterparty`、流水号 `statementNo`、本方账户 `accountLabel`，见 `BankQueryGridColumns.ts:106/240/246/279`），用户**在默认页签里勾选值** → 参数是空串 → **服务端不筛**，而本地又因 `filterServer` 被跳过 → **表格一动不动**。这就是「筛选功能好像都不太正常」。

> 对照：金额列（`filter:'num'` 唯一页签）与借贷列（单选值）是好的 —— 所以现象是「有的列好、有的列完全没反应」，与你描述一致。

**修法（两端都要，缺一不可）**
- 前端：`filterServer` 列把「按值勾选」也映射成服务端参数 —— 多选值 → 需要一个「IN / 或」语义参数；或**把默认页签改成「文本包含」**（最小改动、语义最清晰）。账号列已经是「账号后缀」语义（后端 `likeLeft`），文案与语义一致。
- 后端：若要支持多值勾选，`BankDataExtraFilter` 需新增相应 IN 类参数（否则前端发了也没用）。
- **建议先做「默认页签 + 显式提示」这一版**（0.5 天，不动后端），把多值勾选的服务端化作为后续项（现在账号列的 `likeLeft` 后缀语义本来就不适合多值）。

### 3-B 规则中心「筛选-规则号」：高置信假设，待 5 分钟复现

事实：
- `CategoryRulesGridColumns.ts:42`：`{ k:'ruleNo', t:'规则号', type:'text', filter:'num' }` ⇒ 面板只有「数值区间」一种形态（最小值 ~ 最大值）。
- 内核 num 实现本身是对的（`kernel.ts:342-348`：`Number(v)` 与 min/max 比较）。

**假设（最可能）**：规则号是**唯一编号**，但筛选用的是**区间**语义。你输入「44」到**左框（最小值）** ⇒ 显示 44 及以上（可能 0 行）；输入到**右框（最大值）** ⇒ 显示 1~44 全部 ⇒ 无论哪种，你的直觉（「输入 44 应该只剩 44」）都对不上，观感就是「筛了跟没筛一样」。

**复现配方（5 分钟，本地起前端）**：规则中心 → 点「规则号」列头漏斗 → ①只填最小值 44 应用；②只填最大值 44 应用；③两个都填 44 应用。若 ③ 命中 1 行而 ①② 结果异常 ⇒ 假设成立，这是**语义问题不是内核 bug**。

**修法**：把 `ruleNo` 列改为「精确匹配 / 逗号分隔多值」（如 `44,47,48`）语义；内核加一个 `filter:'exact'`（或直接 `filter:'text'` + 值集模式），顺带覆盖 `priority` 这类「唯一数值」列。若复现发现是真·bug（0 行、报错），按 3-A 同口径修内核。

### 验收
- 银行数据页：给「账号」列在**默认页签**输入/勾选后，行数变化与「服务端全量口径」提示一致（翻页/导出同口径）。
- 规则中心：规则号输入 `44` ⇒ 恰好 1 行；输入 `44,47,48` ⇒ 3 行。

---

## 4. 操作轨迹中文 + 状态点颜色（反馈 4）

### 根因（铁证，两处独立问题）

**4-1 蓝点**：后端写 `SUCCEEDED`，前端只认 `SUCCESS`。
- 后端：`KingdeeVoucherEngineService.java:358-359`

  ```java
  event.setAction("GL_VOUCHER_PUSH");
  event.setResult("GL_FAILED".equals(status) ? "FAILED" : "SUCCEEDED");   // ← SUCCEEDED
  ```
- 前端两个渲染点都只认 `SUCCESS`，落 else ⇒ 蓝点：
  - `VoucherDocPage.tsx:55-56`：`result === 'SUCCESS' ? 'green' : result === 'FAILED' ? 'red' : 'blue'`
  - `statements/pages.tsx:51`（`AuditDrawer`）同样只认 `'SUCCESS'`。
- **这是全系统唯一一处**异类：其余 11 个写审计的位置（`StatementService.java:255/352/446/482/559/572/601`、`BankDataAccountingService.java:271/336/404/424/448/507/580`）都写 `SUCCESS`。

**4-2 英文**：轨迹直接渲染后端常量，没有任何映射：
- `event.action` = `IMPORT` / `REVIEW_APPROVE` / `PUSH_VOUCHER` / `GL_VOUCHER_PUSH` / `AI_VOUCHER_DRAFT` / `STATEMENT_REVIVE` / `WITHDRAW` / `REOPEN` / `AI_SUGGESTION_REFRESH` / `VOUCHER_DRAFT_EDIT`；
- `previousStatus → currentStatus` = `PENDING` / `APPROVED` / `REJECTED` / `WITHDRAWN` / `NOT_PUSHED` / `GL_PUSHED` / `GL_FAILED` …（原文直出）。

### 方案（两步，顺带消掉一类隐患）
- **4-A 统一字面量**：把 `:359` 改成 `SUCCESS`（新数据不再产生异类值）；**前端兼容历史值**（库里的旧事件不会变）：颜色判定接受 `SUCCESS || SUCCEEDED`。加一条契约测试钉住「审计 result 只有 SUCCESS/FAILED」。
- **4-B 中文映射层**（延用现成设施）：仓库已有 `frontend/src/modules/shared/dict.ts`（`dictText` + `STATUS_TAG_TEXT`，原则是「未收录兜底显示原文」）。新增三张表并**按域隔离**：
  - `AUDIT_ACTION_TEXT`（上列 10 个动作 → 中文，如 `GL_VOUCHER_PUSH` → 「推送金蝶（总账）」）；
  - `REVIEW_STATUS_TEXT`（复核域）；
  - `PUSH_STATUS_TEXT`（推送域）。
  > **必须分域**：`validation_status` / `push_status` / `review_status` / `bank_data_*` 投影层是**同名不同域**（FINFLOW 已知坑），一张大表会把 `FAILED` 之类的同名字面量混译。
- 两个渲染点（`VoucherDocPage.tsx:242-252`、`statements/pages.tsx:51`）统一走同一 helper，避免以后又分叉。

### 验收
- 新推送一条流水 → 凭证详情「操作轨迹」显示绿点 + 「推送金蝶（总账）· 待审核 → 已推送（GL）」；
- 历史事件（`SUCCEEDED`）也显示绿点；
- 未收录的新枚举值仍显示原文，不被吞。

---

## 5. 金蝶凭证号对不上（反馈 5）

### 已确认的事实（代码级，三条）
1. 报文**没有**自己编号，但**请求了**返回凭证字号（`KingdeeGlVoucherPayloadBuilder.java:84-85`）：

   ```java
   ArrayNode needReturn = root.putArray("NeedReturnFields");
   needReturn.add("FBillNo").add("FVOUCHERGROUPNO");   // 凭证号 + 凭证字号
   ```
2. 解析时**只取了 `Result.Number`**，`NeedReturnData` / `SuccessEntitys` **完全没读**（`RealKingdeeVoucherGateway.java:448-464`）：

   ```java
   String number = result.path("Number").asText(null);
   return new KingdeeVoucherResult(number, "PUSHED", "Saved to " + formId + ...);
   ```
3. 官方文档里 Save 的响应信封**只有** `Result.ResponseStatus`（含 `SuccessEntitys[{Id, Number}]`）+ `Result.NeedReturnData`，**没有**顶层 `Result.Number`（`docs/kingdee-openapi/openapi-docs/OpenAPI接口文档汇总-20260904.md:225-261`）。

**旁证（两处独立记录，指向「存的是内码」）**：
- 实测注释把「凭证 16043」当作凭证标识（`KingdeeGlVoucherPayloadBuilder.java:44-45`）；
- W16b 端到端验收的金蝶只读回查结果是「流水 17 → 凭证 **16113**」「流水 18 → 凭证 **16121**」（`.workbuddy/memory/2026-09-22.md`，同 `docs/deploy-w16b-report-20260922.md`）。
  两者都是 5 位**内码**形态，而金蝶界面显示的是「凭证字-序号」（如「记-44」）。

⇒ **结论（高置信，待一次实证封口）**：FINFLOW 落库/展示的 `voucher_no` 来自金蝶**内码**口径，而你在金蝶界面看到的是**凭证字号**（截图里的 44 / 47 / 48）⇒ 必然「对不上」。这不是数据传错，是**取值字段取错**。

### 前置实证（P0，必须做，不许猜）
一次真实调用即可定案，二选一：
- **抓报文**：对一张测试凭证执行推送，落一份 Save 的**完整响应 JSON**（现网日志/直连调试）；
- **比三处**：同一张凭证交叉核对 —— ①FINFLOW 界面显示的凭证号 ②RDS `statement_record.voucher_no` ③金蝶界面「记-XX」。
  只读 SQL：`SELECT id, statement_no, push_status, voucher_no, push_message, pushed_at FROM statement_record WHERE id = <ID>;`

### 方案（无论实证落在哪个分支都成立）
**5-A 取回金蝶同款标识**：`parseResponse` 同时解析
- `Result.ResponseStatus.SuccessEntitys[0].Number`（单据编号）
- `Result.NeedReturnData`（已请求 `FBillNo` / `FVOUCHERGROUPNO`）
- `Result.Id`（内码，用于回查/对账）
落库策略：`voucher_no` 存**金蝶界面对应的凭证字号**（如 `记-44`），并**新增列**保存内码（`voucher_inner_id`）与凭证字（`voucher_group_no`），展示层优先显示「凭证字号」。
> 好处：无论实证结果是「`Result.Number` = 内码」还是「= FBillNo」，界面都能与金蝶对齐；对账/回查仍可用内码。

**5-B 迁移与兼容**（若加列）：
- 新增迁移 N ⇒ **同步 `ci.yml` 6 处**（`expected_versions` / 文件断言 / `Migrating "N"` / applied / validated×2），`expected_versions` 逐项字符串比对、漏列必红；
- 线上历史数据的 `voucher_no` 是旧口径 ⇒ 保留原值不动（不猜测回填），界面加「（旧口径，内码）」提示或提供一次性回填脚本（需你在确认口径后拍板）；
- 新增列需评估 `VoucherGroupService` 的列投影与 `assertNotAlreadyPushed` 的守卫文案（`KingdeeVoucherEngineService.java:263` 引用 `getVoucherNo()`，文案要跟着改）。

### 验收
- 推一张新凭证：FINFLOW 显示的字号与金蝶界面**逐字符一致**（含凭证字）；`voucher_inner_id` = 金蝶内码；
- 凭证中心列表、详情、审计事件三处显示的凭证标识一致；
- 历史推送行的展示不出现「假的正确」（旧值明确标注口径）。

---

## 6. 角色权限收敛为「能看到什么页面」（反馈 6）

### 现状（已核实）
- **36 项权限**，命名 `模块:动作`（`docs/permission-catalog.md:18-47`）。
- 菜单可见性其实**已经基本是「一页一权限」**（`productNavigation.tsx:52-100`）：`bankdata:balance:view` / `bankdata:statement:view` → 余额·流水；`bank:view` → 银行账户；`operation:log:view` → 运行日志；`bankdata:raw:view` → 原始报文；`voucher:push` → 规则中心 + 凭证中心；`user:manage` / `audit:view` / `system:dict:manage` / `ai:use` / `ai:config` / `closing:view` / `feishu:view` / `dashboard:view` 各对应一页。
- 问题出在**管理界面呈现**：`UsersPage.tsx:283-290` 按「编码冒号前的技术域」分组（bank / bankdata / statement / voucher / ai / feishu …），`:463-465` **只显示原始编码**（`<span class="mono">{permission.code}</span>`），没有中文名、没有「这页长什么样」。
- 另有**历史包袱**：`reconciliation:view`、`statement:import`、`statement:review`、`data:query`、`validation:view/manage`、`bankdata:receipt:view`、`bankdata:payroll:view` 等对应的页面在导航里**已经没有了**（现导航 16 页），它们现在只当服务端闸门用 ⇒ 管理界面上一堆「看不出用途」的勾选项。

### 方案（A 推荐，B 备选）

**6-A 两段式权限模型（推荐，不动后端语义）**
- **页面级（新增，决定可见性）**：按现有 16 个菜单页 + 1 个详情页建 `page:<页面>:view` 一级；菜单与路由守卫只认它。
- **操作级（保留）**：`voucher:push`、`ai:config`、`bankdata:raw:view`、`bankdata:cross-company:view`、`bankdata:sync:trigger`、`*:manage` 等继续作服务端闸门（**不能删**——后端 `@PreAuthorize` 依赖它们，删了不是越权就是功能不可用）。
- **管理界面改造（关键收益点）**：角色权限编辑器按**页面卡片**呈现 —— 每张卡 = 一个页面（中文名 + 路由），卡内上半区是「能否看到本页」，下半区是「本页内的操作权限」，并列出该页依赖的权限；把已下线页面的遗留权限折叠进「历史 / 仅服务端」区，不再与在用项混排。
- 「数据范围」与「页面可见」**分开表达**：跨主体范围仍归 `bankdata:cross-company:view`（数据维度），不要塞进页面权限。

**6-B 激进收敛（不推荐）**
删掉操作级权限，只留页面级。代价明确：`voucher:push` 是 AI 制证的终局闸门、`bankdata:raw:view` 是唯一返回完整响应体的权限、`ai:config` 是超管专属 —— 降成页面级后**所有能打开页面的人都能推送/看全量报文**，属于权限放大。

### 验收
- 角色编辑界面：新增角色只勾「页面」即可控制菜单可见性与路由准入；勾选 `voucher:push` 这类闸门时，界面明确提示「同时影响：AI 制证、推送金蝶」；
- 权限目录文档补一列「所属页面 / 是否决定菜单可见」；
- 无权限用户直接输 URL 仍被路由守卫挡住（回归既有行为，不能因改造放松）。

---

## 7. 批次拆分建议

| 批次 | 内容 | 交付物 | 完成标志 | 牵头 |
|---|---|---|---|---|
| **W17-A** | 反馈 1（树分支 + 余额页加树）、反馈 3（筛选口径 + 规则号语义） | 前端改动 + 内核小改（如需） | 上述验收 1/3 全过；两 tab 交互一致 | 技术 |
| **W17-B** | 反馈 4（字面量 + 中文映射）、反馈 5（凭证字号） | 后端解析 + 前端映射 + 可能的迁移 | 推送成功绿点、轨迹全中文；凭证字号与金蝶一致 | 技术（5 需 1 次真实调用） |
| **W17-C** | 反馈 2（规则硬校验 + 可观测性） | 后端校验逻辑 + 界面「命中规则」标识 | 命中规则的流水分录与规则一致或显式标红 | 技术 + 口径确认 |
| **W17-D** | 反馈 6（权限两段式 + 管理界面） | 权限目录 + `UsersPage` 分组改造 | 只勾页面即可控菜单；闸门权限有影响提示 | 产品 + 技术 |
| **W17-E** | 3-B 复现后的小修 + `docs/permission-catalog.md` 补列 | 文档 + 小修 | 3-B 定案；权限目录可读 | 技术 |

**建议本轮先做 A + B**（4/6 条即时可见的体感修复），C/D 排在下一批——C 涉及「AI 与规则谁是权威」的口径，D 涉及权限模型，都值得单独一轮。

## 8. 需要你拍板的 4 件事

1. **1-C**：右上角「全部主体」**留**（推荐：保留 + 页内加同款入口）还是**删**（主体范围收进左侧树）？
2. **2-A/2-B**：AI 制证命中规则时，是「**偷偷替换成规则分录**」（推荐 A）还是「**保留 AI 分录但标红要求人工复核**」？或「命中即走规则引擎、AI 只写摘要」（B）？
3. **3-A**：`filterServer` 文本列先做「**默认页签改文本包含**」（0.5 天，推荐），还是**同时**支持多值勾选（需后端加 IN 参数）？
4. **5**：凭证中心展示口径 = **「记-44」式凭证字号**（推荐）？历史行是否要回填/标注旧口径？

## 9. 需要一次实证 / 复现的 2 件事

| # | 事项 | 做法 | 定案什么 |
|---|---|---|---|
| 1 | 金蝶 Save 响应真实形态 | 推一张测试凭证，留完整响应 JSON + 三处交叉核对（FINFLOW / RDS `voucher_no` / 金蝶「记-XX」） | `Result.Number` 到底是内码还是凭证号；`NeedReturnData` 里有无 `FBillNo` |
| 2 | 规则中心「规则号」筛选 | 本地起前端，按第 3-B 节三级配方各点一次 | 是「区间语义 vs 唯一编号」的观感问题，还是真·bug |
