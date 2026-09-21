# W11 部署报告（V40 + V41）

- **日期**：2026-09-21
- **范围**：金蝶推送四项改造 + AI 制证 DRAFT 异步化（V40）+ 银行账号核算维度账户级映射（V41）
- **提交**：`139eae1`（主批次 53 文件 / +11682 −205）、`904b6a5`（lint 修复 3 文件）
- **CI**：run `35567656247` **5 job 全绿**（首轮 `35567377838` 前端 job 因 3 个 ESLint error 失败，见「观察项 1」）

---

## 一、交付物

| 项 | 值 |
|---|---|
| 后端 jar | MD5 `795cae87b99543777b66d9f6b96bdaea`，102,453,360 B |
| jar 内取证 | **27/28 PASS**（唯一 FAIL 是我脚本里过时的 lib 基线 85，实际 96 条目/95 jar，已修正脚本；非产品问题） |
| 前端 dist | MD5 `c3dbd00dd78a518072e8d17387acee8b`，568,480 B，**70 assets**，入口 `index-poqLUI5W.js` / `index-CxEaEnuL.css` |
| 迁移 | V40 `ai_voucher_job`、V41 `bank_account.kingdee_account_number` |

取证明细（jar 内）：V40 建表 + 索引、V41 加列关键字齐备；V40 五个新类（AiVoucherJob / Mapper / Service / Response / SubmitResponse）、V41 六个新类（MappingService / AccountDimensionResolver / BankAccountDimensionResolver / AiGlVoucherAssembler / AccountCatalogService / MappingRequest）全部在包；金蝶 REAL 类 `KingdeeSdkConfig` / `KingdeeSdkClient` 在包；`RealKingdeeVoucherGateway` 字节码含 `ensureBaseDataAudited` 与 `FDocumentStatus`（FIX-006 未回退）；中信 dlink-sdk / isec 与金蝶 k3cloud-webapi-sdk 三件套齐备。

## 二、部署前基线（预检实测）

| 项 | 值 | 判据 |
|---|---|---|
| 线上 jar | `1fa565e5a7e86ce4854201251532bb70`（102,395,181 B，09-20 16:12） | = W10 批次 B 报告记录值 ⇒ **无影子部署** |
| 线上入口 | `assets/index-CjBMeGPf.js` | 同上 |
| flyway | `validated 38 → Current version 39` | = V39 |
| assets | 72 个 / 1.9M | |
| 容器 | finflow-app healthy（22h）、finflow-web up 2 weeks | |
| 磁盘 | 40G 用 14G（37%） | |

## 三、执行

1. **独立备份（早于替换）**：`app.jar.bak-20260921-w11-pre`（MD5 复核 = `1fa565e5…`，确认备份到的是旧 jar）+ `web-dist.bak-20260921-w11-pre.tar.gz`
2. **换 jar**：`cp /tmp/app.jar.new app.jar` → `795cae87…`
3. **web-dist 原地处理**：新包清单 70 个（≥10 断言通过）→ 现盘 72 个，清新包外旧 chunk **68 个** → 原地解压 → 集合比对 **EXACT-MATCH**（70 = 70）
   - 未 `mv` 目录：`web-dist` 是 bind mount 到容器 `/usr/share/nginx/html`
4. **重建**：`docker compose up -d --build app` → **t+25s healthy**
5. **Flyway**：`validated 40 migrations` → `Migrating "40 - ai voucher job"` → `Migrating "41 - bank account kingdee mapping"` → **`Successfully applied 2 migrations, now at version v41`** → `Started in 12.268s`
6. **上传三方 MD5 一致**：本地 = ECS `/tmp` = 实际使用（jar `795cae87` / dist `c3dbd00d`）

## 四、验收证据

