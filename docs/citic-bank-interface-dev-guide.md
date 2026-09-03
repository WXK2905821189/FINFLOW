# 中信银企直联接口开发指导（CITIC dlink SDK v4.1.3）

> 2026-09-03 产出 · 输入源：`/d/中信银行sdk/`（9 月新到资料）精读 + 同源项目《中信银行银企直联接口说明书(集团客户)V6.0.0.1》docx 全文提取
> 用途：全栈工程师对话 P0-1（CITIC Adapter 四件套盘点）的核心输入，接真实白名单后按此实现
> 关联：`bank-connect-schedule-2026-09-03.md` · `v0.3-module-architecture.md`

---

## 0. TL;DR（先读这个）

- **接入形态定为 A：内嵌 SDK 直连**（银行 jar 打进 Spring Boot 应用，直接调 `send()`），与现有 `CiticBankSdkClient`"客户端 + 连接"抽象同构。形态 B（本地化前置机，独立进程 HTTP 到 `:6788`）只作 JDK17 兼容性兜底备选。
- **当前代码假设错了**：FINFLOW 的 `CiticBankProperties(baseUrl/appId)` + HTTP 网关假设 ≠ 真实形态。真实形态 = `CITICBANK.URL`（`…/DLink/DLServlet/Open`）+ 云证书（下载码 + 组织机构代码）+ 自定义 token。**P0-1 必须按本文档重构而非盘点占位**。
- 业务报文双层：**外层** `<action>DLGECOMM</action>` + `Base64(业务报文)`；**内层** 业务 XML 首元素 `<action>DLBALQRY/DLTRNALL/…</action>`。GBK 编码，标签**大小写敏感**。
- FINFLOW 只需要**查询类 4 个接口**：`DLBALQRY` 余额、`DLTRNALL` 流水明细（主）、`DLHBLQRY` 历史余额（对账用）、`DLEDDRSQ` 回单查询。动账类（DLINTTRN 等）v0.4 已砍，**不实现**。
- 开发前先确认 4 件事（见 §7）：IP+MAC 双白名单、token 规则、测试限频（查询 400 笔/h）、SDK jar 在 JDK17 下的兼容性。

---

## 1. 两种接入形态与选型

| 维度 | **A. 内嵌 SDK 直连**（demo + 05 文档）✅ 选型 | B. 本地化前置机（02 安装指南） |
|---|---|---|
| 形态 | 银行 jar（dlink-sdk-demo4customer/lib）打进 Java 应用 | 独立客户端程序，装在前置机/服务器 |
| 应用对接 | 直接调 SDK `send()`（见 §2） | 应用发 HTTP POST 到 `http://本机IP:6788` |
| 运行环境 | demo pom 标注 JDK8（05 文档 OpenJDK 1.8）⚠️ | 客户端 OpenJDK 17 |
| 网络 | 应用直连银行网关（`…/DLink/DLServlet/Open`） | 前置机连银行，应用只连本机 |
| 证书 | 云证书（下载码 + 组织机构代码下载，SDK 内管理） | 云证书/UKey，客户端界面管理 |

**选 A 理由**：与现有代码抽象同构、无额外进程部署、05 文档 V4.1.3（2025.11.21）为官方最新 SDK 对接说明。
**A 的唯一风险**：SDK 标注 JDK8，而 FINFLOW 是 Spring Boot 3（JDK17）。SDK 内 dom4j/slf4j 已重定位到 `citicbank_lib` 包（防冲突，好事），但**必须白名单到手前先做 jar-in-JDK17 冒烟**（§9 验收 4）。
**B 的兜底价值**：若 SDK 在 JDK17 下确有兼容问题，B 形态恰好是 JDK17 服务 + HTTP 直发业务 XML——且 V6.0.0.1 接口说明书的报文（§5）与前置机视角完全一致，业务层实现可复用，只换传输层。

---

## 2. 三步对接流程（demo Main.java 实证）

