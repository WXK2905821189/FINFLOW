# FINFLOW 待修清单

> **维护约定**
> - 一条 FIX = 一个缺陷，**编号只增不复用**；登记四段：现象 → 根因（实测，非推测）→ 方向 → 验收。
> - **闭环即摘出**：正文只留**在办项**；已闭环者压缩成一行移入文末「已闭环归档」表（保留编号，便于外部引用）。
> - 只在待修清单里存在的**运维知识**，条目闭环前先下沉到 `docs/module-notes.md`，避免随条目删除而丢失。
> - 相关在册文档：`docs/kingdee-risk-predictions-20260922.md`（金蝶风险前瞻：P0×5 待拍板 / P1×5 已实施 / P2×3 观察）、`docs/issue-diagnosis-*.md`（只读诊断报告）。

**在办 4 条**：FIX-003（P2）· FIX-005（P3）· FIX-010（P2）· FIX-011（P2）

---

## FIX-003（P2 · 报文证据链与接口补全，非阻塞）2026-09-04 登记 · 未实施

来源：招行云直联三份接口文档（NTQADINF 余额 / trsQryByBreakPoint 流水 / NTQABINF 历史余额）与线上 raw 数据对照。
（NTQADINF 漏解析的 stscod/opndat/inttyp/dpstxt 四字段已随 V21 补齐，不在本项。）

### P2-4 · raw-message 存的不是银行原始响应
- **现象**：`BankRawMessage.payload` 存的是入库后投影 `BankDataCollection.toJson()`（约 300B），不是银行解密后的原始 body（KB 级）。「原始报文」抽屉展示的是「我们保留了哪些字段」，而非「银行真的回答了什么」；适配器丢字段（如 V21 前的 stscod）raw 里同步丢，违背该模块自身定位。
- **方向**：DB 加列 `raw_response_body`（或 payload 双视图：原始 + 入库后），适配器解密后先落原始 body 再投影。需评估体积增长、保留策略联动、敏感字段脱敏范围（现 sanitize 仅在日志层）。涉及迁移 + 双写 + 隐私评估，故排 P2。
- **验收**：抽查任一真实流水，「原始报文」= 银行响应原文，含未解析字段。

### P2-5 · NTQABINF 历史余额接口未实现
- **现象**：余额页只有当下快照（NTQADINF），查不到历史某日余额；对账若需 T-1/T-N 余额只能靠流水倒推。
- **方向**：非 v0.2 必需（测试用户申请表未开通）。M2/M3 对账若需历史余额锚点，再按文档实现（接口简单：单账户 + 日期 → 一行余额）。
- **验收**：指定账户 + 日期返回该日余额。

---

## FIX-005（P3 · 金蝶凭据多账号化，架构预留，非阻塞）2026-09-16 登记 · 未实施（等触发）

触发：用户提出「未来多账号后，不同人登录用的金蝶云凭证不同」。经鉴权模型澄清后定调为**公司级凭据 + 用户名映射**；当前单账套阶段不实现。

- **鉴权模型结论（设计依据）**：AppID/AppSec 属应用级 → 按**公司/账套**分（不同账套 = 不同 AcctID），不按人分；授权用户名决定金蝶侧操作身份（权限 + 审计）→ **按人映射**；账簿/组织已按 companyId 解析。
- **现状**：`KingdeeSdkClient` 用全局 `KingdeeProperties`（env）构建唯一 `IdentifyInfo`，全系统单身份。接缝已就绪：`pushVoucher(id, operatorId)` 自带 companyId + operatorId，网关 `push(statement)` 接口**无需改动**。
- **方案 A**（已对比否决「纯用户级 B」/「维持现状 C」）：新增 `kingdee_provider_config` 表按公司存 AcctID/AppID/AppSec（AES-256-GCM 加密落库，复用 V29 飞书向导模式），查不到回落 env 默认；新增「FINFLOW 用户 → 金蝶用户名」映射表；`pingKingdee` 同步按公司探测。工作量 2~3 天。
- **触发时机**：第二家真实账套接入；或财务要求金蝶侧审计区分到人（此时仅做映射表，半天）。
- **验收**：不同 companyId 走各自账套（凭据加密落库、回显仅尾 4 位 hint）；未配置的公司回落 env 不中断；`pingKingdee` 按公司返回状态。