**三层门禁**
| 层 | 结果 |
|---|---|
| ① 服务器内 | 首页引用 `assets/index-poqLUI5W.js` ✅ |
| ② 公网 | `http://101.200.72.87/` → **200**；`/api/health` → **401** ✅ |
| ③ 浏览器级 | Edge headless dump DOM：root **3939 字符 / 48 元素 / 2 input / 1 button**，渲染出登录页（"FINFLOW｜企业级资金管理平台"），**无白屏** ✅ |

**缓存头**（防「服务器好像没更新」复发）
| 路径 | Cache-Control |
|---|---|
| `/index.html`、`/`、`/dashboard`、`/bank-data/query` | `no-cache, no-store, must-revalidate` ✅ |
| `/assets/index-poqLUI5W.js`、`/assets/index-CxEaEnuL.css` | `public, max-age=31536000, immutable` ✅ |

**静态资源完整性**：白名单 **70 个逐个 curl = 70/70 全 200**，非 200 = 0。反证：旧入口 `index-CjBMeGPf.js` → **404** ✅

**API 轻验**（复用现有令牌，**未重新登录 ⇒ 未踢掉在线会话**）
| 端点 | 结果 |
|---|---|
| `GET /api/bank-accounts/kingdee-mapping`（V41 新增） | `code:0`，**11 行**账户：`AUTO_MATCHABLE 7` / `UNMATCHED 4`；`gatewayMode=REAL`、`catalogAvailable=true` |
| `GET /api/bank-data/ai-voucher-jobs/latest`（V40 新增） | `code:0`，`data:null`（尚无任务，正常） |
| 带 token 打瞎编路径（对照） | **500** —— 印证「未带 token 的 401 无判别力」 |
| 不带 token 打真实端点 | 401（匿名被 Security 拦，符合预期） |

**运行态**：`docker logs` 近 200 行无 ERROR/Exception；finflow-app healthy。

## 五、回滚弹药

```bash
cd /opt/finflow
cp app.jar.bak-20260921-w11-pre app.jar
rm -rf web-dist/assets && tar -xzf web-dist.bak-20260921-w11-pre.tar.gz -C web-dist
docker compose up -d --build app
```

> ⚠️ 回滚 jar **不会撤销 Flyway 迁移**。V40 建了 `ai_voucher_job` 表、V41 给 `bank_account` 加了 `kingdee_account_number` 列——两者对旧代码无害（旧代码不认识它们，不会读写），故 jar 回滚可安全执行、无需手工 drop。若确需彻底回退，手工执行 `DROP TABLE ai_voucher_job;` 与 `ALTER TABLE bank_account DROP COLUMN kingdee_account_number;`。

持久化：`web-dist-release-20260921-w11.tar.gz`（568,480 B，正式发布包副本）。

## 六、观察项

1. **首轮 CI 前端 job 失败（已修）**：`139eae1` 的 Frontend job 卡在 Lint（3 errors）。
   - `kingdeeMapping.tsx:51` / `AiVoucherJobBanner.tsx:46`：`react-hooks/set-state-in-effect` —— effect 体内 `void load()` 会同步 setState。改为把首拉放进宏任务（`window.setTimeout(…, 0)` + `clearTimeout` 清理）。
   - `api.ts:44`：`AiVoucherBatchResult` 成了孤儿 import —— V40 把 `aiVoucher` 返回类型改为 `AiVoucherSubmitResult` 后旧类型无人引用（类型定义本身仍被 `BankDataQueryPage` 使用，未删）。
   - **根因是本地无前端验证能力**：`frontend/node_modules` 是空壳（`typescript` 等包实体为空目录、符号链接指向 `/c/...` 在 Windows 断裂），`tsc`/`eslint`/`vite` 一律跑不起来，前端**只能靠 CI 兜底**。后端本地有全量 `mvn test`（386 用例 0 失败）可拦。