```
① 初始化（全局仅一次）
   sdkInit() → 重复初始化报 ETSK004（"重复初始化"）

② 下载云证书（一次性，下载码随即作废）
   cerMNG(下载码, 组织机构代码, 证书保存路径)
   - 下载码由银行下发，仅可用一次
   - 证书文件落盘到证书路径（后续 send 每次引用）

③ 发交易
   send("DLGECOMM", 通用报文, 证书路径)
   - 通用报文 = 外层 stream（见 §3.1），业务 XML 放 requestContent（Base64）
   - CASHFLAG：0=单笔；1=集团（集团客户用）
```

错误码（联调简介 PDF）：`ETSK004` 重复初始化；证书下载码类错误见 §8 需银行重置（ED52047/TB3U007 上下文）。

---

## 3. 报文结构

### 3.1 双层结构（SDK 传输层 + 业务层）

**外层（SDK send 的"通用报文"，Base64 包业务 XML，GBK）**：

```xml
<?xml version="1.0" encoding="GBK"?>
<stream>
  <action>DLGECOMM</action>
  <userName/><!-- 登录名 -->
  <requestContent><!-- Base64(业务报文) --></requestContent>
  <CASHFLAG>0</CASHFLAG><!-- 0:单笔 1:集团 -->
</stream>
```

**响应外层**：

```xml
<stream>
  <status>AAAAAAA</status><!-- 交易状态，见 §3.3 -->
  <statusText/><!-- 错误时中文描述 -->
  <responseContent><!-- Base64(业务响应 XML) --></responseContent>
</stream>
```

> 注意：外层 status 只代表"通道/解密/报文完整性"级状态；**业务成败看内层业务 XML 的 status**。两层都要判。

### 3.2 业务 XML 通用规则（V6.0.0.1 说明书，标签大小写敏感）

```xml
<?xml version="1.0" encoding="GBK"?>
<stream>
  <action>DLTRNALL</action><!-- 内层首元素 = 业务交易码 -->
  <userName/>
  <list name="userDataList"><!-- 循环域：重复数据记录 -->
    <row><key>value</key></row>
  </list>
</stream>
```

- 标签名必须与接口定义**逐字一致（含大小写）**；
- 数据项默认**非空**，可空项文档会标注；
- 金额 `decimal(15,2)`（整数 13 位，0.00 ~ 9999999999999.99）；日期 `YYYYMMDD`；时间 `hhmmss`；
- 列表返回统一在 `<list name="userDataList"><row>…</row></list>` 循环域。

### 3.3 信息代码（业务层 status / statusText）

| status | 含义 | 处理建议 |
|---|---|---|
| `AAAAAAA` | 交易处理成功 | 正常解析 |
| `AAAAAAB` | 经办成功待审核 | 仅动账相关 |
| `AAAAAAC` | 预约支付成功 | 仅动账相关 |
| `AAAAAAE` | 已提交银行处理，需稍后查状态 | 查询类一般不见 |
| `CCCCCCC` | 交易处理中 | 轮询/重试策略触发 |
| `EEEEEEE` | **交易未产生** | 务必用汇总查询交易确认真实状态，勿直接判失败 |
| `UNKNOWN` | 交易状态未知 | 同上 |
| 其他 | 两位字母+数字 = 网银错误码；其他 = 后台错误码；`statusText` 中文描述 | 记日志 + 映射 |

### 3.4 响应容器级字段速查

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | char(7) | 交易状态 |
| `statusText` | varchar(254) | 状态信息（错误时中文） |
| `accountNo`/`accountName` | char(19)/varchar(122) | 账号/户名（多数接口返回） |
| `totalRecords` | int | 总记录数 |
| `returnRecords` | int | 本次返回条数 |

---

## 4. 服务器配置与 properties（demo citicbank-sdk.properties 实证）

**环境 URL（properties 注释原文）**：

| 环境 | 地址（含固定路径 `/DLink/DLServlet/Open`） | 协议 |
|---|---|---|
| TSEA 商户联调 | `202.108.57.60:8080`（域名 `tsea.colb.test.citicbank.com`） | HTTP |
| TRA 测试 | `202.108.57.60:7080` | HTTP |
| TC 测试 | `202.108.57.65:10187` | HTTP |
| 生产 | `enterprise.bank.ecitic.com`（:443） | HTTPS |
| 专线 | `ecenter.bank.ecitic.com` | HTTPS |

