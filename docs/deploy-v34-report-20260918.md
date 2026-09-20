# FINFLOW V34 部署报告

> 日期: 2026-09-18 09:51–09:56 ｜ 执行: 部署侧（WB）
> 基线: `master` HEAD = **c61e2cf**（09-18 09:43）
> 结论先行: **V34 已上线，三层门禁 + DB 迁移 + API 轻验全绿；线上 schema 由 v32 推进到 v34。**

---

## 一、版本范围

本次跨越 **两个迁移版本**（上次线上部署为 V32，schema 实测停在 v32）：

| 项 | 内容 |
|---|---|
| V33 | 权限基线再规划（role 3 补 voucher:push 13 / bankdata:raw:view 39 / system:dict:manage 41）+ `statement_record.ai_suggestion_json` 列 |
| V34 | `kingdee_voucher_rule` 建表 + 22 条规则 seed（凭证规则引擎数据地基） |
| 代码 | WP-A~WP-E 凭证体系重构（voucherrule / vouchergroup 包）、数据查询改版、银行账户新增改版、两处 P1 修复（f0a627f 结账口径 + 大类规则信封）、A1/B1/C1（c61e2cf 探测语义 + 导出门控 + antd 告警清零） |

## 二、交付物

| 项 | 值 |
|---|---|
| 后端 jar | **`34d4f7b4d4d2156613bf162460d1c97d`**（84,109,681 B）——本地 = 上传 = ECS 三方一致 |
| 前端 dist | CI run **35296619075**（HEAD `c61e2cf`）构件，tar MD5 `250cc32ba7471367f251e048fde4e514` |
| 前端 bundle | **`index-jhvbQ_77.js`**（部署前 `index-D6wMB1j2.js`） |
| 构建方式 | `git archive HEAD backend` → ASCII 隔离路径 `/c/Users/Public/finflow-v34` → `mvn package -DskipTests -Djacoco.skip=true` |

**jar 内取证**（交付前）：

- 迁移 SQL：`V32__` / `V33__` / `V34__` 均存在，迁移总数 33
- V33/V34 代码痕迹：`voucherrule` / `vouchergroup` / `OrgResolver` / `MatchingService` / `PayloadBuilder` / `KingdeeVoucherRuleController` 全部命中
- 金蝶 real：`KingdeeSdkConfig.class` ✅ / `KingdeeSdkClient.class` ✅ / `RealKingdeeVoucherGateway.class` ✅
- 前序修复未回退：`CmbBankService.class` ✅ / `RealCmbBankDataAdapter.class` ✅ / `BankDataStatus` 含 `SUC0000` ✅
- vendor lib：85 个，含中信 `dlink-sdk-lib-4.1.3` + `isec-*`、金蝶 `k3cloud-webapi-sdk-java11-8.2.0`

> 前次遗留（每次部署须手工补 untracked `KingdeeSdkConfig.java`）**已消除**——该文件 7b029f9 已入库，本次 `git archive` 直接带出。

## 三、部署前基线核对（发现异常）

| 项 | 实测 | 说明 |
|---|---|---|
| 线上 jar | `e2d868bbfbc649eda2e458c3ce1c8bfd`（Sep 17 **12:09**） | **非部署侧操作的版本**——本侧上次部署为 09-17 10:40 的 V32（`d8c6dd0c`），12:09 存在一次来源不明的部署 |
| 线上 web-dist bundle | `index-D6wMB1j2.js` | 与 V32 部署时的 `index-D9yUFHNx.js` 不同，印证 12:09 那次也换了前端 |
| flyway 最高版本 | **v32** | 即 12:09 的 jar 仍为 V32 基线（不含 V33/V34），本次部署确有必要 |
| .env 开关 | `BANKDATA_*` 2 个 + `KINGDEE_*` 10 个 | 金蝶 REAL / CMB REAL 配置齐备 |
| citic-cert bind | 在位（compose L66） | — |

## 四、部署执行

1. 备份：`app.jar` → `app.jar.bak-20260918-v34-pre`（`e2d868bb`）；`web-dist` → `web-dist.bak-20260918-v34-pre.tar.gz`
2. 替换 jar（三方 MD5 校验一致）
3. `web-dist` **原地解压**（不 mv），新 bundle `index-jhvbQ_77.js` 生效
4. `docker compose up -d --build app`
5. health 轮询：t+10s `starting` → **t+20s `healthy`**