2. **`ci.yml` 的 `expected_versions` 漏列 V41（本批修复）**：该断言是 `test "${actual_versions[*]}" = "${expected_versions[*]}"`（逐项字符串比对），实际文件已含 V41 而清单只到 V40 ⇒ **漏列必红**。提交前已补齐，H2/MySQL 迁移 job 据此通过。
3. **V41 上线后的可操作状态**：真实账套下 `gatewayMode=REAL`、`catalogAvailable=true`，11 个 `KINGDEE_AUTO` 账户中 **7 个 `AUTO_MATCHABLE`（可一键自动匹配）、4 个 `UNMATCHED`（需人工指定）**。建议管理员进「银行账户」页点一次「金蝶账户映射 → 一键匹配」，再处理 4 个未命中的。
4. **部署未改动 env / nginx**：本批次无新增环境变量（V40 任务与 V41 映射均走 DB），nginx 配置沿用 09-18 的 `no-cache` 修复版，未触碰。
5. **W11 未提交遗留（有意保留在工作区）**：
   - 「批次 A」4 文件（`docs/product-requirements.md`、`docs/ui-click-test-checklist-2026-09-17.md`、`frontend/src/App.tsx`、`frontend/src/modules/statements/ValidationPage.tsx`）—— 用户已拍板**挂起**；
   - 「字典中心」5 文件（`useBankNames.ts`、`archive.tsx`、`BankDataQueryColumns.tsx`、`BankQueryGridColumns.ts` 及与之混改的 `pages.tsx` / `BankDataQueryPage.tsx` / `api.ts` 的字典部分）—— 属并行会话批次。
   - 本次提交按 pathspec + hunk 严格切分，3 个混改文件（`pages.tsx` / `BankDataQueryPage.tsx` / `api.ts`）用「工作区混合版备份 → 还原为 HEAD → 只加本批改动 → add → 恢复混合版」构造，**工作区混合版已逐字节复原（MD5 比对一致）**。

## 七、下一步

1. **业务侧**：管理员执行一次金蝶账户映射（7 个可自动匹配）；4 个 `UNMATCHED` 手工指定（多为支付宝/分贝通等虚拟账户编码）。
2. **FIX-007（P1）未解**：真实账套推送仍卡在 `当前组织未启用出纳`。两条路线待业务拍板：A 请金蝶启用出纳模块（保留收付款单落点）／B 制证落点切 GL_VOUCHER 总账凭证（9/11 已实测可写、不需出纳模块、与规则引擎方向一致）。
3. **挂起批次**：「批次 A」文档校准 + 字典中心前端，待用户放行后各自成批。
   - 其中**字典中心前端 7 文件已于同日 W11b 上线**（见 §八）；「批次 A」仍在工作区。
4. **`docs/dict-center-plan-20260921.md`** 里的 P0-1（`updateItem` 允许改 `itemCode` 会造孤儿）仍是未决设计项。

---

## 八、补充批次 W11b（同日，仅前端）

**起因**：用户在 W11 上线后反馈两点 —— ①「余额查询」分组汇总行显示「可用余额合计」属无效信息（同一账户下多天余额被重复相加）；②「主体树」的优化看起来没更新。

**归因（线上取证）**：下载线上全部 chunk 后定位到
- `可用余额合计` 出现在 `BankDataQueryPage-DOrWNHme.js` ⇒ 用户看到的是**当前线上行为**（本次修复对象）；
- 主体树节点线上仍是 `` `${accountName}（${maskedAccountNumber}）` ``，且全站 chunk **`dicts` 调用为 0** ⇒ 该优化属**当时未提交的字典中心批次**（W11 严格切分时被有意排除），**不是部署失败**。

**提交**：`8aed087`（字典中心前端 7 文件 / +183 −25）、`8ff7da8`（余额汇总口径修复 1 文件 / +12 −4）
**CI**：run `35570581140` **5 job 全绿一次过**（无新迁移，`expected_versions` 不变）

### 交付物
| 项 | 值 |
|---|---|
| 前端 dist | MD5 `10288cf3807932bcb3d5718df668ba6a`，568,941 B，**70 assets**，入口 `index-B3GSO8DZ.js` / `index-CxEaEnuL.css`（CSS 无变化） |
| 后端 jar | **未改动**（`795cae87…`，本批无后端改动、无迁移） |
| 新增文件 | `frontend/src/modules/bank-access/useBankNames.ts`（132 行） |