---

## FIX-010（P2 · 图虫侧 19 条规则入库前置清单）2026-09-21 登记 · 前置 4 项未清

背景：图虫 19 条流水映射规则已结构化（`docs/kingdee-openapi/voucher-rules-toochong-20260921.json`，id 23–41），按用户拍板「**先配齐能力，再入库规则**」暂不入库。能力侧（匹配算子 / 多维度注入 / 槽位 / 配置界面）本轮已完成，见 `docs/kingdee-openapi/toochong-rules-assessment-20260921.md` 第九节。

| # | 前置项 | 归属 | 状态 |
|---|---|---|---|
| 1 | 匹配算子 `IN_SUPPLIER_LIST` / `IN_EMPLOYEE_LIST` / `IN_CUSTOMER_MAPPING` | 技术 | **已完成**：语义 = 来源值在 `kingdee_dimension_mapping` 有启用行即命中 ⇒ **名单月更不改码**；未知算子 fail-closed（绝不自动命中）；单测 4 例 |
| 2 | 「业务线」维度决策 | 业务 | **已决策**：账套 14 个维度类型里无「业务线」，用**项目 `ZDY0002` / `FF100003`** 承载；V42 seed 已写入。金蝶将来新增独立维度时，改「槽位配置」一行即可，不改码 |
| 3 | R35 社保 8 科目 / R36 公积金 2 科目的金额拆分规则 | 财务 | **未给口径** ⇒ 暂按 MANUAL（推送前逐行补金额，系统不猜）；财务补齐后改规则 JSON 的 `share`，不改码 |
| 4 | 附件数据导入（附件1 供应商 412 条 / 附件2 员工 354 行） | 财务 | 待整理成「来源值 + 金蝶档案编码」两列 → 规则中心 › 值映射 › 批量粘贴导入。附件3（客户 mapping）为空 ⇒ R41 销售回款暂缓 |
| 5 | R33 / R34 优先级确认 | 业务 | **用户未答**：两条同为摘要「往来款」，R34 靠对手方「招行清算款」区分，评估建议 R34 排 R33 之前；确认后写入规则 `priority` |
| 6 | 主体名核对（防「一名两命中」） | 技术 | `KingdeeOrgResolver` 已含图虫系 4 主体（映脉 411 / 浙江北分 421 / 浙江 420 / 图虫 410，顺序「具体者在前」）；**须核对用户库实际 `company.name`**，确认不存在如「浙江图虫…」同时命中两关键词（本地 seed 只有 1 家公司，无法自验） |

- **伴生遗留**：业务线（项目）的**档案编码清单**仍需财务整理后批量导入值映射表。
- **验收**：前置 1~6 清完 → 19 条规则一次入库：规则中心可见、`preview` 命中图虫系流水、推送产出 GL 草稿（含供应商/部门等多维度），无「配好但推不通」的静默失效。

---

## FIX-011（P2 · GL_PUSHED 流水无法重推，暂以手工修法兜底）2026-09-22 登记 · 未实施

来源：`docs/kingdee-risk-predictions-20260922.md` P1-4。本期按「P1 前置防护」最小改动原则只做**拦截**（防重复推送 P1-5）+ 本登记；「受控强制重开端点」留下轮排期。

