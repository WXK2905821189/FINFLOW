# V42 部署报告（2026-09-21）

> 核算维度能力建设上线：槽位/值映射可配置（V42 两张表）+ 多维度注入 + 图虫系组织扩展。

## 一、版本基线

| 项 | 值 |
|---|---|
| 代码 | `b19d11f`（含 `dffed70` / `c516248` / `ee3bc34` / `53af5e9`） |
| 后端 jar | `fcb987189050d232d73a0565da6eb129`（102,484,814 字节，含 vendor SDK，95 个 lib） |
| 前端 | 入口 `assets/index-iwxpHAT7.js`，70 assets（CI 构件，run `35577835959`） |
| 迁移 | **v42 已落库**：`Migrating schema finflow to version "42 - kingdee dimension slot and mapping"` → `now at version v42` |
| 部署方式 | jar 替换 + 前端原地解压（web-dist 为 bind mount，未做 mv/mkdir） |

## 二、本次上线内容

1. **V42 两张表**：`kingdee_dimension_slot`（15 条 seed：14 个维度 + 业务线）、`kingdee_dimension_mapping`（值映射，当前空表待导入）
2. **规则中心 › 核算维度配置**（前端第三页签）：槽位配置 / 值映射 / 批量粘贴导入
3. **多维度注入**：分录可带多个核算维度；未就绪维度推送前拒绝并给出补齐指引
4. **3 个匹配算子**：`IN_SUPPLIER_LIST` / `IN_EMPLOYEE_LIST` / `IN_CUSTOMER_MAPPING`
5. **图虫系组织扩展**：映脉 411 / 浙江北分 421 / 浙江 420 / 图虫 410

## 三、三层门禁（部署后实测）

| 层 | 项 | 结果 |
|---|---|---|
| ① 服务器内 | `/` | 200 |
| | `/actuator/health` | 200 |
| | `/api/statements`（未带 token） | 401 |
| ② 公网 | `http://101.200.72.87/` | 200 |
| ③ 前端 | 入口 `index-iwxpHAT7.js` | 200，`Cache-Control: public, max-age=31536000, immutable` |
| | assets 全量白名单 | **70 / 70 全 200**，异常 0 |
| | 旧入口反证 `index-B3GSO8DZ.js` | **404**（证明入口确已切换） |
| 容器 | `finflow-app` | Up（healthy），Tomcat 8080 + Application 启动完成 |

**前端功能取证**（chunk 关键字，证明新页面真在线上包内）：
`核算维度配置` / `槽位配置` / `值映射` / `批量粘贴导入` / `弹性域槽位` —— 全部命中
`assets/CategoryRulesPage-8XltWscv.js`。

## 四、端点注册核验（带 token 的对照组才有判别力）

未带 token 时所有路径一律 401（真/假端点无差别）⇒ 必须带 token 打对照组：

| 路径 | 状态 | 判定 |
|---|---|---|
| `GET /api/kingdee/dimension-slots` | **200** | 真端点；返回 **15 条槽位**（V42 表真实数据） |
| `GET /api/kingdee/dimension-mappings` | **200** | 真端点；`data: []`（值映射待财务导入，符合预期） |
| `GET /api/not-exist-endpoint` | **500** | 瞎编端点（对照组），判别力成立 |

槽位返回抽查：`BANK_ACCOUNT → FF100002`、`PROJECT → FF100003`、`INVESTOR → FF100004`、
`SUPPLIER → FFLEX4`、`DEPARTMENT → FFLEX5`、`CONTRACT → null`（待确认合同号槽位）。

## 五、回滚弹药

| 文件 | 说明 |
|---|---|
| `/opt/finflow/app.jar.bak-20260921-v42-pre` | 旧 jar（MD5 `941441a1` = W12 版本） |
| `/opt/finflow/web-dist.bak-20260921-v42-pre.tar.gz` | 旧前端（入口 `index-B3GSO8DZ.js`） |
| `/opt/finflow/web-dist-release-20260921-v42.tar.gz` | 本次发布包（持久化，便于快速重放） |

回滚方式：`cp app.jar.bak-20260921-v42-pre app.jar && docker compose up -d --build app`；
前端 `tar -xzf web-dist.bak-20260921-v42-pre.tar.gz -C /opt/finflow/web-dist`。
⚠️ 迁移不可回滚（V42 只新建两张表，回滚 jar 时保留表不影响旧代码运行）。

## 六、过程中的两个坑（已记入 skill）

1. **CI 前端 lint 拦下真缺陷**：`DimensionMappingPanel.tsx` 的 `openSlotCreate` 定义后未使用——
   根因不是多余代码，而是**槽位页签漏放了「新增」按钮**（功能缺口）。本地 `node_modules` 是空壳
   跑不了 eslint，这一点只能靠 CI 兜住。首次 CI run `35577590639` 因此失败，修后 `35577835959` 全绿。
2. **部署后 `/api` 502 是启动未就绪的假象**：容器起后约 10 秒内探测 `/api/*` 全 502（Tomcat 未
   ready），45 秒后复测全部正常。**判定部署成功不能只看容器状态，要等 `Started ...Application`**。

## 七、遗留与待办

- **值映射表为空**：供应商（412 条）/ 员工（354 行）等附件数据需财务整理成「来源值 + 编码」后，
  在「核算维度配置 › 值映射 › 批量粘贴导入」一次灌入；
- **业务线（项目）档案编码清单**待财务提供（用户已拍板用项目 ZDY0002 承载）；
- **合同号槽位**（ZDY0004）该账套未在凭证模板启用，待确认；
- 图虫侧 19 条规则**仍未入库**（能力已齐，见 `docs/pending-fixes.md` FIX-010 前置清单）；
- 本次登录探测用了 admin 账号（单点登录），**用户的网页会话已被踢，需重新登录**。
