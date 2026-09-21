# W12b 部署报告（AI 制证失败语义修复 + 批次 A 重定向整理 / 文档校准）

- **日期**：2026-09-21 17:58–18:00（GMT+8）
- **提交**：`ca2d875`（AI 制证语义）→ `5c80e61`（/validation 重定向上移）→ `7482990`（PRD v0.8 文档校准）
- **CI**：run `35585937092` **5 job 全绿**（无新增迁移）
- **触发原因**：用户指令「部署最新版本到线上」。勘测发现线上已是 HEAD 的**代码**版本（V42 于 16:29 由并行会话部署），真正未上线的是工作区两批：AI 制证失败语义修复 + 批次 A；用户拍板**两批一起上**。

---

## 一、版本基线（部署前预检实测）

| 项 | 值 | 判据 |
|---|---|---|
| 线上 jar | `fcb987189050d232d73a0565da6eb129`（102,484,814 B） | = V42 报告记录值 ⇒ **无影子部署** |
| 线上入口 | `assets/index-iwxpHAT7.js` / 70 assets | 同上 |
| flyway | `Validated 41 migrations` → `Current version: 42` | = V42 |
| 容器 | finflow-app healthy / finflow-web up 2 weeks | |

## 二、本次上线内容

### 1. AI 制证失败语义修复（`ca2d875`，后端 3 + 测试 2 + 前端 1）

| 改动 | 前 | 后 |
|---|---|---|
| AI 调用失败时的行级状态（DRAFT 模式） | `DRAFT_CREATED` + 复核意见写「AI 建议不可用」——**调用方看到「成功」，实际无 AI 内容** | 行级 **`FAILED`**；失败原因写入 `statement_record.review_comment`（`AI 制证失败：…`）+ 记 `AI_VOUCHER_DRAFT` **失败审计**；标准流水保持 `PENDING` ⇒ 凭证中心「待复核」仍看得到该流水，不会凭空消失 |
| 制证阶段 max_tokens | 固定注入 `2048` | 传 `null` ⇒ 网关**仅在调用方显式传值时才发送** `max_tokens`，交由供应商按模型默认上限处理，避免固定预算截断中文 JSON。显式传值的调用（如分类 4096）不受影响 |
| 前端文案 | 「草稿已生成的行可在下方凭证列表…」 | 「**成功生成的**草稿行可在下方凭证列表…」（区分成功与失败行） |

### 2. 批次 A：`/validation` 重定向上移（`5c80e61`）

`ValidationPage.tsx` 原导出一个只做 `<Navigate>` 的空壳组件、路由却注册在 `App.tsx`（同一件事两处维护）。
现删除空壳导出，把 `/validation → /voucher-rules` 并入 `App.tsx` 的「已下线页面」重定向块，与其它历史路径同构。
`ValidationEmbedded`（规则中心「校验与入账映射」页签主体）不受影响。

**副产品证据**：assets 数 **70 → 69** —— 少掉的正是原 `ValidationPage` 的懒加载 chunk。

### 3. 文档校准批（`7482990`，PRD v0.8）

只校准事实、不改功能范围：§2.3 页面清单残留 6 个已下线/已合并页面、漏掉字典中心 / AI 状态 / AI 设置；
总数口径由「18 个业务页面」纠正为「**16 个菜单项 + 1 详情页**」，组名更正为「凭证与入账」。
另提交 `docs/dict-center-plan-20260921.md`（其前端批次已于 W11b 上线）。

## 三、交付物

| 项 | 值 |
|---|---|
| 后端 jar | MD5 `0aa72c0b8450e4f7ee9ac73a76a0c649`，102,484,796 B（隔离构建：`git archive HEAD backend`） |
| jar 内取证 | **24/24 PASS**（脚本 `tmp/w12b-jar-forensics.py`） |
| 前端 dist | MD5 `d5608d65270584b130ce5579c3411da2`，571,847 B，入口 `index-CoCcIVNd.js` / **69 assets**（CI 构件） |
| 迁移 | **无新增**（v42 保持） |

**取证明细**：V39/V40/V41/V42 四个迁移 SQL 在包；本次三个类在包；
`BankDataAccountingService.class` 含新文案「AI 制证失败：」且**已无旧降级文案**「AI 建议不可用：」；
网关仍含 `max_tokens` 键与 `LinkedHashMap`（条件写入的实现前提）；
前序修复未回退（FIX-009 `GL_VOUCHER` 落点 / FIX-006 `ensureBaseDataAudited` / V41 映射类 / V42 槽位类 / 金蝶 REAL 客户端）；
`BOOT-INF/lib` 全条目 **96** / `.jar` **95**；三套 vendor SDK（dlink / isec / k3cloud-webapi）齐备。

