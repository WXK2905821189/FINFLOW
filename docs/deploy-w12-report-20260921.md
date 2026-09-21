# FINFLOW 部署报告 · W12（FIX-008 / FIX-009）

- **部署日期**：2026-09-21 15:46（GMT+8）
- **提交**：`53af5e9` — fix(ai,statement): 跨公司 AI 建议放行(FIX-008) + 凭证中心推送切总账落点(FIX-009)
- **类型**：**仅后端 jar 批次**（无前端改动、无新增迁移）
- **触发原因**：用户报「推送凭证仍提示组织未开启出纳」+「很多流水提示 AI 建议不可用」

## 1. 交付物

| 项 | 值 |
|---|---|
| jar | `finance-system-backend-0.0.1-SNAPSHOT.jar`（size 102,454,010） |
| jar MD5 | **`941441a1876c8c25e5d7dc959e741032`** |
| 前端 | **未改动**（入口仍 `assets/index-B3GSO8DZ.js`，assets 70 个） |
| 迁移 | **无新增**（线上 14:19 已应用至 v41） |

### jar 内取证（交付前）

| 检查 | 结果 |
|---|---|
| V40 / V41 迁移 SQL | 在场 |
| StatementService / AccountingSuggestionService / KingdeeAccountMappingService / AiGlVoucherAssembler / KingdeeVoucherEngineService | 在场 |
| 金蝶 real 三件套（KingdeeSdkConfig / KingdeeSdkClient / RealKingdeeVoucherGateway） | **在场**（缺则 REAL 模式首启 502） |
| **字节码级**：`StatementService` 含 `isGlTarget` + `pushGlVoucher` | **True**（FIX-009 真进包） |
| **字节码级**：`AccountingSuggestionService` 含 `bankdata:cross-company:view` | **True**（FIX-008 真进包） |
| `BOOT-INF/lib` 条目数 | 96（= skill 基线；实际 .jar 95） |
| k3cloud-webapi-sdk / dlink-sdk / isec | 均在（vendor SDK 随包交付） |

## 2. 部署前基线（应用层实测，非推断）

| 项 | 值 |
|---|---|
| 线上 jar MD5 | `795cae87b99543777b66d9f6b96bdaea`（W11，14:19） |
| 线上前端入口 | `assets/index-B3GSO8DZ.js` |
| flyway | **v41**（14:19 应用 V40/V41）；`Successfully validated 40 migrations` |
| 容器 | finflow-app healthy / finflow-web up |
| `.env` | KINGDEE 凭据齐全；**无 `KINGDEE_VOUCHER_TARGET`** → 走代码默认 `GL` |
| 端点判别（带 token 对照组） | `ai-voucher-jobs/latest` 200（V40）、`bank-accounts/kingdee-mapping` 200（V41）、`statements/kingdee/ping` 200（mode=REAL）；瞎编路径 500 |

> ⚠️ 基线勘察**纠正了先前误判**：线上并非版本落后，W11（含 V41）早已在线。

## 3. 执行

1. 隔离构建：`git archive HEAD backend` → `C:/Users/Public/finflow-w12/` → `mvn.cmd package -DskipTests -Djacoco.skip=true`（ASCII 路径）
2. **备份先于上传**：`cp -a app.jar app.jar.bak-20260921-w12-pre`（MD5 `795cae87…` 双验一致）
3. 上传 `scp` → `/tmp/app.jar.new`，**远端 MD5 = 本地 MD5 = `941441a1…`**
4. `cp /tmp/app.jar.new app.jar` → `docker compose up -d --build app`（换 jar 必须 rebuild）
5. 验收（见下）

## 4. 验收证据

| 检查 | 结果 |
|---|---|
| 容器健康 | **healthy**（t+2s） |
| **容器内** `/app/app.jar` MD5 | **`941441a1876c8c25e5d7dc959e741032`** ✓（与交付物同源 ⇒ 交付前字节码取证结论传递成立） |
| 启动日志 | `Successfully validated 40 migrations` + `Started FinanceSystemApplication in 9.691s`，**无 ERROR/Exception**、**无 `Migrating schema`**（无新迁移，符合预期） |
| 门禁 1（本机） | `GET /` → 200 |
| 门禁 2（业务） | `GET /api/health` → 401（需认证，符合预期） |
| 门禁 3（SPA 回退） | `GET /dashboard` → 200 |
| 缓存头 | `index.html` → `no-cache, no-store, must-revalidate`；入口 bundle → `public, max-age=31536000, immutable` |
| 前端白名单 | 70 个 assets 全部 200（非 200 数 = **0**） |

## 5. 回滚弹药

| 对象 | 路径 | MD5 |
|---|---|---|
| 上一版 jar | `/opt/finflow/app.jar.bak-20260921-w12-pre` | `795cae87b99543777b66d9f6b96bdaea` |
| 前端 | **本次未改动**，无需回滚 | — |

回滚步骤：`cp app.jar.bak-20260921-w12-pre app.jar && docker compose up -d --build app`。
⚠️ **回滚 jar 不会撤销 Flyway 迁移**——但本次无新增迁移，故不涉及。

## 6. 观察项

1. **功能验收（待用户操作）**：线上当前 16 条流水全部处于 `WITHDRAWN` / `REJECTED`，**没有一条 `APPROVED`**，
   无法在不新建数据的前提下实跑推送。请在界面上走一次：
   导入/选用**已映射金蝶账户**的流水 → AI 制证为草稿 → 凭证中心点推送 → 预期金蝶出现 `GL_VOUCHER` 草稿，
   **不再出现「未启用出纳」**。
2. **跨公司 AI 建议**：对上海图虫等**非本公司账户**的流水点「AI 制证为草稿」，预期不再出现
   「AI 建议不可用：流水不存在或不在当前公司域内」。
3. **账户映射现状**：招行 7 个账户已 `MAPPED`；4 个中信测试账户 `UNMATCHED`（测试号匹配不上 CN_BANKACNT）
   ——这些账户的流水推送会被**阻断并提示补映射**，属设计口径（用户拍板的 fail-closed）。
4. **推送前置**：GL 落点要求流水已有 AI 分录（`ai_suggestion_json`）。若流水没有分录，
   凭证中心推送会提示「请先在凭证草稿工作台生成 AI 建议后再推送」——即正确顺序为
   **先「AI 制证为草稿」再推送**。

## 7. 下一步

- 用户在界面完成第 6.1 / 6.2 两条功能验收；
- 中信测试账户若需走金蝶链路，需先做账户映射（或改 `MANUAL` 制证模式）；
- 出纳模块若日后启用，可用 `KINGDEE_VOUCHER_TARGET=BILL` 切回收付款单落点（两条链路共用凭据/状态机）。
