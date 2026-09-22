# 金蝶模块风险前瞻与处置记录（2026-09-22）

> 来源：用户指令「预测一下，金蝶模块这边可能会出现哪些问题？我们看看能不能前置解决」。
> 本文为活文档：处置状态随实施回填。P0 项需用户拍板处置口径，不在本批实施范围。
> 本批实施范围 = P1×5 全部 + P2 落盘登记；代码改动文件清单见文末。

## 口径
- **P0**：可能产生错误财务数据（记错账/重复凭证）或阻塞主链路 —— 需拍板后实施；
- **P1**：前置防护可拦下的问题 —— **本批已全部实施**；
- **P2**：观察项，不产生错误数据，按观察口径触发处置。

---

## 总览

| ID | 风险 | 状态 | 本批后防护覆盖 |
|---|---|---|---|
| P0-1 | 目录 TTL 过期 + 重拉失败 → 银行账号维度静默不注入 | 待拍板 | 已缓解：WARN 留痕 + 构建报文时预热重拉，盲区消除 |
| P0-2 | 规则 1/14 FIXED 维度是自由文本，注入槽位后值非法 | 待拍板 | 无（待口径） |
| P0-3 | 同流水多规则同优先级双命中，择优口径未定 | 待拍板 | 无（本批未动匹配代码） |
| P0-3b | 值映射/槽位配置界面误改 → 全链路制证失败 | 待拍板 | 无（恢复路径待定） |
| P0-4 | 规则 JSON 配置错误 → 错账 | 待拍板 | 无（preview 确认页是唯一防线） |
| P1-1 | 必录维度缺失只在金蝶报错，本地静默 | 已实施 | builder 前置 400 + 处置指引 |
| P1-2 | 单维度与 extraDimensions 撞槽位静默覆盖 | 已实施 | builder 400 拒绝（不同槽位共存口径不变） |
| P1-3 | 维度映射导入的档案未审核，首推才暴露 | 已实施 | 导入后回查 WARN 预警（非阻断） |
| P1-4 | GL_PUSHED 死锁（金蝶删凭证后无法重推） | 已登记 | FIX-011 手工修法；完整端点留下轮 |
| P1-5 | 同流水并发推送 → 金蝶重复草稿凭证 | 已实施 | JVM 锁 + 锁内重读 409（多实例需换 DB 守卫） |
| P2-1 | 目录 TTL 过期伴生现象 | 观察中 | 并入 P0-1 拍板后处置 |
| P2-2 | 同报文多行未就绪维度需多轮试错 | 观察中 | V42 多维度是新能力，实际用法待观察 |
| P2-3 | 科目目录查询超时（REAL 网关长 FilterString） | 观察中 | 只记日志；触发处置 = 节流目录查询 |

---

## P0-1 · 目录 TTL 过期 → 维度静默不注入（待拍板）

### 机制
目录缓存 TTL 默认 600s；过期后重拉失败 → `requiresBankDimension` 返回 false → 维度不注入（fail-open）。
制证（09:47）与推送（09:49-50）之间目录过期，是 W15 未决诊断（金蝶报「必录维度未录入」但静态判定全成立）的
**新嫌疑路径**。

### 本批已缓解
- `requiresBankDimension` 目录空时打 WARN（此前完全静默）；
- `buildPayload` 构建报文前触发 `catalog()` 预热重拉（规则路径上 builder 是目录首个调用点，
  不预热则 `isCatalogAvailable()` 永远按「未加载」处理，P1-1 校验会被静默跳过——本批实现中发现并修正）。
- **盲区已消除**：下次发生时服务端日志可直接定位。

### 待拍板
降级终态口径二选一：
- **A（现状）fail-open + WARN**：目录不可用时不注入、放行，由金蝶报错校准；
- **B fail-closed**：目录不可用且科目挂 ZDY0001 时阻断推送，提示先做连接测试恢复目录。
  （更安全但有误杀风险：金蝶侧故障时 FINFLOW 制证整体不可用）

---

## P0-2 · 规则 1/14 FIXED 自由文本维度（待拍板，本批新发现）

### 机制（已实地核实 seed 原文）
- 规则 1（即时行乐报销）：`"dimension":"FIXED","value":"部门=1001/项目=即时行乐"`；
- 规则 14（福利费/办公费）：`"value":"部门=综合管理部；项目=…（人工选）"`；
- 维度注入槽位后，金蝶弹性域校验的是**档案编码**（如项目 YX001、部门 D001 之类），自由文本不是合法编码；
- 金蝶报错会是「必录维度未录入**或不可用**」——与「值缺失」报错相同，从报错看不出是「值非法」，
  排查会绕远路（这正是 W15 现象的另一个候选解释）。

### 待拍板
- **A. 修订规则 JSON**：把 FIXED 自由文本改为真实档案编码（需财务提供部门/项目档案编码清单，seed 修订随下批迁移）；
- **B. FIXED 不注入槽位**：推送时维度留空，金蝶必录时再报错校准（简单但把问题推给推送时刻）。