## 五、验收结果（全绿）

### 5.1 三层门禁

| 层 | 结果 |
|---|---|
| ① 服务器内 | 首页引用新 chunk `index-jhvbQ_77.js` ✅ |
| ② 公网 | root **200** / `/api/health` **401** / 端点均 401 ✅ |
| ③ 浏览器级 | Edge headless 渲染登录页（`auth-page` / FINFLOW / 「让每一笔资金，清晰且可追溯」），root 非空、**零白屏** ✅ |

### 5.2 DB 迁移

启动日志：`Successfully validated 34 migrations` → `Current version: 32` → **applied 2 migrations，now at version v34**（09:53:24），`Started in 9.674 seconds`，无异常。

| 迁移 | 落库 | 效果实测 |
|---|---|---|
| V33 | success=1 @09:53:24 | `statement_record.ai_suggestion_json` varchar NULL ✅；role 3 持权限 13/39/41 ✅ |
| V34 | success=1 @09:53:24 | `kingdee_voucher_rule` **22 条，enabled 22** ✅ |

### 5.3 API 功能轻验（服务器内，admin 登录）

| 端点 | 结果 |
|---|---|
| `GET /api/kingdee/voucher-rules` | **code=0**，data 为 list、**22 条**（f0a627f 信封修复实证）✅ |
| `GET /api/kingdee/voucher-rules?enabledOnly=true` | code=0，22 条 ✅ |
| `GET /api/statements/voucher-groups` | code=0，page/size/total/records，total=3 ✅ |
| `GET /api/bank-accounts` | code=0，**11 条**（含 7 个 CMB；V32 软删除过滤正常）✅ |

### 5.4 容器内取证

`V32__/V33__/V34__` SQL ✅ / `KingdeeSdkConfig.class` ✅ / `voucherrule` 包 ✅ / lib 85 ✅ / env 注入 18 项 ✅。

## 六、回滚弹药（已持久化到 /opt/finflow/）

| 文件 | 内容 |
|---|---|
| `app.jar.bak-20260918-v34-pre` | `e2d868bb`（部署前版本，Sep 17 12:09） |
| `web-dist.bak-20260918-v34-pre.tar.gz` | 部署前前端（`index-D6wMB1j2.js`） |

> ⚠️ **注意**：V33/V34 迁移已执行。回滚 jar **不会**撤销迁移。如需完整回退需手工执行：删除 `statement_record.ai_suggestion_json`、`kingdee_voucher_rule` 表、回收 role 3 的权限 13/39/41，并将 flyway_schema_history 中 version 33/34 行删除后重新 `repair`。

## 七、观察项（不阻塞，供全栈侧）

1. **9/17 12:09 存在一次来源不明的部署**（jar `e2d868bb` + bundle `D6wMB1j2`），非部署侧操作。建议确认来源，避免部署链路上出现"影子版本"。
2. **不存在的 API 路径返回 500 而非 404**：`GET /api/kingdee/voucher-rule/rules`（错误路径）触发 `NoResourceFoundException`，被 `GlobalExceptionHandler` 按 `unhandled exception` 归为 500。建议对 `NoResourceFoundException` 单独映射 404，避免排障误导。
3. V34 迁移的 `TINYINT(1)` 触发 MySQL `Integer display width is deprecated` WARN（Error Code 1681）——无语义影响，后续新表可改用 `TINYINT`。

## 八、建议的下一步（业务/页面侧）

1. 页面人工复核：凭证中心 / 凭证单据 / 大类规则（22 条规则渲染与 priority 排序）、银行账户新增（2 必填 + 银行识别 + 测试连通三态）、归档页移除账户、AI 设置页对 FINANCE_STAFF 不可见
2. 财务经理账号登录复核：应能看到「凭证草稿与制证」与 AI 制证入口（V33 权限补授）
3. 全栈侧确认 §七.1 的 12:09 部署来源

---

**脚本留档**：`tmp/v34-{build,deploy,verify,lightverify,wrapup}.sh` ＋ `tmp/v34-*-out.txt`
