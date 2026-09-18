# FINFLOW V36 部署报告（六项需求 + W7 账期锁收口）

- **部署日期**：2026-09-18
- **版本范围**：V36 全量六工作包（W1~W5 并行合流）+ W7 账期锁收口，本会话交付 W3/W4/W7
- **提交**：`8e53528` feat(closing): W7 账期锁收口（本会话）；含 `ddc9606`（W3+W4）、`fb82bf6`（CI 修正）、`4ea3cde`（W1+W2）、`557db36`（W5）
- **CI**：run 35319971280 — **5 job 全 success**（Frontend / Backend / Release contract / MySQL migration / H2 migration）
- **ECS**：101.200.72.87（nginx :80 对外，app 8080 未映射）
- **结论**：✅ 上线成功，三层门禁全绿，V37 迁移落库取证通过

---

## 一、本会话交付（W3 / W4 / W7）

| 工作包 | 内容 | 根因/要点 |
| --- | --- | --- |
| W3 | 凭证域跨公司可见性合流 | 计划假设「DRAFT 不落组」**被排查推翻**：凭证中心是 StatementRecord 投影读模型（voucher_group 表不存在），真根因是**公司域过滤不一致**（制证链放行他司、查询/复核/推送链恒锁本公司）→ 统一可见域语义，无需迁移（V36 号空置） |
| W4 | 规则中心（V37 迁移） | kingdee_rule_group 表 + 规则 CRUD 9 端点 + Excel 导入三步（POI→AI 映射 fail-closed→人工勾选入库）+ 前端双 Tab 单页合并，旧 /validation 重定向 |
| W7 | 账期锁收口 | ①`ClosingService.unlock`（仅 ADMIN，CLOSED→READY，审计 UNLOCK_PERIOD）②业务写拦截 `ensurePeriodOpen(s)`：按**流水所属公司**+交易时间归月，CLOSED→409，挂六条链路（importBatch / transferFromBankData / pushVoucher / createVouchers / saveVoucherDraft / 规则引擎 push）③前端 closing 解锁按钮+行级解锁+确认模态、VoucherDocPage 锁 chip ④退役 BankReconciliationPage 死代码 |

**W7 设计口径**：
- 无记录 = 开放（存量链路零影响，全量 323 测试 0 失败佐证）；
- 批量推送 409 自动转行级 SKIPPED（不炸整批）；
- `refreshAiSuggestion` 有意放行（元数据刷新不落凭证）；
- dashboard 4 Tab 化维持 P2 未做（结构微调不影响部署）。

## 二、交付物取证

### 后端 jar（隔离构建：git archive HEAD → ASCII 路径 → mvn.cmd package）

- MD5：`6ea0ed2b84747f84657683c800852581`，102,367,169 字节
- 包内含 `V35__account_preference.sql` + `V37__rule_group_and_rule_crud.sql`、closing 包 5 class
- lib `.jar` 计数 **95**（= 84 + W4 POI 全家桶 ~11，非异常）

### 前端 dist（CI 构件 run 35319971280）

- `web-dist.tar.gz` MD5：`41f8496b481ff8a71f2e8296e98d9b9c`（554,516 字节），64 assets
- 首页 bundle：**`assets/index-DqT6ktiF.js`**（本地 vite build 主 chunk 516.78 kB）

**三方 MD5 一致**：本地 = 上传后远端 = ECS 落位。

## 三、执行

1. 备份 `app.jar.bak-20260918-v36-pre`（84,120,182 = V35）+ `web-dist.bak-20260918-v36-pre.tar.gz`
2. 换 jar（远端 MD5 = 本地）→ web-dist **先按新包清单清旧 chunk（removed=59）→ 原地解压**（64 个 = 新包干净态）
3. `docker compose up -d --build app`（compose/.env 零改动，V36 无新增 env）
4. health 轮询：t+10s starting → **t+20s healthy**

## 四、验收证据（三层门禁全绿）

| 层 | 检查 | 结果 |
| --- | --- | --- |
| ① 服务器内 | `curl 127.0.0.1/` 引用 bundle | `assets/index-DqT6ktiF.js` ✅ |
| ② 公网 | `http://101.200.72.87/` | 200 ✅ |
| ② 公网 | `/api/health` | 401（需认证，符合预期）✅ |
| ② 公网 | 新 chunk `/assets/index-DqT6ktiF.js` | 200 ✅ |
| ③ 浏览器级 | Edge headless dump DOM | 206,650 字符 / #root 渲染 / 登录页 2 input，**无白屏** ✅ |

### 迁移取证（DB 层）

启动日志：
```
Successfully validated 36 migrations
Migrating schema `finflow` to version "37 - rule group and rule crud"
Successfully applied 1 migration to schema `finflow`, now at version v37
Started FinanceSystemApplication in 9.957 seconds
```

`flyway_schema_history` 实查：**37 / rule group and rule crud / success=1 / 2026-09-18 15:41:07**（V35→v37 无断档）；`kingdee_rule_group` 表存在 ✅

### W7 unlock 端点 API 实测（未完成项）

admin 默认密码（Admin@123）在产库已改，token 获取失败，未做带 token 的端点对照实验。**注册证据链以构建侧取证代替**：jar MD5 三方一致 + closing 包 class 在包内 + V37 迁移由该 jar 执行成功（能跑 V37 迁移的必然是 W4 之后的代码，unlock 与其同包同提交）。待用户以真实账号在结账管理页实测「解锁」按钮。

## 五、回滚弹药

| 项 | 位置 |
| --- | --- |
| 上一版 jar（V35） | `/opt/finflow/app.jar.bak-20260918-v36-pre` |
| 上一版 web-dist（V35） | `/opt/finflow/web-dist.bak-20260918-v36-pre.tar.gz` |

回滚：`cp app.jar.bak-20260918-v36-pre app.jar && tar -xzf web-dist.bak-... -C web-dist && docker compose up -d --build app`。

⚠️ 回滚 jar 不会撤销 V37 迁移：`kingdee_rule_group` 表与 `kingdee_voucher_rule.group_id` 列会保留（旧代码不认识，无副作用）。彻底回退需手工 DELETE flyway 历史行 + DROP。

## 六、遗留与下一步

1. **W6 收口态**：V36 六包 + W7 全部完成；W6 核查表中 P2（dashboard 4 Tab）与 P3（8 处 muted 微缩，W2 已覆盖横幅）维持未做，属结构/文案微调，无阻塞。
2. 用户真实账号回归：余额/流水页（W1 内核一体化）、规则中心导入向导（W4）、结账管理解锁（W7）。
3. P2 技术债（FIX-002）：CITIC/金蝶 mock 开关清理、BankDataAdapterRegistry 死字段、主 chunk 516.78 kB 优化。