**SDK 配置键**（键名即 SDK 约定，勿改名；换行即配置项结束）：

| 键 | 值 | 说明 |
|---|---|---|
| `CITICBANK.URL` | 上表 URL | 银企直联服务器 |
| `CITICBANK.proxy.http.hostname/port/username/password` | 空=不用代理 | 可空 |
| `CITICBANK.host.ip` | 本机网卡 IP | **必须与提供给银行的 MAC 对应**（白名单双绑）；`OpenCommunicationCustom=false` 时用 |
| `CITICBANK.OpenCommunicationCustom` | `true` | 走自定义 token/mac 通讯（demo 即如此） |
| `CITICBANK.log.path/limit/history` | 如 `/var/cache/citicbank-dlink-sdk-logs` / 100(MB) / 30(天) | SDK 日志 |
| `CITICBANK.sftp.actions/ip/port` | 不涉及 SFTP 可空 | 仅 SFTP 类交易用 |

> 现有 `CiticBankProperties`（prefix `bank.citic`：mockMode/baseUrl/appId）需扩展为：`url`、`orgCode`（组织机构代码）、`certPath`、`token`、`hostIp`、`openCommCustom`、`downloadCode`（一次性）、`logPath` 等，见 §8。

---

## 5. FINFLOW 需要的接口定义（业务 XML 内层）

### 5.1 接口总览

| 交易码 | 名称 | FINFLOW 用途 | 建议 |
|---|---|---|---|
| `DLBALQRY` | 余额查询 | 账户实时余额 | ✅ 必做（§5.2） |
| `DLTRNALL` | 账户明细信息查询 | **流水拉取主接口** | ✅ 必做（§5.3） |
| `DLHBLQRY` | 账户历史余额查询 | 历史对账（30 天窗口） | ◐ 对账增强（§5.4） |
| `DLEDDRSQ` | 回单信息查询 | 回单核对/附件 | ◐ 二期增强（§5.5） |
| `DLEDCDTD` | 电子回单下载 | 同上 | ✗ 二期再说 |
| `DLINTTRN` 等动账 | 支付转账/批量 | v0.4 已砍支付 | ✗ 不实现 |
| `DLTRNCOL`/`DLTRNDET` | 明细概要/详情 | 可用 DLTRNALL 平替 | ✗ 不实现（冗余） |
| `DLOACCBL/DLOACCDT/DLOACTDT` | 他行账户查询 | 依赖跨行协议签约 | ✗ 不实现 |

### 5.2 DLBALQRY — 余额查询

> 实时余额，多活期账号一次查（≤10 个/次），响应 0.5-1.5s。保证金账户可用余额含冻结，其他不含。

**请求**：

```xml
<action>DLBALQRY</action>
<userName/><!-- 登录名 varchar(30) -->
<list name="userDataList">
  <row><accountNo/><!-- 账号 char(19) --></row>
</list>
```

