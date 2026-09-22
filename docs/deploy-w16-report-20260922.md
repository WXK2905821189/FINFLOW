# W16 部署报告（账簿跟随组织修复 + 金蝶 P1 五件套）

- **日期**：2026-09-22 13:50 前后（GMT+8）
- **版本范围**：`34982c2`（W15 线上，jar `ca3e955a`）→ **`d3e685f`**（本次 HEAD）
- **CI**：run `35686021001` **5 job 全绿**（离线全量 443 用例 0 失败 / 2 skipped）
- **触发**：GL 凭证推金蝶报「必录维度未录入或不可用：银行账号」，插桩证明维度在报文里仍被拒 ⇒ 四点互证差分实验定案根因后修复上线

---

## 一、上线内容

### 1. 核心修复：GL 账簿跟随核算组织（`e39ebb5`）

**根因**：银行账号维度值档案（CN_BANKACNT）必须属于账簿对应的组织。app 把所有公司凭证都推到雪云账簿 400，图虫（org 410）的档案在账簿 400 语境下被金蝶判「不可用」。

**实验矩阵（四点互证，全部即推即删）**：

| 账簿 | 档案 | 结果 |
|---|---|---|
| 400 + 雪云档案 | ✅ 16094 | 对称互证 ✅ 16096（账簿 410 + 图虫档案） |
| 400 + 图虫档案 | ❌（400/410/411 全试） | 对称互证 ❌（账簿 410 + 雪云档案） |
| 账簿 410 + 兜底科目 2241.99 | ✅ 16100 | |

**修复**：`KingdeeOrgResolver.resolveAcctbookCode`——账簿号 = 组织号（`BD_AccountBook` 实查 9 组织 300/400/410/411/420/421/710/720/900 均有同号账簿且名称一一对应）；orgCode=null 回退 `kingdee.gl.acctbook-number`（400）。`glAcctbookNumber` 配置语义降级为「未识别组织的回退默认值」。

**两个关键反转（诊断期间推翻的旧认知）**：
1. 9-22 上午的「成功对照」16079-16083 全部无效——科目用的 1001（库存现金）**无必录维度**，金蝶不校验塞进去的维度值；
2. 报文形态差分（P1-P5：根级开关 / FEXCHANGERATE / 维度行方向）全部失败 ⇒ 形态差异不是原因。

### 2. 金蝶 P1 风险前置防护五件套（`9503aee`，并行会话合流）

| 件 | 内容 |
|---|---|
| P1-1 | 银行账户类凭证缺银行维度直接拦截（不再赌金蝶报错） |
| P1-2 | 单维度与 extraDimensions 撞槽位返回 400（原先后者静默覆盖） |
| P1-3 | 维度映射导入回查金蝶 FDocumentStatus（暂存/不存在 WARN） |
| P1-4 | GL_PUSHED 死锁登记 FIX-011（手工 SQL 修法） |
| P1-5 | 按 statementId ReentrantLock + 锁内重读 409（FAILED 放行） |

### 3. 配套测试（`d3e685f`）

builder×4（账簿跟随组织 / null 回退 / P1 拦截）/ mapping×3 / engine 集成×1（GL_PUSHED 二推 409）。

**诊断报告**：`docs/issue-diagnosis-20260922.md`；**风险前瞻**：`docs/kingdee-risk-predictions-20260922.md`（P0×5 待拍板 / P1×5 已实施 / P2×3 观察口径）。

---

## 二、执行与验收证据链

| 门禁 | 结果 |
|---|---|
| jar 隔离构建（git archive） | ✅ 无工作区混入 |
| jar 内取证（双向断言） | ✅ `resolveAcctbookCode` 字节级命中；V40-V42 迁移在；voucherrule/real 类在；95 lib + dlink/isec/k3cloud 全在（ALL PASS） |
| CI dist 构件 + 三方 MD5 | ✅ 本地 / CI / 容器内三方一致 `fae06f6dfcb2`（容器路径 `/app/app.jar`） |
| 备份先于替换 | ✅ w16-pre 备份旧 jar（MD5 `ca3e955a` = W15 线上包） |
| 容器 rebuild | ✅ healthy（t+20s）；`Started in 9.874s` |
| Flyway | ✅ 41 migrations validated（v42 无新增迁移，本轮纯 Java 改动） |
| 三层门禁 | ✅ 服务器内 bundle `index-BBeBG15i.js` ✓ / 公网 root 200 + health 401 ✓ |
| 缓存头 | ✅ index no-cache + bundle immutable |
| 白名单 | ✅ 69/69 assets 全 200 |
| **浏览器级白屏门禁**（本机 Edge headless DOM dump） | ✅ DOM 206,650 B；`#root` 挂载非空；2 个 `<input>`（含 `type="password"`）+「登录」文案 ⇒ 未登录正确渲染登录页，无白屏 |

**线上终态**：jar `fae06f6dfcb2` / 入口 `index-BBeBG15i.js`（不变，**前端免部署**）/ 69 assets / flyway v42。

## 三、清理

- 本机：探针脚本、`tmp/.askpass-ecs.sh`（askpass 凭据，用完即删）✅
- ECS：`/tmp/kd-probe*.py` 5 个诊断脚本、`/tmp/kd-sdk-extract/`、`/tmp/app.jar.new`、`/tmp/keep.txt` ✅
- 真实账套：差分探针凭证全部即推即删，无残留 ✅

## 四、回滚弹药

| 项 | 值 |
|---|---|
| 旧包备份 | ECS `app.jar.bak-20260922-w16-pre`（MD5 `ca3e955a` = W15 线上 jar） |
| dist | 未变更，无回滚需求（bundle 与 W15 相同） |
| 回滚动作 | 恢复备份 jar → 容器 rebuild → 门禁复验 |

## 五、待办与观察项

1. **【请用户验证】凭证中心对图虫流水（账户 9）复推 AI 制证/推送**：日志应显示 `acctbook=410 org=410`，金蝶不再报「必录维度未录入或不可用」。**这是本修复的最终闭环判据**。
2. 科目目录跨组织校验：`KingdeeAccountCatalogService.exists()` 只查编码存在性、不分组织，跨组织科目可能误放行——下一轮评估。
3. calibration 文档口径修订：`docs/kingdee-openapi/gl-voucher-calibration-20260921.md` 的「账套400+组织410 ✅」仅对无维度科目成立，有维度科目必须账簿=档案组织。
4. 置信度阈值界面入口：用户已拍板，未实现（下轮排期）。
5. 风险前瞻文档 P0×5 待用户拍板（`docs/kingdee-risk-predictions-20260922.md`）。