---

## P0-3 · 多规则同优先级双命中（待拍板）

`KingdeeVoucherMatchingService` 当前择优口径未复核「同优先级双命中」时的行为（取首条 or 400 拒绝）。
本批未动匹配代码；拍板后实施（倾向 400 拒绝 + 列出命中规则让用户选，与「AI 建议不可用即人工」的产品口径一致）。

---

## P0-3b · 值映射/槽位配置误改无防护（待拍板）

维度配置是界面可改的（V42 设计目标），但误改/误删会直接让全链路制证失败。
待拍两个口径：① 配置修改是否需要二次确认/两次操作；② 误改后的恢复路径（本地导出备份 vs 重新导入）。
拍板前 interim 防线：值映射批量导入本身是幂等 upsert，可随时重灌修正。

---

## P0-4 · 规则 JSON 配置错误 → 错账（待拍板）

规则 JSON 是界面可维护的（V34），配错借/贷科目就会产出合法但**错误**的凭证草稿——系统级校验
（平衡、科目存在性）拦不住「语义错误」。当前唯一防线是 preview 确认页的人工确认。
待拍板：是否引入「规则变更需第二人复核」或「灰度生效」（新规则先对 N 笔流水试跑）。

---

## P1 实施记录（本批，全部完成）

### P1-1/P1-2 · builder 前置校验（文件：`KingdeeGlVoucherPayloadBuilder.java` / `KingdeeAccountCatalogService.java`）
- 构造器加第三参 `KingdeeAccountCatalogService`；
- `assertBankDimensionInjectedForBankAccounts`：目录可用且科目挂 ZDY0001 但分录无维度值 → 400 附处置指引
  （指引内容：确认银行账户页的金蝶账户映射，或规则模板声明 BANK_ACCOUNT）；目录不可用时跳过（fail-open 保持）；
- `assertNoSlotCollision`：单维度与 extraDimensions 写同一槽位 → 400 拒绝（判据 = extra 槽位 equals
  `props.getGlBankDimensionSlot()`；不同槽位共存是既有测试口径，不受影响）；
- `requiresBankDimension` 目录空时 WARN 留痕；
- 目录降级提示**只进日志**——`buildPayload` 返回值就是发金蝶 Save 的请求体，任何非 JSON 尾巴都会让报文解析失败
  （初版把提示拼在 JSON 后，本批自纠）；
- **爆炸半径核实**：V34 seed 22 条规则的 1002 行全部声明 `BANK_ACCOUNT` → 规则路径拦截零误伤。

### P1-3 · 维度映射导入档案状态回查（文件：`KingdeeDimensionMappingService.java` + 网关三实现）
- `batchUpsert` 落库后调 `auditBaseDataStatus`：SUPPLIER/CUSTOMER/EMPLOYEE 行按表单分组去重回查
  `FDocumentStatus`；暂存(A)/已提交(B)/不存在 → 汇总 WARN（含表单+编码+「先提交+审核」指引）；
- 回查异常只记日志不阻断导入（预警非准入——档案状态是财务在金蝶侧维护的，系统无法代审）；
- 新增网关方法 `queryBaseDataDocumentStatus(formId, numbers)`：Mock（VEN0001/2/KH0001/EMP0001→C、
  VEN0003→A，供测试断言）/ Unavailable（空 Map）/ Real（BillQuery `FNumber in (...)` 逐号转义）。

### P1-4 · GL_PUSHED 死锁（登记于 `docs/pending-fixes.md` FIX-011）
- 手工修法（RDS SQL + `AND push_status='GL_PUSHED'` 条件 + 影响行数断言 + 审计留痕要求）已登记；
- P1-5 的 409 文案指向 FIX-011；完整端点（需 `voucher:force-reopen` 权限迁移）留下轮。

### P1-5 · 并发推送守卫（文件：`KingdeeVoucherEngineService.java`）
- `pushAiVoucher` 与规则路径 `push` 均包 `ReentrantLock`（按 statementId）；
- 锁内重读流水、`assertNotAlreadyPushed`：GL_PUSHED/PUSHED → 409（带凭证号与 FIX-011 指引）；
- FAILED 放行（重试推送是既有能力）；只拦「已成功」，正是防重复凭证的关键窗口；
- 边界：单实例（ECS docker compose）JVM 锁足够；**多实例部署时需换 DB 条件更新**（已写入 javadoc 与本文）。

---

## 测试与提交

- 两个单测构造器改 3 参（`KingdeeGlVoucherPayloadBuilderTest` / `KingdeeDimensionMappingServiceTest`）+
  新增撞槽/维度拦截/并发重查/导入回查用例；全量 `mvn.cmd test -o -Djacoco.skip=true`；
- 测试基线注意：用例数随并行会话上涨，别拿旧数字当基线，以本次 CI 为准。