- **场景**：财务在金蝶侧发现凭证录错 → 删除 GL 草稿凭证 → 回 FINFLOW 想重推，但流水 `push_status` 已是 `GL_PUSHED` → 被推送守卫 409 拒绝 → **死锁**（FINFLOW 认为已推、金蝶实际已无此凭证）。
- **现状守卫**：`KingdeeVoucherEngineService.assertNotAlreadyPushed`（GL_PUSHED / PUSHED 直接 409）；`StatementService.withdraw`（`PUSH_COMPLETED` 不可撤回，409「请在金蝶侧处理」）。
- **手工修法（当前唯一可行路径，管理员执行）**：
  1. 金蝶侧确认凭证已删除；
  2. 直连 RDS 只读复核：`SELECT id, statement_no, review_status, push_status, voucher_no, push_message FROM statement_record WHERE id = <流水ID>;`
  3. 重开（写操作，须在确认无在途推送后执行）：

     ```sql
     UPDATE statement_record
     SET push_status = 'FAILED',
         push_message = CONCAT('人工重开（金蝶凭证已删除）: ', IFNULL(voucher_no, ''), '，操作人: <姓名>'),
         voucher_no = NULL,
         pushed_at = NULL
     WHERE id = <流水ID> AND push_status = 'GL_PUSHED';
     ```

     - **必须带 `AND push_status='GL_PUSHED'`** 并核对影响行数 = 1（假成功纪律）；
     - 置 `FAILED` 而非 `NOT_PUSHED`：FAILED 可走既有「重试推送」能力，同时 `push_message` 留痕；
     - 人工改库不走应用审计 ⇒ 在工单 / 部署日志记录操作人与原因。
  4. 回 FINFLOW 凭证中心正常重推。
- **完整方向**：受控强制重开端点 —— 新增 `voucher:force-reopen` 权限（V43 迁移 + 权限目录同步）+ 强制填原因 + 审计事件。未做原因：本期无迁移配额；复用 `ai:config`（仅超管）作守卫属语义滥用。
- **验收**：持权管理员对 GL_PUSHED 流水执行「强制重开」→ 流水回 FAILED 态 + 审计记录原因与操作人；非持权 403；重推后新凭证号正常回写。

---

## 已闭环归档（正文已摘出，仅留索引）

| 编号 | P | 一句话 | 结论 / 证据 | 闭环 |
|---|---|---|---|---|
| FIX-001 | P0 | 线上白屏（`manualChunks` 致跨 chunk React 为 undefined） | 移除全部手工分桶（`c268f38`）+ 浏览器实测首屏；确立红线「构建绿 ≠ 运行绿」 | 09-02 |
| FIX-002 | P2 | CI grep 断言易碎 / 覆盖率盲区 / 主 chunk 偏大 | P2-1 白名单显式化加固；P2-2 补 `ConnectionOperationsServiceTest`（并抓出 `logs()` NPE 真缺陷）；P2-3 页面级 lazy 795.70 → 578 kB（警告线调 600） | 09-04 |
| FIX-004 | P3 | `SYNC_COMPLETED` 文案「without external network calls」误导 | 改中性文案 + 注释溯源，零波及，随构建生效 | 09-07 |
| FIX-006 | P1 | 金蝶自动建档未审核 ⇒ 报「往来单位必填」 | 补 `Submit` + `Audit` + 回查（`ensureBaseDataAudited`，不带病推单）+ 21 例测试；真实账套档案 `FDocumentStatus` A → C 验收通过 | 09-21 |
| FIX-007 | P1 | 收付款单路线真实账套不可用（境内主体未启用出纳） | 拍板 B：落点切 `GL_VOUCHER`（`kingdee.voucher-target` 默认 GL，BILL 保留可一键切回）；账户级维度映射（V41）已落地。**残留**：金蝶若启用出纳，需决定是否切回 BILL（业务侧，非阻塞） | 09-21 |
| FIX-008 | P1 | 跨公司流水 AI 建议被误拒 ⇒ 降级「AI 建议不可用」 | `AccountingSuggestionService.requireInCompanyScope` 与 `refreshAiSuggestion` 口径对齐（持 `bankdata:cross-company:view` 放行）；W12 上线 | 09-21 |
| FIX-009 | P1 | 凭证中心推送未按落点分流 ⇒ 仍报「未启用出纳」 | `StatementService.pushVoucher` 按 `isGlTarget()` 分流至 GL 链路；W12 上线，W15 线上审计已见 `GL_VOUCHER` 类报错（分流生效） | 09-21 |

**未编号开放项（另有台账，此处仅索引）**
- 金蝶风险前瞻 **P0×5 待拍板**：目录 TTL 过期致维度静默不注入 / 规则 FIXED 维度自由文本 / 同优先级双命中择优口径 / 值映射与槽位配置误改无防护 / 规则 JSON 配置错账 → `docs/kingdee-risk-predictions-20260922.md`
- W15 遗留：科目目录 `exists()` 不分组织、可能误放行 → `docs/issue-diagnosis-20260922.md`