### 内容
1. **余额查询分组汇总口径修正**（`BankDataQueryPage.tsx`）：去掉「可用余额合计」；「N 个账户」由**行数**改为**按账户去重**（`bankAccountId → accountMasked → bankAccountNo` 依次取键）。
   - 去掉的理由：同一账户在筛选区间内可能有多天余额，对行求和属重复计入；余额还可能是多币种，不可相加。
   - 流水侧「N 笔 · 借 X / 贷 Y」口径本就正确（一行即一笔），未动。
2. **银行中文名改由字典中心驱动**：字典类型 `bank` 成为唯一可维护源，`BANK_NAME_TEXT` 降级为代码兜底。覆盖 6 处：主体树账户节点（「银行-尾号」+ 悬浮提示完整户名/账号）、网格「银行」列（含导出与筛选候选）、余额详情抽屉、银行账户页（列 / 新增下拉 / 连通测试标题）、公司档案页（摘要 + 下拉）。
   - `useBankNames`：模块级缓存 + inflight 去重 + **`resolveBankName` 恒定引用**（避免内核列 memo 每渲染重建）+ `revision` 驱动重建；本轮另把 `options` 加 `useMemo`，否则每次渲染返回新数组会带动依赖它的 memo 重算。
   - 消费端点 `dictApi.activeItems`（`GET /api/system/dicts/{typeCode}/items`，仅需登录态、类型不存在返空数组）。

### 执行
1. 备份 `web-dist.bak-20260921-w11b-pre.tar.gz`（**内含入口 `index-poqLUI5W.js`，证明备份早于替换**）
2. 先清新包外旧 chunk **65 个**（70 → 70）→ 原地解压 → 集合比对 **EXACT-MATCH**
3. **不重建容器**：仅前端资源，nginx 直接读盘（`web-dist` 为 bind mount，全程原地操作未 `mv`）

### 验收证据
| 层 | 结果 |
|---|---|
| ① 服务器内首页 | `assets/index-B3GSO8DZ.js` |
| ② 公网 | root 200 / health 401；入口 bundle 200 |
| ③ Edge headless 渲染 | root **3960 字符 / 49 元素**，登录页文案正常，**无白屏** |
| 缓存头 | `/index.html` → `no-cache, no-store, must-revalidate`；`/assets/` → `public, max-age=31536000, immutable` |
| 白名单 | 容器内 **70/70 全 200**；旧入口 `index-poqLUI5W.js` **404**（反证） |
| 构件功能取证（线上包） | `可用余额合计` **0 命中**；`M 个账户 · 最近截止` 命中；`system/dicts` 命中；`金蝶账户映射`（V41）仍在 |
| 终态 | 入口 `index-B3GSO8DZ.js` / 70 assets / jar `795cae87` 未变 / finflow-app healthy、finflow-web up |

### 回滚弹药
`web-dist.bak-20260921-w11b-pre.tar.gz`（= W11 的 dist）+ 发布包 `web-dist-release-20260921-w11b.tar.gz`。回滚仅需原地解压旧包，**不涉及 jar 与数据库**。

### 观察项（W11b）
1. **字典数据尚未维护**：线上 `bank` 字典类型为空，界面当前走代码兜底常量（`CMB`=招商银行 / `CITIC`=中信银行），**显示正常**。要让其它银行（工行/建行等）显示中文名，在「系统管理 → 字典中心」新建类型 `bank` 并加项即可，**无需发版**——这正是本批的目的。
2. **脚本小坑**：部署脚本 §6「终态」段相对路径失效（§4 里 `cd web-dist` 后未返回上级，导致 `web-dist/index.html` 二次拼接）。不影响部署结果（§1–§5 均已通过），已在 skill 中记入坑清单。
