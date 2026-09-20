# FINFLOW V35 部署报告（Excel 表格内核落地）

- **部署日期**：2026-09-18
- **提交**：`57b3bfa` feat(bankdata): V35 Excel 表格内核落地（排序/列筛选/双合计/命名视图/冻结列）
- **CI**：[run 35300441692](https://github.com/WXK2905821189/FINFLOW/actions/runs/35300441692) — 5 job 全 success
- **ECS**：101.200.72.87（nginx :80 对外，app 8080 未映射）
- **结论**：✅ 上线成功，三层门禁全绿，真实数据端到端点击取证通过

---

## 一、版本范围

把 `docs/ui-v34-demo.html` 里已验证的表格内核**真正移植进产品**，余额查询与流水查询两页共用同一内核。
此前 V35 内核只存在于 demo（未上线），是本轮唯一「已完成但未上线」的内容。

### 交付物

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| 表格内核 | `frontend/src/modules/bank-access/grid/kernel.ts` | 1272 行，自管 DOM；排序/列头筛选/双合计/命名视图/冻结列/查找/分组/导出 |
| React 包装 | `frontend/src/modules/bank-access/grid/ExcelGrid.tsx` | 300 行，只给空容器 + role 锚点，挂载时 `createGrid` 一次 |
| 样式 | `frontend/src/modules/bank-access/grid/grid.css` | 302 行 |
| 偏好 Hook | `frontend/src/modules/bank-access/grid/useGridPreference.ts` | 900ms 防抖 + 卸载 flush |
| 列适配层 | `frontend/src/modules/bank-access/BankQueryGridColumns.ts` | 263 行，余额/流水两套列定义 + 双轨派生 |
| 页面改造 | `frontend/src/modules/bank-access/BankDataQueryPage.tsx` | 423 行改动，移除 antd Table / localStorage 隐藏列 |
| 后端迁移 | `backend/src/main/resources/db/migration/V35__account_preference.sql` | 新表 `account_preference`，`uk(user_id, scope_key)` |
| 后端服务 | `backend/src/main/java/com/finance/system/preference/`（4 类） | `GET/PUT /api/preferences/{scope}` |
| 后端单测 | `backend/src/test/java/com/finance/system/preference/AccountPreferenceServiceTest.java` | 7 条，覆盖 upsert 分支与三类形态校验 |
| CI 同步 | `.github/workflows/ci.yml` | 6 处迁移计数与断言同步 |

### 四条口径（2026-09-17 已拍板，本轮未擅改）

1. **排序＝仅当前页** —— 界面常驻琥珀色「仅本页排序」标注（财务红线）
2. **双合计并存且分清** —— 状态栏＝本页可见小计（受列头筛选影响）；工具栏＝服务端全量聚合，不含本页筛选。
   接口拿不到金额聚合时**只报行数**（`GridTotals.sum` 改为可选），绝不拿本页求和冒充全量
3. **视图偏好＝服务端账号级、跨设备一致** —— 新增 `account_preference` 表 + `/api/preferences/{scope}`
4. **导出＝当前查询条件全量**，有选区时按钮联动为「仅导出选中 N 行」

---

## 二、部署前基线

| 项 | 部署前 |
| --- | --- |
| `app.jar` MD5 | `34d4f7b4d4d2156613bf162460d1c97d`（V34，09-18 09:52） |
| 首页 bundle | `assets/index-jhvbQ_77.js` |
| Flyway 最高版本 | **34**（V35 未应用） |
| `account_preference` 表 | 不存在（`ERROR 1146`） |
| 容器状态 | finflow-app / finflow-web 均 healthy |
| `/tmp` 残留 | 无 |

**判据**：线上 flyway 34 < 本地最高 35 → 部署必要，且 V35 是唯一未上线内容。

---

## 三、交付物取证（构建侧）

### 后端 jar（隔离构建，`git archive HEAD` → ASCII 路径 → `mvn.cmd package`）

- MD5：**`c9b3f9201e5463516396d2a9cdcf8d32`**，84,120,182 字节
- jar 内取证 **16/16 PASS**：

| 检查项 | 结果 |
| --- | --- |
| `V35__account_preference.sql` 在包内 + 含建表语句与唯一键 | ✅ |
| `preference/` 四类（Controller/Request/Response/Service） | ✅ |
| `AccountPreference` 实体 + `AccountPreferenceMapper` | ✅ |
| `KingdeeSdkConfig.class` / `KingdeeSdkClient.class`（REAL 模式依赖） | ✅ |
| `BankDataStatus.class` 含 `SUC0000`（前序修复未回退） | ✅ |
| `BOOT-INF/lib` 条目 85（`.jar` 84）含 dlink-sdk / isec / k3cloud-webapi-sdk | ✅ |

> 口径澄清：历史报告的「lib 85」是 `BOOT-INF/lib/` **全部 zip 条目数**（含目录项），`.jar` 文件是 84 个。差异 1 不是依赖缺失。

### 前端 dist（CI 构件）

- `web-dist.tar.gz` MD5：`4015908815c0a3b670b422a69a494c28`
- 首页 bundle：**`assets/index-C6Ap8Y6j.js`**，页面 chunk `BankDataQueryPage-DZFUsYtR.js`（77.64 kB）
- **可复现性验证**：本地 `vite build` 产物与 CI 构件 chunk hash **完全一致** → 构建可复现

**三方 MD5 一致**：本地 = 上传后远端 = ECS 落位。

---

## 四、执行

1. 独立备份 `app.jar.bak-20260918-v35-pre`（**备份早于替换**，MD5 复核 = `34d4f7b4…`）
2. 备份 web-dist 为 `web-dist.bak-20260918-v35-pre.tar.gz`
3. 替换 jar → web-dist **原地解压**（未 mv 目录）
4. `docker compose up -d --build app`（jar 走 Dockerfile COPY，必须 rebuild）
5. health 轮询：t+10s `starting` → **t+20s `healthy`**
6. V35 不新增 env 变量（偏好走 DB），无需改 compose / `.env`

---

## 五、验收证据

### 5.1 三层门禁（全绿）

| 层 | 检查 | 结果 |
| --- | --- | --- |
| ① 服务器内 | `curl localhost/` 引用 bundle | `assets/index-C6Ap8Y6j.js` ✅ |
| ② 公网 | `http://101.200.72.87/` | 200 ✅ |
| ② 公网 | `/api/health` | 401（需认证，符合预期）✅ |
| ③ 浏览器级 | Edge headless dump DOM | `#root` 渲染 3,939 字符 / 48 元素 / 登录页 2 input + 1 button，**无白屏** ✅ |

### 5.2 容器内取证（证明跑的是 V35）

`V35 migration: True` ｜ `AccountPreferenceController/Service/.class: True` ｜ `KingdeeSdkConfig.class: True` ｜ `lib entries: 85`

### 5.3 DB 迁移

启动日志：
```
Successfully validated 35 migrations
Current version of schema `finflow`: 34
Migrating schema `finflow` to version "35 - account preference"
Successfully applied 1 migration to schema `finflow`, now at version v35
Started FinanceSystemApplication in 9.491 seconds
```

`flyway_schema_history`：version **35 / account preference / success=1**（10:46:28）

迁移效果实测：`account_preference` 表存在，字段 `id/user_id/scope_key/payload/created_at/updated_at`，唯一键 `uk_account_preference_user_scope(user_id, scope_key)` 就位。

### 5.4 API 实测（服务器内，admin 登录）

| 场景 | 结果 |
| --- | --- |
| 首次读取（从未保存） | `code=0`，`payload:null`（**200 而非 404**，符合设计）✅ |
| 写入 → 回读 | payload 一致 ✅ |
| 跨 scope 隔离 | 写 `bankdata.balances` 后 `bankdata.statements` 仍为 `payload:null` ✅ |
| 超长 scope（200 字符） | `400` + `{"code":400,"message":"scope 不合法：仅允许字母/数字/点/下划线/短横，长度 1-128"}` ✅ |
| 含空格 scope | 同上 400 业务信封 ✅ |
| 非法 payload（`not-json`） | `400` + `payload 必须是合法 JSON` ✅ |

### 5.5 真实数据端到端点击取证（Playwright + Chromium，公网环境）

零控制台错误、零页面异常。

**余额查询页**：th=7 ｜ 27 行（7 分组行 + 20 数据行）｜ 筛选钮 6
- 表头：银行 / 账号 / 截止时间 / 币种 / 可用余额 / 公司主体 / 详情
- 状态栏：「本页显示 20 / 20 行 · **本页可见小计 ¥ 46,768,966.55**」+ 完整口径说明
- 工具栏「**全量 55 行**」（接口未给金额聚合 → 只报行数，符合口径②不造假）
- 「仅本页排序」标注**常驻** ✅
- 分组行：「北京雪云锐创科技有限公司长沙分公司 3 个账户 · 可用余额合计 ¥ 99,348.57 · 最近截止 2026-09-18 06:35」
- 点击「账号」表头 → 排序生效（带序表头 = 账号）✅

**流水查询页**：th=13 ｜ 20 行 ｜ 筛选钮 11
- 状态栏：「本页显示 20 / 20 行 · **本页金额净额（贷−借）¥ 9,815.97**」
- 工具栏「全量 102 行」
- 列头筛选浮层可正常打开 ✅

### 5.6 口径③（服务端账号级偏好）实证 —— 本轮最关键的验证

以可证伪方式验证「不是 localStorage」：

1. 打开列设置面板：14 列（7 开 / 7 关），必需列（账号、详情）标注正确
2. 勾选「联机余额」→ th **7 → 8** 立即生效
3. **刷新页面 + 重新查询 → th 仍为 8，表头仍含「联机余额」**
4. 独立 token 直查服务端：`bankdata.balances` payload **803 字符**、`bankdata.statements` payload **924 字符**
5. 快照内含 `on/order/w/sort/filters/density/frozen/views` 完整结构

**同时排除一个风险**：列设置浮层由 React 渲染成空 div、内容由内核写 `innerHTML`。
实测开关前后 `innerHTML` 长度恒为 7,580 → **React 重渲染不会清空内核内容**。

验证后已把偏好表清空，账号回到 `payload=null` 全新态（清掉的 3 条均为本次自动化测试产物）。

---

## 六、回滚弹药

| 项 | 位置 | MD5 / 大小 |
| --- | --- | --- |
| 上一版 jar | `/opt/finflow/app.jar.bak-20260918-v35-pre` | `34d4f7b4d4d2156613bf162460d1c97d` |
| 上一版 web-dist | `/opt/finflow/web-dist.bak-20260918-v35-pre.tar.gz` | 1,038,245 字节 |
| 本版 web-dist（留存） | `/opt/finflow/web-dist-release-20260918-v35.tar.gz` | 538,019 字节 |

**回滚步骤**：
```bash
cd /opt/finflow
cp app.jar.bak-20260918-v35-pre app.jar
tar -xzf web-dist.bak-20260918-v35-pre.tar.gz -C web-dist
docker compose up -d --build app
```

⚠️ **回滚 jar 不会撤销 Flyway 迁移**。V35 建了 `account_preference` 表，回滚 jar 后该表会留着（旧代码不认识它，无副作用）。
如需彻底回退：
```sql
DELETE FROM finflow.flyway_schema_history WHERE version = '35';
DROP TABLE finflow.account_preference;
```

---

## 七、观察项（非阻塞）

1. ~~**web-dist 原地解压导致 chunk 累积**~~ → **已修复，见 §九（同日补充）**。实测比估计严重得多：
   累积 **121 个**历史文件（占六成体积），而 `index.html` 无 `Cache-Control` 才是「看起来像没更新」的真正根因。
2. **401 不具备端点判别力**：`SecurityConfig` 为 `.anyRequest().authenticated()`，Spring Security 在路由前拦截，
   任意路径（含瞎编的 `/api/definitely-not-real-xyz`）未带 token 均返回 401。判端点注册必须带 token 打对照组
   （真实端点 200/400/405；瞎编路径 500）。已修正 `finflow-deploy` 技能中「401=端点已注册」的错误表述。
3. **URL 编码斜杠 `%2F` 返回 Tomcat 原生 HTML 400**（非业务信封）。因前端 scope 是硬编码常量，不可达，仅记录。
4. **`updated_at` 精度**：写入响应回显内存值（带纳秒），回读来自 DB 截断到秒。不影响前端逻辑（前端不消费该字段）。
5. **迁移注释示例不精确**：`V35__account_preference.sql` 注释举例 `grid.balance / grid.statements`，
   实际前端用 `bankdata.balances / bankdata.statements`。迁移已应用**不可改**（Flyway checksum），
   属注释级瑕疵，不影响功能（服务端按设计只存不解释 scope）。
6. **页面行为**：结果区在点「查询」前是空态占位，表格内核此时**尚未挂载**（设计如此，非缺陷）。

---

## 八、下一步

1. ~~**P1**：`index.html` 补 `Cache-Control: no-cache` + 清理累积历史 chunk（观察项 1）~~ → **已完成，见 §九**
2. **P2**：FIX-002 剩余项 —— CITIC/金蝶 mock 开关清理、`BankDataAdapterRegistry.realAdaptersEnabled` 死字段
3. 用户以真实账号登录，确认列设置/命名视图/冻结列的实际手感

---

## 九、补充修复（2026-09-18 同日）：index.html 缓存头 + 历史 chunk 清理

### 9.1 问题定性与真实根因

用户提出「服务器上还不是最新版本」。上一轮归因为「缓存放大的错觉」，本轮把机制钉死 ——
**这是真实缺陷，不是错觉**：

| 环节 | 修复前状态 |
| --- | --- |
| `index.html` 缓存策略 | **无任何 `Cache-Control`**，只有 ETag / Last-Modified → 浏览器只能启发式缓存（heuristic caching，约取 `Last-Modified` 距今 × 10%） |
| `/assets/*` 缓存策略 | `public, max-age=31536000, immutable`（设计正确，哈希文件名本就该长缓存） |
| `web-dist/assets/` 文件数 | **181 个**（当前版本只需 **60 个**）→ 历史累积 **121 个 / 3.0 MB**，占六成体积 |

**故障链（三环缺一不可）**：
旧 `index.html` 被启发式缓存 → 它引用的**旧 chunk 全都还在服务器上** → 旧界面能完整正常打开 →
用户看到的确实是「没更新」的界面。

因此**只清 chunk、或只加缓存头，都堵不住**，必须同时做。

### 9.2 修复内容

**A. nginx 缓存头**（宿主 `/opt/finflow/nginx-app-locations.conf`，bind 到容器 `/etc/nginx/app-locations.inc`）

```nginx
# index.html 必须禁缓存。它是唯一「文件名不带哈希」的入口，引用的是带哈希的 chunk 名。
# 原先无 Cache-Control，浏览器只能启发式缓存 → 用户长期拿到旧 index → 引用的旧 chunk 随版本清理后 404。
# SPA 回退（try_files ... /index.html）会内部重定向到本 location，从而命中同一套头。
location = /index.html {
    add_header Cache-Control "no-cache, no-store, must-revalidate";
    add_header Pragma "no-cache";
    try_files $uri =404;
}
```

`/assets/` 的 immutable **保持不动**。装配顺序：改文件 → `nginx -t` 语法预检（此时线上仍跑旧配置，零风险）
→ 通过后 `nginx -s reload`。

**B. 原地清理 121 个历史 chunk**

以本次 release 包（`web-dist-release-20260918-v35.tar.gz`，60 个文件）为白名单，`comm` 求差集后小批量删除。

- **未 `mv` 目录**：`web-dist` 是 bind mount 到容器 `/usr/share/nginx/html` 的，`mv` 后容器内挂载点
  仍指向旧 inode（`mkdir` 重建同理）——这条是原生坑，已写进技能
- 删除前设硬断言：**白名单必须 ⊆ 现盘**，否则中止（防止基线不一致时误删）

### 9.3 验收证据

**缓存头（6 条路径实测）**

| 路径 | Cache-Control | 结果 |
| --- | --- | --- |
| `/index.html` | `no-cache, no-store, must-revalidate` + `Pragma: no-cache` | ✅ |
| `/`（SPA 回退） | 同上 | ✅ |
| `/dashboard`（SPA 深链） | 同上 | ✅ |
| `/bank-data/query`（SPA 深链） | 同上 | ✅ |
| `/assets/index-C6Ap8Y6j.js` | `public, max-age=31536000, immutable`（未误伤） | ✅ |
| `/assets/index-BMzzjG0L.css` | 同上 | ✅ |

> **SPA 回退带头是重点验证项**：`try_files ... /index.html` 属内部重定向、会重新匹配 location，
> 理论上能命中新 location —— 但那是推理，实测 4 条路径全带头才算数。

**chunk 清理**

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| `assets/` 文件数 | 181 | **60** |
| `assets/` 占用 | 5.1 MB | **1.8 MB** |
| 删除结果 | — | 121 成功 / 0 失败 |
| 白名单完整性 | — | **丢失 0 个** |

**关键验证：60 个文件逐个 HTTP 探测** —— 懒加载 chunk 只在访问对应路由时才被请求，
漏一个要到用户点进那个页面才会炸：

```
白名单 60 个逐个 curl        → 200 = 60，非 200 = 0
反证（旧 chunk 确已清除）    → BankDataQueryPage-D0J3uWt5.js = 404、AiSettingsPage-BfwgYULk.js = 404
                              index-BBHv43Q0.js = 200（它本就在当前版本白名单内，是懒加载块，不是旧文件）
SPA 路由                     → / 、/dashboard 、/bank-data/query 全 200
```

**浏览器级复验**：Edge headless → `#root` 渲染 3,939 字符 / 48 元素 / 登录页正常，**无白屏**。

### 9.4 回滚弹药（本次新增）

| 项 | 位置 | 大小 |
| --- | --- | --- |
| 清理前完整 web-dist（181 文件，可整体回滚） | `/opt/finflow/web-dist.bak-20260918-chunkclean.tar.gz` | 1,576,168 字节 |
| 修复前 nginx 配置 | `/opt/finflow/nginx-app-locations.conf.bak-20260918-chunkclean` | 1,053 字节 |

```bash
cd /opt/finflow
cp nginx-app-locations.conf.bak-20260918-chunkclean nginx-app-locations.conf
docker compose exec -T nginx nginx -s reload < /dev/null
tar -xzf web-dist.bak-20260918-chunkclean.tar.gz -C .
```

### 9.5 治本措施（防止下版复发）

**清理只是治标** —— 只要部署流程还是「原地解压」，下一版又会攒回来。已把根治写进 `finflow-deploy` 技能：

- §4-3 部署步骤改为「**先用新包清单做白名单清掉旧 chunk → 再原地解压**」，含「白名单 ⊆ 现盘」断言
- 三层门禁新增第 4 条**缓存头检查**：`index.html` 必须 no-cache、`/assets/` 必须 immutable、
  SPA 回退路径需实测带头、白名单文件需逐个 curl 全 200
- 坑清单新增 5 条：chunk 累积机制、`docker compose exec` 吃 stdin、nginx 装配顺序、
  `add_header` 覆盖语义、CRLF 清洗

### 9.6 遗留风险（需用户知晓）

**已缓存旧 index.html 的浏览器，在缓存过期前仍会引用已删除的 chunk → 404 白屏。** 缓解：

- 每次部署都会刷新 `index.html` 的 `Last-Modified`，而启发式缓存时长 ≈ `(now - Last-Modified) × 10%`，
  所以该窗口通常只有**几分钟到几十分钟**，不会长期存在
- 缓存过期后浏览器会 revalidate，`no-cache` 保证拿到最新 index
- **建议用户强刷一次**（Ctrl+F5）立即清除本地残留
