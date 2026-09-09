# FINFLOW × 金蝶云星空 联调手册（9/11）

> 依据 2026-09-04 ~ 09-07 对官方演示环境（apiexp.open.kingdee.com）的探针实测结论整理。
> 所有字段均经真实调用校准，非文档推测。探针脚本留存于 `tmp/kd-probe/`，文档快照在 `docs/kingdee-openapi/openapi-docs/`。

## 一、前置条件检查单

| # | 项 | 检查方式 |
|---|---|---|
| 1 | 开通批复到手：ServerUrl + 测试账套 AcctID + AppID/AppSec + 授权用户 | 9/5 申请批复（形态：开放平台网关 or 私有云 K3Cloud 直连，均只需填 ServerUrl） |
| 2 | SDK jar 在构建机 `~/.m2` | `ls ~/.m2/repository/com/kingdee/k3cloud-webapi-sdk-java11/8.2.0/`（kingdee-sdk profile 按 file-exists 自动激活） |
| 3 | 后端全量测试绿 | `mvn -P '!citic-sdk' clean test`（170 个，金蝶 20） |
| 4 | FINFLOW 侧流水就绪 | 至少一条 EXPENSE + 一条 INCOME 流水，状态 PASSED + 复核 APPROVED |

## 二、环境变量清单（激活 real 网关）

```bash
KINGDEE_MOCK_MODE=false
KINGDEE_REAL_ENABLED=true
KINGDEE_SERVER_URL=<批复的 ServerUrl，如 https://apiexp.open.kingdee.com/k3cloud/>
KINGDEE_ACCT_ID=<账套 AcctID>
KINGDEE_APP_ID=<第三方应用 AppID>
KINGDEE_APP_SEC=<AppSec，仅环境变量注入，严禁入库>
KINGDEE_USER_NAME=<授权集成用户>
KINGDEE_LCID=2052
KINGDEE_ORG_NUMBER=<测试账套组织编码>
# 可选（默认值见 application.yml）：
KINGDEE_AUTO_CREATE_COUNTERPARTY=true   # 对手方自动建档
KINGDEE_AUTO_AUDIT=false                # 自动提审，业务拍板后再开
KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER=<我方账户 CN_BANKACNT 编码>
```

**fail-closed 三态**（任一不满足即降级，不会半开）：
`mock-mode=true` → Mock；`false` + `real-enabled≠true` → Unavailable(501)；两者满足 + SDK 在 → Real。

## 三、已校准字段速查表（演示环境实证）

| 字段 | 结论 | 实证方式 |
|---|---|---|
| `FDATE` / `FPAYORGID` / `FSETTLEORGID` / `FCURRENCYID`(PRE001) | 必填，组织编码 `100` 体系 | 报错驱动校准 9/04 |
| `FCONTACTUNIT` / `FRECTUNIT` + `TYPE` | **必填**；TYPE 必须跟随解析的 BD_* 类型（供应商→BD_Supplier，客户→BD_Customer） | 报错驱动 9/04 + 9/07 修正 |
| `FSETTLETYPEID` 结算方式 | `JSFS04_SYS`(电汇) 等编码有效 | ExecuteBillQuery 实查 BD_SETTLETYPE |
| `FACCOUNTID` 我方账号 | 收款单**必录**（银行业务结算方式）；编码 `1234567`(网银01) 合法 | 收款单保存成功 SKD00000016 |
| 单据体金额 | `FPAYAMOUNTFOR_E`/`FRECAMOUNTFOR_E` + `FPAYTOTALAMOUNTFOR`/`FRECTOTALAMOUNTFOR`，须 >0 | 报错驱动 |
| 对手方建档 | 最小 4 字段：FNumber/FName/FCreateOrgId/FUseOrgId；客户需加 `FCustTypeId`(KHLB001_SYS)；供应商无需类别 | 保存成功 FINGYS0001 (Id=121838) |
| 幂等信号 | 重复建档报「编码为X…组织内编码唯一」，FieldName=FNumber | 重复保存实测 |
| Submit/Audit | `excuteOperation(formId,"Submit"/"Audit",{"Numbers":[...]})`；**save→submit→audit 全链路已闭环**（SKD00000016 状态 C=已审核） | 9/07 实测 |
| 回滚 | **UnAudit → Delete 即可**（收款单无 CancelSubmit 接口，UnAudit 后可直接删） | 9/07 实测（删除确认） |

## 四、联调用例清单（按序执行）

