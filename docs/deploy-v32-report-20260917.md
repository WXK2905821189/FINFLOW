# FINFLOW V32 部署验收报告（2026-09-17）

**版本**：`7984891`（V32 账户软删除 + AI 公司主体归类 + LLM 解析诊断增强 + AI 页面收归超管）
**CI**：run 35174129975 全绿 · **部署目标**：ECS 101.200.72.87 · **部署时刻**：10:39-10:44

## 一、部署前基线核对（关键）

| 项 | 勘察结果 |
|---|---|
| 线上 jar | `415b7a16dd0753972545981b00e9fd1d`（10:17 部署的"金蝶 real 版"） |
| 线上 flyway | **v31**（9/16 18:35）——**V32 未部署** |
| V32 提交时间 | `753bda0` 今日 **10:18**、类型修复 `7984891` **10:21**（均晚于 10:17 部署） |
| 前端 bundle | 线上 `index-C3uolF0g.js` vs CI 新构件 `index-D9yUFHNx.js`（需更新） |
| compose KINGDEE_* | ✅ 线上已含 9 行透传（19-28 行），无需改动 |
| .env 金蝶 real | ✅ 已配齐（xyrc.ik3cloud.com / 账套 20210801002010962 / 王一霏 / org 400） |

> 结论：线上缺 V32（jar 内 `V32__*.sql` 与 `SoftDelete` 类均不存在，已实测取证），需部署 HEAD。

## 二、本次交付内容（V32）

| # | 需求 | 落地 |
|---|------|------|
| 1 | 一账户软删除 | `bank_account.deleted TINYINT NOT NULL DEFAULT 0`（V32 迁移）；归档页新增"移除账户"动作，物理 DELETE 被外键阻断且原始数据须留存 |
| 2 | AI 配置收归超管 | `ai:config(43)` 撤销 FINANCE_STAFF(2) 绑定，仅 ADMIN(1) 保留；成员仍可经 `ai:use(42)` 使用 AI 能力 |
| 3 | AI 公司主体归类 | archive.tsx `AiCompanyApplyRow` 类型导入修复（CI tsc TS2304，`7984891`） |
| 4 | LLM 解析诊断增强 | 解析失败诊断上下文增强 |

## 三、交付物

| 项 | 值 |
|---|---|
| 后端 jar | `finance-system-backend-0.0.1-SNAPSHOT.jar`（84,023,531 B） |
| **jar MD5** | **`d8c6dd0cc710b4e0340a0ce9aa0a9b12`**（本地 = 上传 = ECS 落位，三方一致） |
| 构建方式 | `git archive HEAD backend` → ASCII 隔离路径 → `mvn package -DskipTests -Djacoco.skip=true`（BUILD SUCCESS 22.25s） |
| **⚠️ 构建补丁** | **补入 untracked 的 `KingdeeSdkConfig.java` / `KingdeeSdkConfigTest.java`**——该文件是 9/17 金蝶 REAL 事故修复件（`KingdeeSdkClient` 的 `@Bean` 定义），未提交，`git archive` 会漏掉，遗漏将导致 REAL 模式首启 502 |
| jar 内取证 | V32 SQL ✅ / V31 SQL ✅ / `KingdeeSdkConfig.class` ✅ / 金蝶 real 全套 ✅ / **lib 85**（dlink-sdk+isec=中信、k3cloud-webapi-sdk=金蝶） |
| 前端 dist | CI run 35174129975 构件 `finflow-web-dist`，MD5 `cb8d75aef3b272cafa72b9faa26e7b39`（511,648 B） |
| 前端 bundle | `assets/index-D9yUFHNx.js`（线上旧为 `index-C3uolF0g.js`） |

## 四、部署动作

1. 备份当前 jar → `app.jar.bak-20260917-v32-pre`（MD5 `415b7a16`，前版金蝶 real 版）
2. scp 上传（/tmp 暂存）→ MD5 双向核验 → 替换 `/opt/finflow/app.jar`
3. 备份 web-dist（tar）→ **原地解压**（勿 mv，避免破坏 nginx 挂载）
4. 确认 citic-cert bind 源在位（`cert.properties`，compose L66）
5. `docker compose up -d --build app`（换 jar 必须 rebuild，铁律）
6. health 轮询 → t+20s healthy

## 五、部署验收（三层门禁 + 迁移实证）

| 验收项 | 结果 |
|---|---|
| **① 服务器内** | 首页引用 `index-D9yUFHNx.js`（新 chunk）✅ |
| **② 公网** | `http://101.200.72.87/` = **200**、`/api/health` = **401** ✅ |
| **③ 浏览器级** | Edge headless（`--virtual-time-budget=10000`）渲染登录页（`auth-page` / FINFLOW 品牌 / "让每一笔资金，清晰且可追溯"），root 非空、**无白屏** ✅ |
| 容器健康 | `finflow-app` **Up (healthy)**，t+20s 达成 ✅ |
| 启动日志 | `Successfully validated 32 migrations` → `Migrating to version "32"` → `Successfully applied 1 migration` → `Started in 9.537s`，**无 error/exception** ✅ |
| **DB 迁移** | flyway `version=32` `success=1` @ 10:40:03 ✅ |
| **迁移效果①** | `bank_account.deleted` = tinyint NOT NULL DEFAULT 0 ✅ |
| **迁移效果②** | `sys_role_permission` 中 `permission_id=43` **仅剩 role_id=1**（role2 已撤销）✅ |
| 金蝶 REAL 装配 | 启动无 `UnsatisfiedDependencyException`（`KingdeeSdkConfig` 的 @Bean 装配成功）✅ |
| 容器内 jar 取证 | V32 SQL ✅ / `KingdeeSdkConfig.class` ✅ / lib 85 ✅ |
| 端点探测 | health / kingdee-ping / ai-voucher / bank-accounts 全 **401**（已注册受保护）✅ |
| 功能轻验 | admin 登录 OK，账户列表返回 **12 个账户**（V32 软删除列未破坏查询）✅ |

## 六、回滚弹药

| 资产 | 位置 |
|---|---|
| 前一版 jar | `/opt/finflow/app.jar.bak-20260917-v32-pre`（`415b7a16`） |
| 前一版前端 | `/opt/finflow/web-dist.bak-20260917.tar.gz` |
| 更早 jar 链 | `bak-20260917-kdreal-pre`、`bak-20260917-draft-pre`、`bak-20260916-*`（保留） |

**回滚命令**：`cp app.jar.bak-20260917-v32-pre app.jar && tar -xzf web-dist.bak-20260917.tar.gz -C web-dist && docker compose up -d --build app`

> 注意：V32 迁移已执行（加列 + 删权限行），回滚 jar **不会**自动撤销迁移；如需完整回退需手工 `ALTER TABLE bank_account DROP COLUMN deleted` 并恢复 role2 的 43 权限（当前 v32 在 flyway 历史中标记 success，回退需删该行）。

## 七、遗留与后续

1. **`KingdeeSdkConfig.java` 仍未提交**（untracked）——建议全栈侧尽快提交，否则每次部署都需手工补文件，且 CI jar 不含它（CI 只产 web-dist，不产交付 jar）；
2. 页面级人工复核建议：账户归档页"移除账户"动作、AI 设置页权限（FINANCE_STAFF 应不可见）、AI 公司主体归类展示；
3. 部署脚本留档：`tmp/v32-build.sh`、`v32-deploy.sh`、`v32-verify.sh`、`v32-wrapup.sh`（含现场输出 txt）。