**前端构件探针**：新文案命中 `VoucherCenterPage-*.js`、旧文案 0 命中；`system/dicts`（W11b）与「可用余额合计已移除」（W11b 修复）均保持。

## 四、执行

1. **备份先于替换**：`app.jar.bak-20260921-w12b-pre`（MD5 复核 = `fcb98718…`，确认备份到的是旧 jar）+ `web-dist.bak-20260921-w12b-pre.tar.gz`（内含旧入口 `index-iwxpHAT7.js`）
2. **换 jar** → `0aa72c0b…`
3. **web-dist 原地处理**：新包清单 69（≥10 断言通过）→ 现盘 70，清新包外旧 chunk **65 个** → 原地解压 → 集合比对 **EXACT-MATCH**
4. **重建**：`docker compose up -d --build app`
5. **就绪判定**：`Started FinanceSystemApplication in 10.17 seconds`（**不只看容器 healthy**——Tomcat 未 ready 时 `/api/*` 会假 502）
6. flyway：`Validated 41 migrations` → `Current version: 42`（无迁移执行）
7. **上传 MD5 三方一致**：本地 = ECS `/tmp` = 实际使用（jar `0aa72c0b` / dist `d5608d65`）

## 五、验收证据

| 层 | 结果 |
|---|---|
| ① 服务器内 | 首页入口 `assets/index-CoCcIVNd.js`；白名单 **69/69 全 200** |
| ② 公网 | `http://101.200.72.87/` 200；`/api/actuator/health` 401（未带 token 正常） |
| ③ Edge headless 渲染 | root **49 元素 / 136 字符可见文本**，登录页正常渲染，**无白屏** |
| 缓存头 | `/index.html` → `no-cache, no-store, must-revalidate`；`/assets/` → `public, max-age=31536000, immutable` |
| 反证 | 上一版入口 `index-iwxpHAT7.js` **404**（证明入口确已切换） |
| 容器 | finflow-app Up (healthy)、finflow-web Up 2 weeks |

**说明**：本次**未做带 token 的 API 探测**——系统是单点登录，admin 登录会踢掉用户当前网页会话（V42 那次已发生过）。改动语义的验证落在「本地 423 用例 + CI 5 job + jar 字节码取证」三处，未依赖线上登录。

## 六、回滚弹药

| 文件 | 说明 |
|---|---|
| `/opt/finflow/app.jar.bak-20260921-w12b-pre` | 旧 jar（`fcb98718` = V42） |
| `/opt/finflow/app.jar.release-20260921-w12b` | 本次 jar 副本 |
| `/opt/finflow/web-dist.bak-20260921-w12b-pre.tar.gz` | 旧前端（入口 `index-iwxpHAT7.js`） |
| `/opt/finflow/web-dist-release-20260921-w12b.tar.gz` | 本次前端包副本 |

回滚：`cp app.jar.bak-20260921-w12b-pre app.jar && docker compose up -d --build app`；
前端 `tar -xzf web-dist.bak-20260921-w12b-pre.tar.gz -C /opt/finflow/web-dist`。
⚠️ 本次**无迁移**，回滚无数据库影响。

## 七、过程中的坑

1. **后端全量测试基线已从 386 涨到 423**（多个并行会话陆续加用例）⇒ 引用旧数字会误判「测试变少了」。
2. **jar 取证脚本写法坑**：`GL_VOUCHER` 是**字符串常量**，我却按**文件名**过滤去找它 ⇒ 假 FAIL。
   按字节内容搜时应限定候选类范围（本次限定 voucher/kingdee/statement 包共 139 个类），否则要全量扫 102 MB。
3. **`tail -c` 直接切日志会切出半个 UTF-8 汉字**，终端显示成乱码 ⇒ 读中文日志用 `tail -n`，别用 `tail -c`。

## 八、遗留与待办

1. **AI 制证修复尚未在真实链路验证**：需要有人在「流水查询」勾选一行跑一次 AI 制证，观察失败时凭证中心是否显示 `FAILED` 而非「草稿已生成」。失败场景不易主动触发，建议下一轮真实操作时留意。
2. **值映射表为空**（V42 遗留）：供应商 412 条 / 员工 354 行需财务整理后「批量粘贴导入」。
3. **图虫 19 条规则仍未入库**（`docs/pending-fixes.md` FIX-010 前置清单）。
4. 工作区已清空（本批三提交后无未提交改动）。