| # | 用例 | 操作 | 预期 |
|---|---|---|---|
| T0 | 连通性 | 管理员账号经 SDK ExecuteBillQuery 查 BD_Customer Limit 1 | 返回数据行 |
| T1 | **收款单保存（核心）** | FINFLOW 推送一条 INCOME 流水（含 FACCOUNTID） | PUSHED，回写 voucher_no |
| T2 | **付款单保存（唯一遗留验证点）** | 推送一条 EXPENSE 流水（含 FACCOUNTID） | PUSHED。⚠️ 演示环境此路径撞缺列缺陷，真实环境若报「列名 XX 无效」请先比对 SQL 环境，不是 payload 问题 |
| T3 | 提审链路 | T1/T2 产出单据 Submit → Audit | 均 IsSuccess=true；状态 C |
| T4 | 对手方自动建档 | 推送带金蝶不存在的对手方名称的流水 | 自动建档 + 保存成功；FNumber=前缀+SHA256 |
| T5 | 幂等复跑 | 同名对手方再推一条 | 建档命中「已存在」复用，不报错 |
| T6 | 回滚 | 对 T3 单据 UnAudit → Delete | 均 IsSuccess=true，查询返回空 |
| T7 | 端到端幂等 | FINFLOW 对同一条流水重复调 voucher-push | 第二次被 PROCESSING/已推送状态机拦截，不产生重复单据 |
| T8 | 审核策略拍板 | 业务确认金蝶审批流是否绕过 | 拍板 `KINGDEE_AUTO_AUDIT` 开/关 |

## 五、已知风险与边界

1. **apiexp 演示环境缺陷不迁移**：付款单带 FACCOUNTID 撞「列名 CREDITTYPE 无效」是演示库 schema 问题（二分定位确认）；真实测试环境预期正常，若复现按 T2 处理。
2. **apiexp 网关常态抖动**：504/503 空响应频发，脚本须带间隔重试（3 轮 30s 实测足够）；联调脚本不要把单次失败判死。
3. **基础资料差异**：真实账套的客户类别/结算方式/账户编码与演示环境（KHLB001_SYS / JSFS04_SYS / 1234567）不同，用 ExecuteBillQuery 实查后改环境变量，**不要改代码默认值**。
4. **审核语义**：FINFLOW 侧复核（role 1/3）≠ 金蝶侧审核。T8 拍板前保持 auto-audit 关闭，推送语义 = 金蝶生成草稿单据。

## 六、放行标准（Definition of Done）

- T0~T7 全绿；
- T8 拍板并固化配置；
- `mvn -P '!citic-sdk' clean test` 170+ 全绿；
- 攒批 commit（金蝶骨架 + 文档快照 + 联调手册一并入库；`openapi-storage-state.json`/`kd-tk.txt` 已 gitignore）。

## 七、第三方应用授权登录配置（9/9 补，接口开通后必做）

「第三方授权登录」= 金蝶侧注册受信任调用方，我们 SDK 走 `IdentifyInfo`(acctID+userName+appId+appSecret) 自动完成认证，**代码零改动**。

金蝶侧管理员操作清单：

1. 管理员进入「第三方应用」管理（BOS 设计器 / 管理中心 → 第三方应用），新增应用：生成 AppID + AppSec，认证方式选第三方应用授权登录；
2. 绑定集成用户并授权：对 5 个业务对象（BD_Customer/BD_Supplier/CN_BANKACNT/AP_PAYBILL/AR_RECEIVEBILL）授予 Save/Submit/Audit/UnAudit/Delete/ExecuteBillQuery 权限；
3. 如有 IP 白名单，加入我方出口公网 IP；
4. 交付 5 项凭据：ServerUrl / AcctID / AppID / AppSec / 授权用户名（AppSec 私密渠道传递，仅环境变量注入）。

验收：凭据填入 runbook 第二节环境变量 → 跑 `tmp/kd-probe/` T0 探针（ExecuteBillQuery 查 BD_Customer Limit 1）返回数据行。典型失败：「未授权第三方应用 / 应用未启用 / 用户未绑定」分别对应上面 1/1/2 步遗漏。

### 凭据到位状态（9/9）

