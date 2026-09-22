# W14 部署报告（科目兜底策略 + 名称不一致放行 + 置信度可编辑）

- **日期**：2026-09-22 09:41（GMT+8，机器时钟；本轮工作会话起于 09-21 傍晚）
- **提交**：`9a2526f`（后端兜底策略）→ `19451c0`（置信度编辑，**落在死代码**）→ `cc6f430`（迁徙到 live 页面 + 撤回死代码改动）
- **CI**：run `35675978344` / `35676360494` **两轮 5 job 全绿**
- **触发**：用户反馈「错误的科目、或置信度低的科目，先保证凭证能推到金蝶，大不了我手工调」

---

## 一、实测先行（三条决定性事实，均为真实账套 400 保存后即删）

| 用例 | 报文 | 金蝶反应 |
|---|---|---|
| A 对照 | 借/贷 1001 各 1.00 | ✅ 接受（单号 16076，已删） |
| B 科目留空 | `FACCOUNTID.FNumber=""` | ❌ 拒绝：「请输入凭证数据，凭证分录不合法！」 |
| C 科目缺键 | 不写 `FACCOUNTID` | ❌ 拒绝：同上 |
| D 兜底候选 `2241` | 借 2241 / 贷 1001 | ❌ 拒绝（**父科目不允许记账**，`FIsDetail=false`） |
| E 兜底候选 `2241.99` | 借 2241.99 / 贷 1001 | ✅ 接受（单号 16077，已删） |
| F 兜底候选 `1901` | 借 1901 / 贷 1001 | ✅ 接受（单号 16078，已删） |
| G 对照组 `2241.05` | 借 2241.05 / 贷 1001 | ❌ 「必录维度未录入或不可用：组织机构」（证明探测方法有效） |

**结论**：①「科目录空推上去」在金蝶侧不成立 ⇒ 必须用真实存在且可记账的科目兜底；
②用户最初指定的 `2241` 不可用（父科目），改用实测通过的 **`2241.99` 其他应付款-其他**。

## 二、上线内容

### 1. 后端（`9a2526f`）

| 改动 | 说明 |
|---|---|
| **名称不一致不再阻断** | `KingdeeAccountCatalogService.check`：金蝶报文只发科目**编码**（`FNumber`），名称不参与推送 —— 本地因名称不同拒绝属过度拦截（用户实际被这条挡住）。现以账套名称为准 + 返回 note，组装器写进 warnings 留痕 |
| **科目不可用 → 兜底替换** | `AiGlVoucherAssembler`：编码账套不存在 / 编码空且名称反查不到 ⇒ 换 `kingdee.fallback-account`（默认 `2241.99`）+ warning；**兜底科目自身在账套不存在时维持原拦截**（不把必然被拒的编码发出去） |
| **低置信度 → 兜底替换** | 置信度低于 `kingdee.low-confidence-threshold`（默认 0.6）同样走兜底；人工调高即视为已确认 |
| 配置 | 两个新键进 `application.yml` **并同步 compose 白名单**（compose 是 env 透传唯一入口，漏加会「在 .env 改了不生效」） |

### 2. 前端（`19451c0` + `cc6f430`）

`/statements/voucher-doc/:statementId`（凭证单据详情页）：置信度列可编辑（`InputNumber` 0~100、步长 5），
改动行标「未保存」，顶部出现「保存置信度（N）」→ 走既有 `PUT /statements/{id}/voucher-draft`；
仅 `voucher:push` 持有者可编辑。页面补推送策略说明。

## 三、过程中的关键坑（**本轮最有价值的发现**）

### 坑 1：前端改动落在死代码里（差点静默无效交付）
第一版把「置信度可编辑」加在 `modules/statements/VoucherDraftDrawer.tsx`。
CI 全绿、`vite build` 成功，但**构件探针发现本批 4 个独有文案 0 命中**，而上一批标记都在
⇒ 回查引用关系：该组件**已不可达**（`statements/pages.tsx` 只被 `VoucherCenterPage` 引用到 `AuditDrawer`，
`VoucherDraftDrawer` 无任何引用，V34 ⑦ 后被单据详情页取代），构建时被 tree-shake 掉。

**判据与做法**：入口 hash 不变不等于改动无效（改动可能落在独立 chunk），必须逐个 grep
**本批独有的业务文案**；发现 0 命中后，先查「文件是否可达」（`grep -rn "<组件名>" frontend/src`）
再怀疑构件陈旧。随后把实现迁到 live 的 `VoucherDocPage` 并撤回死代码改动（`cc6f430`），
重新下载构件确认 4/4 命中（`VoucherDocPage-BVwcCTqX.js`，入口 `index-D4iIYHJu.js`）。

