# 部署报告 W16b（2026-09-22）— P1-6 GL_FAILED 重推修复

> 本批为 **W16 之后的纯后端热修**：仅替换 jar，**前端零改动、无新迁移**。
> 目标：把 P1-6（凭证中心 CAS 漏 `GL_FAILED`）上线，并借重推 W15 原始失败流水做端到端验收。

## 1. 版本范围

| 项 | 内容 |
|---|---|
| 代码范围 | `af3e553`（P1-6） / `d3e685f` / `9503aee`（P1 五件套） |
| 迁移 | 无新增（最高仍 **V42**） |
| 前端 | **未改动**（入口 bundle 保持 `index-BBeBG15i.js`） |
| 触发原因 | 真账套端到端测试发现 GL_FAILED 流水永久无法重推（详见风险文档 P1-6） |

## 2. 交付物

| 产物 | 值 |
|---|---|
| jar | `finance-system-backend-0.0.1-SNAPSHOT.jar`，**102,495,114 B**，MD5 **`465b65825d5f61360bdfc3d6483c180b`** |
| 构建方式 | `git archive HEAD backend` → 隔离目录 `C:/Users/Public/finflow-v16b` → Maven 3.9.16 `package -DskipTests` |
| web-dist | 未交付（前端未改） |

## 3. 部署前基线

- 线上 jar：**102,495,085 B**，09-22 12:25，MD5 `fae06f6dfcb260c1ab4bcb0b371dde78`（W16 版）
- 容器：`finflow-app Up 2 hours (healthy)` / `finflow-web Up 2 weeks`
- flyway：最高 **V42**（与本地一致 ⇒ 无迁移风险）
- 备份链：w13-pre / w14-pre / w15-pre / w16-pre 齐备

## 4. 执行

1. 备份（**早于上传**）：`app.jar.bak-20260922-w16b-pre`（MD5 `fae06f6d…`，即 W16 版）
2. 上传 `/tmp/app.jar.new` → 远端 MD5 `465b6582…` **与本地产物一致（三方校验通过）**
3. `cp /tmp/app.jar.new app.jar` → `docker compose up -d --build app`（jar 由 Dockerfile COPY，必须 rebuild）
4. 启动：`validated 41 migrations` → `Tomcat started on port 8080` → `Started FinanceSystemApplication in 10.502s`

## 5. 验收证据

### 5.1 三层门禁 + 缓存头

| 检查 | 结果 |
|---|---|
| 容器内 jar MD5 | `465b65825d5f61360bdfc3d6483c180b` ✅ 与交付物一致 |
| 服务器内入口 bundle | `assets/index-BBeBG15i.js`（前端未动，符合预期） |
| 公网 `/` | 200 ✅ |
| 公网 + 本地 `/api/health` | 401 ✅（链路不回归） |
| `index.html` 缓存头 | `no-cache, no-store, must-revalidate` ✅ |
| `assets/index-*.js` 缓存头 | `public, max-age=31536000, immutable` ✅ |

### 5.2 容器内字节码取证（jar 内铁律）

- `BOOT-INF/lib/*.jar` = **95**（含 `dlink-sdk-*` / `isec-*` / `k3cloud-webapi-sdk-*`）✅
- 金蝶 REAL 类齐备：`KingdeeSdkClient` / `KingdeeSdkConfig` / `RealKingdeeVoucherGateway` ✅
- **P1-6 目标代码在**：`StatementService.class`（50,794 B）字节码含 `GL_FAILED` ✅
- P1 前序修复未回退：`assertNotAlreadyPushed`（P1-5）/ `assertBankDimensionInjectedForBankAccounts`（P1-1）/
  `queryBaseDataDocumentStatus`（P1-3）/ `resolveAcctbookCode`（账簿跟随组织）/ 撞槽拒绝文案 全部 ✅
- 迁移文件 V40/V41/V42 在包内 ✅

### 5.3 端到端验收（本批核心）

**推送流水 18**（W15 原始失败流水：上海图虫 / `0922_1720219225` / 4,202.12）：
修复前推它必得 409「already in progress or has completed」。

```
POST /api/statements/18/voucher-push  →  code: 0 | Voucher push completed
  reviewStatus: APPROVED
  pushStatus:   GL_PUSHED          ← 修复前为 GL_FAILED（且重推 409）
  voucherNo:    16121
  pushedAt:     2026-09-22T14:23:26
```

**app 侧报文证据**（服务端日志）：
`acctbook=410 org=410 行数=2 分录=#1 2241.99 维度[无] | #2 1002 FDETAILID__FF100002=110922659010201`

**金蝶侧只读回查**（ExecuteBillQuery，非推测）：

| FBillNo | 日期 | 账簿 | 状态 | 创建人 |
|---|---|---|---|---|
| 16113（流水 17） | 2026-09-22 | 410 | A（暂存草稿） | 王一霏 |
| **16121（流水 18）** | 2026-09-22 | 410 | A（暂存草稿） | 王一霏 |

⇒ 账簿跟随组织（410）+ 图虫银行账号维度注入 + P1-6 重推链路 **三层全部实测成立**。

## 6. 回滚弹药

| 产物 | 位置 |
|---|---|
| W16 jar | `/opt/finflow/app.jar.bak-20260922-w16b-pre`（MD5 `fae06f6d…`） |
| 更早链 | `app.jar.bak-20260922-w16-pre` / `-w15-pre` / `-w14-pre` / `-w13-pre` |
| 前端 | 未改动，无需回滚 |

回滚步骤：`cp app.jar.bak-20260922-w16b-pre app.jar && docker compose up -d --build app`。
⚠️ 本次无迁移，回滚不影响 DB；反之**回滚会退回 P1-6 缺陷**（GL_FAILED 重新不可重推）。

## 7. 观察项

- **流水 17、18 已推送并留金蝶草稿凭证 16113 / 16121**（用户授权测试；如需作废需在金蝶侧删除，
  并在 FINFLOW 按 FIX-011 手工修法复位，否则会留下「FINFLOW 已推送、金蝶无凭证」的不一致）。
- **登录会踢用户界面会话**（单点登录）：本次验收用 admin 登录，须提醒用户重新登录。
- 前端 `/index.html` 与 assets 缓存头策略正常；本次未动前端，W16 的 bundle 保持有效。

## 8. 下一步

- 观察线上后续推送（含其他图虫流水）是否均落到账簿 410；
- P0 项待用户拍板（目录降级口径 / 规则 1·14 FIXED 自由文本维度 / 双命中择优 / 维度配置误改 / 规则错账防独审）；
- 图虫 19 条规则入库前置：3（社保拆分口径）、5（R33/R34 优先级）待财务/用户；1·2·4（供应商）·6 已完成。