| 凭据 | 状态 | 值/说明 |
|---|---|---|
| AcctID（数据中心ID） | ✅ 到手 | `20210801002010962` |
| AppID | ✅ 到手 | `241784_4Yfq1xFo4JC5RfSJTc0qUbSqVtX9XpNG`（应用名：Finflow） |
| 授权用户名 | ✅ 到手 | `王一霏`（LCID=2052） |
| AppSec | ✅ 到手（9/9） | 原文仅环境变量注入，严禁入库/落文档 |
| ServerUrl | ✅ 到手 | `https://xyrc.ik3cloud.com/k3cloud/`（https 200，http 301 → https） |
| **IP 白名单** | ⚠️ **当前唯一阻塞** | LoginByAppSecret 实测返回「当前IP:120.246.107.198 白名单失败」——证明凭据全对；需管理员加白：`120.246.107.198`（本地联调出口）+ `101.200.72.87`（ECS 生产出口） |
| 组织编码 / CN_BANKACNT 账户编码 | ⚠️ 已实查，待业务拍板 | 见下表 |

> 授权入口确认（9/9 实测）：第三方应用登录走 `AuthService.LoginByAppSecret.common.kdsvc`（parameters: [acctID, username, appId, appSecret, lcid 字符串]）；`ValidateUser` 是账号密码入口，传 AppSec 报「输入字符串的格式不正确」。

### T0 验收结果（9/9 10:47，全绿 ✅）

- LoginByAppSecret：`LoginResultType:1`，用户王一霏，主体「北京雪云锐创科技有限公司」，金蝶版本 9.0.553.10
- ExecuteBillQuery BD_Customer：返回真实数据 ✅

**真实账套基础资料实查（与演示环境比对）：**

| 项 | 配置默认值（演示环境校准） | 真实账套实测 | 结论 |
|---|---|---|---|
| 币别 | PRE001 人民币 | PRE001 人民币 | ✅ 不改 |
| 结算方式 | JSFS04_SYS 电汇 | JSFS04_SYS 电汇（01现金/02现金支票/03转账支票/04电汇…） | ✅ 不改 |
| 客户类别 | KHLB001_SYS | KHLB001_SYS（所有存量客户均此类别） | ✅ 不改 |
| 组织 | 100 | **400**=北京雪云锐创科技有限公司（登录主体）；另有 100 即时设计、300 北京即设、500 天津雪云、600 南京璞锐雪、700 河北分公司等 | ✅ 9/9 拍板：取 400（测试阶段占位，真实联通时改环境变量即可） |
| 我方银行账户 | 1234567 网银01 | FINFLOW 侧仍为测试账户，无真实联通 | ✅ 9/9 拍板：占位映射 `11050160520009100036`（400 主体名下首个账户），逐账户映射后置 |
| 5 业务对象权限 | — | AP_PAYBILL/AR_RECEIVEBILL/BD_Customer/BD_Supplier/CN_BANKACNT ExecuteBillQuery 全部通过 | ✅ 接口权限范围已放开 |

**真实环境环境变量配置（待两处拍板后启用）：**

```bash
KINGDEE_MOCK_MODE=false
KINGDEE_REAL_ENABLED=true
KINGDEE_SERVER_URL=https://xyrc.ik3cloud.com/k3cloud/
KINGDEE_ACCT_ID=20210801002010962
KINGDEE_APP_ID=241784_4Yfq1xFo4JC5RfSJTc0qUbSqVtX9XpNG
KINGDEE_APP_SEC=<环境变量注入，严禁入库>
KINGDEE_USER_NAME=王一霏
KINGDEE_LCID=2052
KINGDEE_ORG_NUMBER=400          # 9/9 拍板：登录主体，真实联通时可改
KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER=11050160520009100036  # 占位映射，逐账户映射后置
```

> **边界约定（9/9 用户拍板）**：金蝶内现有真实单据/基础资料一律不碰；FINFLOW 测试阶段不执行 T1/T2 推单（不在真实账套建单）。联通验证以 T0（授权登录 + 5 业务对象查询）为准。T1/T2 留待真实联通时以「建测试单→立即 UnAudit→Delete 回滚」方式验证。

> 给对方的一句话：请提供①该第三方应用的 AppSec 原文（签名是按它派生的，我们可自行生成签名，无需每次向贵方申请）②API 访问的 ServerUrl 真实地址。

## 八、故障速查

| 症状 | 处置 |
|---|---|
| 推送返回 UNAVAILABLE | 三态检查：mock-mode / real-enabled / SDK jar 是否在 ~/.m2 |
| 推送返回 FAILED 带「Kingdee real gateway requires …」 | 对应凭据环境变量未填 |
| FAILED 带「Counterparty not found … auto-provision is off」 | 自动建档被关或建档报错，查 message 尾部 |
| SDK 抛 JsonSyntaxException / 504 | 网关抖动，间隔重试 |
| 付款单报「列名 XX 无效」 | 见风险 1，先查真实库 schema，勿改 payload |