**响应 row 字段**（每账号一行）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` / `statusText` | char(7)/varchar(254) | **账户级**状态（多账号部分失败场景要逐行判） |
| `accountNo` / `accountName` | char(19)/varchar(122) | 账号/账户名称 |
| `currencyID` | char(2) | 币种 |
| `openBankName` | varchar(122) | 开户行名称 |
| `lastTranDate` | char(8) | 最近交易日 |
| `usableBalance` | decimal(15,2) | **可用余额** |
| `balance` | decimal(15,2) | **账号余额** |
| `forzenAmt` | decimal(15,2) | 冻结（看管）金额（**字段名照抄原文，拼写即 forzen**） |
| `frozenFlag` | char(1) | 仅信银国际账号返回：A 正常 / D 睡眠 / F 冻结 |
| `accountType` | char(2) | 仅信银国际：ST 活期储蓄 / IM 活期支票 |
| `lawptLmt` | decimal(15,2) | 仅信银国际：法透额度 |

### 5.3 DLTRNALL — 账户明细信息查询（流水主接口）

> **硬约束：分页查询，每页 ≤20 条；起始~截止日期间隔 ≤92 天；最远追溯 3 年**；响应 0.1-1.5s。
> `controlFlag` 控制银行端扩展字段返回（见下），**上送 `2`** 以拿到 `oriNum` 原始流水号做幂等键。

**请求**：

```xml
<action>DLTRNALL</action>
<userName/><!-- varchar(30) -->
<accountNo/><!-- 账号 char(19) -->
<lowAmount/><!-- 最小金额 decimal(15,2)，可空 -->
<upAmount/><!-- 最大金额 decimal(15,2)，可空 -->
<startDate/><!-- YYYYMMDD -->
<endDate/><!-- YYYYMMDD，与 startDate 间隔 ≤92 天 -->
<pageNumber/><!-- 本次请求条数，最大 20 -->
<startRecord/><!-- 起始记录号 char(4)，从 0 还是 1 需联调实测 -->
<controlFlag/><!-- 建议 2：兼容新增 + 返回原始流水号 -->
```

**响应**（容器级：`status/statusText/accountNo/accountName/openBankName/totalRecords/returnRecords` + list）：

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `tranDate` / `tranTime` | char(8)/char(6) | 交易日期 YYYYMMDD / 时间 hhmmss | |
| `tranNo` | char(14) | 柜员交易号 | 明细唯一键之一 |
| `sumTranNo` | char(13) | 总交易流水号 | 明细唯一键之一 |
| `tranAmount` | decimal(15,2) | 交易金额 | |
| `creditDebitFlag` | char(1) | 借贷：**D=借(付)，C=贷(收)** | 方向映射关键 |
| `oppAccountNo` | varchar(32) | 对方账号 | |
| `oppAccountName` | varchar(122) | 对方账户名称 | |
| `oppOpenBankName` | varchar(122) | 对方开户行 | |
| `abstract` | varchar(102) | 附言/摘要 | |
| `cashTransferFlag` | char(1) | 0 现金 / 1 转账 | |
| `opId`/`opName` | char(20)/varchar(20) | 网银制单员/姓名 | |
| `ckId`/`ckName` | char(20)/varchar(20) | 网银审核员/姓名 | |
| `balance` | decimal(15,2) | 交易后账户余额 | 对账可用 |
| `valueDate` | char(8) | 起息日期 | |
| `hostTranCode` | varchar(7) | 主机交易码 | |
| `e3rtDate` / `e3rtFlag` | char(8)/char(1) | 退汇日期 / 0 退汇 1 非退汇 | |
| `chkNum` | char(20) | 对账编号 | controlFlag≥1 |
| `rlTranNo` | char(14) | 关联交易日志号 | controlFlag≥1 |
| `rfTranDt` / `rfTranNo` | char(8)/char(14) | 冲账对方交易日期/柜员交易号 | controlFlag≥1 |
| `subAcccNo` | char(19) | 附属账户（**Accc 三个 c，照抄原文**） | controlFlag≥1 |
| `hostTranDesc` | varchar(20) | 摘要内容（归集/下拨等） | controlFlag≥1 |
| `oriNum` | varchar(36) | **原始流水号** | controlFlag≥2 ← 幂等/去重键 |
| `tranCodeDesc` | varchar(60) | 业务操作码描述（如"自动归集"） | controlFlag=3/6/7 |
| `setlcardNum` | varchar(20) | 单位结算卡号 | 4/6/7 |
| `tgfi` | varchar(14) | 支付方联行号 | 5/6/7 |
| `cratTmtp` | varchar(26) | 微秒级时间戳 | 6/7 |
| `vochCod` / `vochNum` | char(5)/varchar(20) | 凭证代码/号码 | 7 |

**controlFlag 取值决策**（原文规则）：
- `0`/空：不返回对账编号/关联日志/冲账/附属账户/摘要/原始流水号等 → 信息最少，**不推荐**；
- `1`：返回 chkNum/rlTranNo/rfTranDt/rfTranNo/subAcccNo/hostTranDesc；
- **`2`：在 1 基础上加 `oriNum` 原始流水号（FINFLOW 幂等去重必需）→ 推荐**；
- `3~7`：依次叠调拨描述/结算卡号/联行号/时间戳/凭证（6、7 才全量，含微秒时间戳可做严格排序）。
- **银行端若新增返回字段，会在 controlFlag≥1 时统一追加返回 → 解析器必须做未知字段兼容**（跳过而非报错）。

> ⚠️ 原文排版有残缺标签（如 `< balance>`、`<hostTranCode>` 内嵌空格），实现按上表标准名，**不要照抄 XML 示例里的空格**。

### 5.4 DLHBLQRY — 账户历史余额查询（可选）

> 签约账户历史余额；**起始~截止间隔 ≤30 天**；响应 0.5-2s。用于月底对账补历史快照。

**请求**：`accountNo` + `startDate` + `endDate`。**响应**：容器 + `mngNode`(开户网点) + `cryType`(币种) + list row：`date`(YYYYMMDD) / `balance`。

### 5.5 DLEDDRSQ — 回单信息查询（二期可选）

> 分页 ≤100 条/页；响应 0.1-1.5s。`qryType`：1=T+0 当日 / 2=T+1 非当日；`billType`：0 全部/1 转账/2 利息/3 收费/4 电子缴税/5 结售汇/6 现金管理转账；`minAmt/maxAmt/startDate/endDate/pageSize(≤100)/startNo`。
> 关键字段：`brseqNo` 回单编号（T+0 28 位 / T+1 46 位——**T+0 与 T+1 的编号体系不同，落库键要区分**）、`billType`（100000 存款/100001 取款/200000 转账/…）、`cdfg` 借贷标识 C/D、`brStt` 回单状态（1 正常/2 冲正/3 被冲正/4 当日冲正）、对手方/己方账号户名。
> 下载走 `DLEDCDTD`（单次 ≤10 个，fileType 0=pdf+ofd/1=pdf/2=ofd）。

---

## 6. 分页/窗口/追溯约束速查

| 接口 | 分页模型 | 每页上限 | 时间窗口 | 追溯深度 |
|---|---|---|---|---|
| DLBALQRY | 账号列表 | 10 账号/次 | — | — |
| DLTRNALL | pageNumber+startRecord | **20** | **92 天** | **3 年** |
| DLHBLQRY | 无 | — | **30 天** | — |
| DLEDDRSQ | pageSize+startNo | 100 | 当日(T+0)/历史(T+1) | — |

> 拉数策略推论：日切任务按"自然日 = 1 窗口"循环翻页即可满足 92 天/20 条约束；**补数场景（跨 >92 天）必须切分窗口**。查询限频 400 笔/h（§7），日切约 60+ 账户 × 2 页/日 ≈ 可承受，但**大补数要排队限速**。

---

## 7. 安全与运营约束（联调简介 + 05 文档 + demo 汇总）

1. **IP+MAC 双白名单**：找客户经理在分行内管维护——6 个单 IP 或 4 个 IP 段；互联网客户要提供**出口 IP**；`CITICBANK.host.ip` 必须填 MAC 对应网卡的 IP。**拿到测试包后第一件事**。
2. **token 规则**：自定义且 >32 位，加密进证书；**token 变更 = 全部证书作废**，需银行重置下载码（错误上下文 ED52047/TB3U007）→ token 列为最高密级配置，走 KMS/环境变量，禁入代码库。
3. **测试限频**：查询 400 笔/小时、动账 200 笔/小时，**不可压测**。
4. **30 分钟无交易自动下线**：发交易前先发一笔查询"唤醒"（调度首步可固定发 DLBALQRY 最小查询）。
5. 云证书下载码**一次性**，使用即作废，妥善保管再下发流程。
6. 初始化**全局一次**，重复初始化报 ETSK004。
7. **防重放/回执验签**：SDK 传输层已封装，但业务层幂等仍需 `clientID/oriNum` 去重（DLTRNALL 用 `oriNum`，动账若未来启用用 `clientID` 客户流水号）。

---

## 8. 与现有代码差异与重构要点（P0-1 直接输入）

现状（backend/src/main/java/com/finance/system/bank/）与目标差距：

| 现有类 | 现状假设 | 真实形态要求 | 动作 |
|---|---|---|---|
| `CiticBankProperties`(prefix=`bank.citic`, mockMode/baseUrl/appId) | HTTP 网关 + AppId | URL=/DLink/DLServlet/Open + orgCode + certPath + token + hostIp + openCommCustom + logPath + 下载码 | **重构**为 SDK 配置键映射（§4） |
| `CiticBankSdkClient`(interface) | `queryAvailableBalance(BankAccount)` / `submitPayment(...)` | SDK 三步（init → cerMNG → send）+ 通用 `send(action, xml)` | **重构**：加 `initOnce()`、`downloadCert(downloadCode, orgCode, path)`、`send(String action, String bizXml)` 返回码 + Base64 解析 |
| `ExternalCiticBankSdkAdapter` | 占位 | 组装内层 XML → 外层 DLGECOMM/Base64 → send → Base64 解码 → 内层解析 | 按 §2/§3/§5 实现 |
| `MockCiticBankSdkClient` | mock | 保留（D3 软切换 flag） | 保留，扩展新方法签名 |
| jar 依赖 | 无 | lib/ 下 5 个 jar（citicbank-dlink-lib-4.1.3 等）system scope 或进私有仓库 | **P0-1 即办**：验 jar 在 JDK17 可加载（§9 验收 4） |

解析层补充：现有 `CiticBankDataCodec` 应改为"GBK XML ⇄ 对象"通用 codec（dom4j 已在 jar 内重定位，业务代码可引 dom4j 或自带轻量解析，注意 XML 编码 GBK + 未知字段跳过 + 大小写敏感）。

---

## 9. 开发验收清单（P0-1 交付门槛，白名单前可完成的全部项）

> **落地注记（2026-09-03）**：A1–A5/A7 已按新 SPI 链路（`BankDataAdapterRegistry` 路由 + `BankDataAdapter` 接口，位于 `backend/.../bankdata/adapter/`）完成；下述清单按早期 HTTP 网关假设命名（`CiticBankProperties`/`CiticBankSdkClient`/`ExternalCiticBankSdkAdapter`），仅为设计溯源，实际实现类名以注记内为准。A6 未做，见下。

| 项 | 状态 | 实际落地 |
|---|---|---|
| A1 配置层 | ✅ | `citic/CiticAdapterProperties`（prefix `bankdata.adapter.citic`，realEnabled=false、startRecordBase=1、pageSize=20 硬约束、controlFlag=2、SDK 键集占位）；application.yml citic 段环境变量占位 |
| A2 传输接口 | 🟡 | `citic/dlink/CiticDlinkSdk` 接口（exchange → 内层业务 XML + downloadCertificate）；`UnavailableCiticDlinkSdk`（`@ConditionalOnMissingBean` 默认 501）。真实 `SdkCiticDlinkSdk` 待 A4 通过后引 jar 实现 |
| A3 业务层 | ✅ | 报文模型（DLBALQRY 余额 / DLTRNALL 流水，92 天窗 + page≤20 校验）+ `CiticRequestXml`（GBK 组装）+ `CiticResponseXml`（禁 DOCTYPE/外部实体、双层 status、大小写精确）+ `CiticEnvelopeCodec`（DLGECOMM 外层，复用 `CiticBankDataCodec` GBK Base64） |
| A4 兼容性冒烟 | ✅ 2026-09-03 | JDK17.0.20.1 实测（`tmp/a4-smoke/SmokeMain.java`）：5 个 jar 关键类加载 OK、`new DefaultOpenCommunication()` 构造 OK、`getInstance()` OK，**无任何 LinkageError** → A 形态（SDK 直连）成立 |
| A5 单测 | ✅ | 新增 5 测试类 25 例（RequestXml/ResponseXml/EnvelopeCodec/RealCiticBankDataAdapter/Codec）；全量回归 59/59 绿 |
| A6 CI 契约 | ❌ 未做 | prod mock 拒绝启动未实现；现有反向安全网 `bankdata.adapter.call.real-adapters-enabled=false`（聚合执行器双开关）比 dev-guide 原设计更严，R4 风险已覆盖，A6 可降级 |
| A7 落库映射 | ✅ | `RealCiticBankDataAdapter`（adapterCode=CITIC，`@ConditionalOnProperty real-enabled=true`）；statementNo 映射链 tranNo→sumTranNo→oriNum（幂等键不变，无需新迁移）；D/C→EXPENSE/INCOME；cursor 承载 startRecord；hasMore=returnRecords≥20 |

- [ ] **A1 配置层**：`CiticBankProperties` 按 §4 键集重构；application.yml/application-test.yml 占位参数就绪（测试环境 URL=TSEA/TRA，mock 默认 true）
- [ ] **A2 传输接口**：`CiticBankSdkClient` 增 `initOnce/downloadCert/send(action,xml)`；`ExternalCiticBankSdkAdapter` 实现三层封装（§2/§3.1）
- [ ] **A3 业务层**：`DLBALQRY` + `DLTRNALL` 请求组装与响应解析（§5.2/§5.3 字段表，controlFlag=2，未知字段跳过）
- [ ] **A4 兼容性冒烟**：JDK17 下加载 `citicbank-dlink-lib-4.1.3.jar` + 初始化不抛 LinkageError（**白名单前必须完成，直接决定 A/B 形态**）
- [ ] **A5 单测**：codec 大小写/GBK/空值/EEE 状态/部分失败（账户级 status）用例；Mock 双线
- [ ] **A6 CI 契约**：prod profile 下 `mockMode=true` 拒绝启动（防 R4）
- [ ] **A7 落库映射**：DLTRNALL → 流水实体（`oriNum+tranNo+sumTranNo` 幂等键、D/C → 收/支、金额/余额 decimal 精度）

## 10. 待银行确认清单（白名单到手后第一轮）

1. 测试包实际发的下载码/组织机构代码/证书路径约定；
2. 我方出口 IP + MAC 提交给谁、多久生效（白名单双绑）；
3. `DLTRNALL.startRecord` 起始值（0 还是 1）——联调首个用例；
4. 测试环境 URL 用 TSEA 还是 TRA/TC（两个网段都要试通？）；
5. 回单（DLEDDRSQ）T+0/T+1 是否已签约开通（影响二期）。

---

## 附录：源文件清单（资料位置）

| 源 | 路径 | 状态 |
|---|---|---|
| 接口开发 PDF | `/d/中信银行sdk/2.银企直联联调测试简介-接口开发.pdf` | 已提取 → `tmp/citic-联调测试简介-接口开发.txt` |
| SDK 对接说明 DOC | `/d/中信银行sdk/【本地化部署】05-中信银行银企直联SDK对接说明.doc` | 已提取 → `tmp/citic-SDK对接说明.txt`（OLE2 启发式） |
| Windows 安装指南 PDF | `/d/中信银行sdk/【本地化部署】02-中信银行银企直联Windows操作系统安装指南V1.2.pdf` | 已提取 → `tmp/citic-本地化部署-安装指南V1.2.txt` |
| demo 工程 | `/d/中信银行sdk/dlink-sdk-demo4customer/`（Main.java/CustomOpenCommunication.java/pom/properties + lib 5 jar） | 已精读 |
| 接口说明书（业务报文权威） | `Documents/财务流水自动化/财务流水自动入账项目/docs/中信银行银企直联接口说明书(集团客户)V6.0.0.1_转自DOC.docx` | 已提取 → `tmp/citic-接口说明书V6.0.0.1.txt` |
| 同源 codex 汇总 | 同上项目 `docs/参考资料集成汇总.md`、`docs/D3银行与金蝶凭据待确认清单-v1.md` | 交叉参考 |