### 坑 2：Spring Boot jar 的 MD5 **天生不可复现**
同源代码两次构建：大小一致（102,487,855）、`BOOT-INF/classes` 逐字节相同（`diff -rq` 0 差异），
但 **MD5 不同**（`58e4ce81…` vs `47230983…`）—— zip 条目内嵌构建时间戳。
⇒ **判「后端有没有变」要比内容，不能比 MD5**；MD5 只适用于「同一份文件在本地/ECS/容器内三处一致」的传输校验。

### 坑 3：本地 `mvn test` 的增量编译会假绿
改了 `record EntryInput` 的签名（加 `confidence`）后，`mvn -o test-compile` 报
「Nothing to compile - all classes are up to date」，测试类**没有重编**，看起来全绿；
`mvn -o clean test-compile` 才暴露 `COMPILATION ERROR`。
⇒ **凡改动 public 签名/record 组件/构造器，本地验证一律先 `clean`**。

## 四、交付物与验收

| 项 | 值 |
|---|---|
| jar | MD5 `472309836490c79a25e3e0003b5d144e`（102,487,855 B） |
| jar 取证 | 通用 **24/24 PASS**；本批 5/5 PASS：兜底文案「待确认科目」、低置信度文案、目录服务新 note「已按账套名称处理」、**旧拦截文案「名称与账套不一致」已消失**、配置类含 `2241.99` |
| 前端 | 入口 `index-D4iIYHJu.js` / 69 assets；`VoucherDocPage-BVwcCTqX.js` 含「待确认科目 2241.99」「置信度 %（可改）」「保存置信度」 |
| compose | 现场与仓库版差异**恰为 5 行**（3 行注释 + 2 个键）；脚本设「差异 > 12 行即中止」护栏，未触发 |
| env 生效 | 容器内实测 `KINGDEE_FALLBACK_ACCOUNT=2241.99`、`KINGDEE_LOW_CONFIDENCE_THRESHOLD=0.6` |
| 三层门禁 | ①服务器内 `index-D4iIYHJu.js` + 白名单 **69/69 全 200** ②公网 root 200 / health 401 ③Edge headless 49 元素无白屏 |
| 缓存头 | `/index.html` no-cache；`/assets/` immutable；旧入口 `index-C4MUBOJN.js` **404**（反证） |
| 启动 | `Started FinanceSystemApplication in 10.296 seconds`，容器 healthy |
| 迁移 | **无新增**（flyway 保持 v42） |
| 备份先于替换 | `app.jar.bak-20260922-w14-pre`（MD5 = 基线 `b949a0b6`）、`web-dist.bak-…-w14-pre.tar.gz`（含旧入口）、`docker-compose.yml.bak-…-w14-pre` |

## 五、回滚弹药

`app.jar.bak-20260922-w14-pre`(`b949a0b6`) / `app.jar.release-20260922-w14` /
`web-dist.bak-20260922-w14-pre.tar.gz`（含 `index-C4MUBOJN.js`）/ `web-dist-release-20260922-w14.tar.gz` /
`docker-compose.yml.bak-20260922-w14-pre`。**无迁移**，回滚不动数据库。

## 六、观察项与待办

1. **真实链路待用户复验**：请用户在凭证单据详情页对那条「科目名称不一致」的流水再推一次 ——
   预期：①名称不一致不再拦截（按账套名称推）②若还有其它科目不可用/置信度 <60%，该行科目会变成
   `2241.99 其他应付款-其他` 并在推送消息里给出提示；③推上去后在金蝶侧手工改成正确科目。
2. **兜底科目是会计意义上的「临时科目」**：建议财务后续把这类凭证在金蝶侧清理，或在
   `KINGDEE_FALLBACK_ACCOUNT` 里换成公司习惯的过渡科目（改 .env 即可，compose 已透传）。
3. **`2241.99` 的适用性依赖账套**：它是「明细科目 + 无必录维度」，若换账套（其它 org）挂了维度，
   需先用 `tmp/kd-probe-fallback-candidates.py` 重测（脚本已按新账套可复用）。
4. **死代码识别是长期隐患**：`VoucherDraftDrawer`（含一套完整的草稿编辑器 + 校验）已不可达；
   前端可能还有类似残留。建议单开一批「前端可达性清理」（`grep` 引用 + 删除 or 复活）。
5. 本批未做的仍挂着：币种字典化、组织映射字典化（用户已排下一轮）。
