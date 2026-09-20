# FINFLOW 对接指导：招行 CloudDC 账务查询报文规范（账户余额 + 交易流水）

> 生成：2026-09-04 ｜ WB
> 来源：招行企业开放平台·云直联文档中心「账务查询」文档集
> `https://openbiz.cmbchina.com/clouddc-document?bizkey=DCCT20201214145038074&subclass=1&treeID=2026082616401771366`（treeID 指向「9.批量查询余额 NTQADINF」）
> 文档集更新日期：2026-08-27 ｜ 全部 19 篇已离线镜像至 `docs/cmb-clouddc/markdown2/`（原始 HTML 在 `raw2/`）
> 前置依赖：免前置接入、报文签名/加密流程见本目录既有文档（`4.2请求处理流程.md`、`DcHelper.java` 示例），本文不重复。

---

## 一、官方推荐的开发主线

招行概述篇明确建议按 **「查询账户余额 → 查询交易流水 → 查询电子回单」** 的顺序开发。常规三件套：

| 用途 | 官方推荐接口 | 说明 |
| --- | --- | --- |
| 查余额 | `NTQACINF` 结算账户详细信息查询 | 含实时余额四口径 |
| 查流水 | `trsQryByBreakPoint` 账户交易信息查询 | 断点续传，单次 ≤200 笔 |
| 查回单 | `ASYCALHD` 电子回单异步查询 | 本文不展开 |

**FINFLOW 选型建议**：余额主用 `NTQADINF`（批量、容错好，见下）；流水主用 `trsQryByBreakPoint`；`TotalAccountDirect` 用于账户 discovered/对账维度的账户列表刷新。

---

## 二、报文信封规范（所有账务查询接口通用）

### 2.1 请求信封

```json
{
  "request": {
    "head": {
      "funcode": "<接口名，如 NTQADINF>",
      "userid":  "<直联用户ID，免前置在企业网银 ubank 直联设置页查看>",
      "reqid":   "<请求唯一ID>"
    },
    "body": { "<接口专属结构>": [ ... ] }
  }
}
```

- `reqid`：免前置方式**必输**。前 17 位必须为 `yyyyMMddHHmmssSSS`，总长 18–51 位，每个请求唯一。
- `body` 结构为接口专属的「接口名 + X1/Y1/Z1/Z2」或小写集合名（如 `ntqadinfx`），每个接口不同，见第四节。

### 2.2 响应信封与结果语义（关键约定）

```json
{
  "response": {
    "head": {
      "funcode": "...", "userid": "...", "reqid": "...",
      "rspid": "<银行响应唯一ID>",
      "resultcode": "SUC0000",
      "resultmsg": "",
      "bizcode": ""
    },
    "body": { ... }
  }
}
```

> ⚠️ **`resultcode = SUC0000` 只表示「请求已被银行成功受理」，不代表业务处理成功。**
> 业务结果必须检查 body 中每条记录的 `errcod`/`errtxt`（如 NTQADINF 按账户返回 `SUC0000` 或错误码）。
> 查询类请求出错可直接重发；重发时 `reqid` 规则不变。

### 2.3 免前置安全处理（引用既有文档）

请求发送前：对整个报文预添加 `signature`（含 `sigtim`=yyyyMMddHHmmss、`sigdat` 预填 `__signature_sigdat__`）→ SM2-with-SM3 签名 → SM4-CBC 加密。国密向量 = `userid` 右补 0 至 16 位。完整流程与代码对照见 `docs/cmb-clouddc/markdown/4.2请求处理流程.md` 与 `samples/免前置Demo/Api/Java/DcHelper.java`。

---

## 三、接口选型总览（账务查询文档集 19 篇中与本任务相关）

| # | 接口 funcode | 用途 | 与 FINFLOW 关系 |
| --- | --- | --- | --- |
| 9 | **NTQADINF** | 批量查询实时余额（≤30 账户/批） | ⭐ 余额同步主接口 |
| 3 | NTQACINF | 结算账户详细信息（含实时余额，多账户） | 备选；**任何一户无权限/出错则整体无返回**，容错差 |
| 6 | TotalAccountDirect | 客户号/账号维度查结算+非结算账户详情与汇总 | ⭐ 账户发现/账户列表同步 |
| 7 | NTQABINF | 历史余额（按日，区间 ≤31 天） | 期初/对账场景 |
| 8 | NTQRYHSB | 历史冻结/可用余额（需白名单开通） | 按需 |
| 10 | **trsQryByBreakPoint** | 账户交易流水（断点续传，单次 ≤200 笔） | ⭐ 流水同步主接口 |
| 11/12 | NonSettleTranOpr / NonSettleTranQry | 非结算户流水导出/查询 | 非结算户场景 |
| 13/14/15 | DCTRSPDF / issueBillOfd / queryBillOfd | PDF/OFD 对账单文件 | 电子回单替代，按需 |
| 16/17/18 | DCSIGREC / ASYCALHD / DCTASKID | 回单查询/异步打印 | 按需 |
| 1/2 | DCLISMOD / DCLISACC | 可经办业务模式/账户列表 | 主要面向支付代发；账务查询（buscod=N01010）时 busmod 忽略 |
| 4 | NTACCBBK | 查分行号信息 | 辅助 |

---

## 四、核心接口报文规范

### 4.1 批量查询余额 NTQADINF ⭐（余额同步主接口）

**场景**：批量查询账户实时余额、账户性质、开户分行、开户日、利率、到期日、存期。
**与 NTQACINF 的差异**：NTQACINF 遇任一账户无权限/出错则**整体无结果**；NTQADINF 逐户返回，**坏户不影响好户**，且每户带 `errcod`。多账户企业优先用它。

**请求 body**（查询条件集合 `ntqadinfx`，多记录，一批 ≤30 个账户）：

| 字段名称 | 字段ID | 类型 | 必输 | 描述 |
| --- | --- | --- | --- | --- |
| 分行号 | `bbknbr` | string(2) | — | A.1 招商分行代码 |
| 账号 | `accnbr` | string(35) | Y | |
| 币种 | `ccynbr` | String(2) | N | A.3 货币代码；10=人民币 |
| 保留字 | `rsv30z` | String(30) | N | |

**响应 body**（账户信息集合 `ntqadinfz`，多记录，逐户）：

| 字段名称 | 字段ID | 类型 | 描述 |
| --- | --- | --- | --- |
| 币种 | `ccynbr` | string(2) | A.3 货币代码 |
| 科目 | `accitm` | string(5) | |
| 分行号 | `bbknbr` | string(2) | |
| 帐号 | `accnbr` | string(35) | |
| 户名 | `accnam` | Z(62) | 一般为户名 |
| 客户关系号 | `relnbr` | string(10) | 集团网银成员编号 |
| **上日余额** | `accblv` | M | =联机余额−当日发生额；`intcod='S'` 时为子公司虚拟余额（头寸额度） |
| **联机余额** | `onlblv` | M | 账户实际资金 |
| **冻结余额** | `hldblv` | M | 司法冻结+银行冻结等加总 |
| **可用余额** | `avlblv` | M | 实际可付款金额 = 联机−冻结−预期+透支额度 |
| 透支额度 | `lmtovr` | M | |
| 状态 | `stscod` | string(1) | A=活动 B=冻结 C=关户 |
| 利息码 | `intcod` | string(1) | S=子公司虚拟余额 |
| 年利率 | `intrat` | F(11,7) | |
| 开户日 | `opndat` | D | 8 位 YYYYMMDD |
| 到期日 | `mutdat` | D | 8 位 |
| 利率类型 | `inttyp` | string(3) | ZZZ 不计息 / TD1·TD2 定期 / C01 协议 等 |
| 存期 | `dpstxt` | Z(12) | 定期时：一天~五年 |
| 错误码 | `errcod` | string(7) | 成功 `SUC0000` |
| 错误说明 | `errtxt` | Z(92) | |

**请求范例**：

```json
{
  "request": {
    "body": {
      "ntqadinfx": [
        { "accnbr": "769900000010370", "bbknbr": "69" },
        { "accnbr": "OSA769900206010102", "bbknbr": "12" }
      ]
    },
    "head": {
      "funcode": "NTQADINF",
      "userid": "N000097143",
      "reqid": "202003161123456780001fbdev01"
    }
  }
}
```

**响应范例**（节选）：

```json
{
  "response": {
    "body": {
      "ntqadinfz": [
        {
          "accblv": "4111.01", "accitm": "10001",
          "accnam": "北京迪龙化工有限公司", "accnbr": "769900000010370",
          "avlblv": "0.00", "bbknbr": "69", "ccynbr": "10",
          "errcod": "SUC0000", "hldblv": "0.00", "intcod": "S",
          "intrat": "0.0000000", "lmtovr": "0.00", "mutdat": "00000000",
          "onlblv": "0.00", "opndat": "20140519", "relnbr": "0000001256",
          "stscod": "A"
        }
      ]
    },
    "head": {
      "funcode": "NTQADINF", "resultcode": "SUC0000", "resultmsg": "",
      "reqid": "...", "rspid": "20200908165717654000100180374319-LW",
      "userid": "N000097143", "bizcode": ""
    }
  }
}
```

### 4.2 账户交易信息查询 trsQryByBreakPoint ⭐（流水同步主接口）

**场景**：查近 13 个月对公金融交易数据；13 个月外至 5 年内需联系客户经理开通权限（默认有效期 1 年）。
**产品包装行为**（影响对账逻辑，必须知晓）：
- 日间**隐藏**实时划拨交易，日终对实时划拨**汇总后新增一笔汇总交易** → 同一天内两次查询流水可能不一致，日终数据才是终态；
- 组合存款协议内交易被屏蔽；支取类只体现利息金额。

**分页机制**：断点续传，**单次最多 200 笔**；`ctnFlag=Y` 表示未查完，须循环续传。

**请求 body**：

| 集合 | 类型 | 必输 | 说明 |
| --- | --- | --- | --- |
| `TRANSQUERYBYBREAKPOINT_X1` | JSONArray | Y | 查询条件（单记录） |
| `TRANSQUERYBYBREAKPOINT_Y1` | JSONArray | N | 续传键值（首次查询不传） |

`X1`（单记录）：

| 字段名称 | 字段ID | 类型 | 必输 | 描述 |
| --- | --- | --- | --- | --- |
| 户口号 | `cardNbr` | String(35) | Y | 一个户口号下可有多币种账号 |
| 开始日期 | `beginDate` | D | Y | YYYYMMDD |
| 结束日期 | `endDate` | D | Y | YYYYMMDD |
| 起始记账序号 | `transactionSequence` | String(9) | — | 仅无续传键时有效，默认从第 1 笔 |
| 币种 | `currencyCode` | String(2) | — | 可空，建议传 |
| 继续查询账号 | `queryAcctNbr` | String(200) | — | 首次不传；续传时填响应 Z1 的 `queryAcctNbr` |
| 保留字段 | `reserve` | String(200) | — | |
| 借贷码 | `loanCode` | String(1) | — | C 贷方，D 借方 |

`Y1`（多记录，续传键，原样回传）：

| 字段名称 | 字段ID | 类型 | 描述 |
| --- | --- | --- | --- |
| 账号 | `acctNbr` | String(200) | 行内续传用账号，**无业务含义，照传即可** |
| 交易日期 | `transDate` | D | 当前查询最后一笔交易日期 |
| 期望下一记账序号 | `expectNextSequence` | String(9) | 期望下一笔记账序号 |

**响应 body**：

| 集合 | 内容 |
| --- | --- |
| `TRANSQUERYBYBREAKPOINT_Y1` | 续传键（下次请求原样带回） |
| `TRANSQUERYBYBREAKPOINT_Z1` | 分页控制 + 小计（单记录） |
| `TRANSQUERYBYBREAKPOINT_Z2` | 交易流水明细（多记录） |

`Z1`（单记录）：

| 字段名称 | 字段ID | 类型 | 描述 |
| --- | --- | --- | --- |
| 未传完标记 | `ctnFlag` | String(1) | Y 还有后续 / N 查询完毕 |
| 继续查询账号 | `queryAcctNbr` | String(200) | `ctnFlag=Y` 时下次请求携带 |
| 借方笔数/金额 | `debitNums` / `debitAmount` | N / M | 本页汇总 |
| 贷方笔数/金额 | `creditNums` / `creditAmount` | N / M | 本页汇总 |

`Z2`（多记录，明细核心字段）：

| 字段名称 | 字段ID | 类型 | 描述 |
| --- | --- | --- | --- |
| 交易日 | `transDate` | D | YYYYMMDD |
| 流水号 | `transSequenceIdn` | String(15) | 银行流水唯一号 |
| 交易时间 | `transTime` | String(6) | HHmmss |
| 起息日 | `valueDate` | D | |
| 借贷码 | `loanCode` | String(1) | C 贷方 / D 借方 |
| 交易金额 | `transAmount` | M | |
| 币种 | `currencyNbr` | String(2) | |
| 交易类型 | `textCode` | String(12) | 附录 A.9 |
| 票据号 | `billNumber` | String(20) | |
| 你方摘要 | `remarkTextClt` | Z(200) | 企业网银经办=用途信息；他渠道=交易说明 |
| 冲帐标志 | `reversalFlag` | String(1) | `*` 冲帐 / `X` 补帐（与原交易借贷相反） |
| 余额 | `acctOnlineBal` | M | 交易后账户余额 |
| 扩展摘要 | `extendedRemark` | String(20) | |
| 收付方帐号/名称 | `ctpAcctNbr` / `ctpAcctName` | String(35) / Z(200) | |
| 收付方开户行行名/地址 | `ctpBankName` / `ctpBankAddress` | Z(400) / Z(200) | |
| 母子公司帐号/名称/行名/地址 | `fatOrSonAccount` / `fatOrSonCompanyName` / `fatOrSonBankName` / `fatOrSonBankAddress` | — | 集团交易场景 |
| 信息标志 | `infoFlag` | String(1) | 空=付方+子公司；1=收方+子公司；2=收方+母公司；3=原收方+子公司 |
| 业务名称 | `businessName` | Z(60) | |
| 网银业务摘要 | `businessText` | Z(400) | |
| 网银流程实例号 | `requestNbr` | String(10) | **String 类型** |
| 网银业务参考号 | `yurRef` | String(30) | 支付/代发的业务参考号记录于此 |
| 虚拟户编号 | `virtualNbr` | String(16) | 需开通交易管家-收款识别 |
| 商务支付订单号 | `mchOrderNbr` | String(50) | |
| 记账卡号 | `transCardNbr` | String(35) | 仅 cardNbr 与实际记账不一致时有值（公司卡、监管子户等） |
| 保留字 | `reserve` | String | |

**首次请求范例**：

```json
{
  "request": {
    "body": {
      "TRANSQUERYBYBREAKPOINT_X1": [
        {
          "cardNbr": "755947919810515",
          "beginDate": "20230401",
          "endDate": "20230502",
          "transactionSequence": "1",
          "currencyCode": "",
          "queryAcctNbr": "",
          "reserve": ""
        }
      ]
    },
    "head": {
      "funcode": "trsQryByBreakPoint",
      "reqid": "",
      "userid": "U003736239"
    }
  }
}
```

**响应（节选）**：

```json
{
  "response": {
    "body": {
      "TRANSQUERYBYBREAKPOINT_Y1": [
        { "acctNbr": "755947919880003", "transDate": "20230401", "expectNextSequence": "1" },
        { "acctNbr": "755947919880029", "transDate": "20230411", "expectNextSequence": "101" }
      ],
      "TRANSQUERYBYBREAKPOINT_Z1": [
        { "creditAmount": "0", "creditNums": "0", "ctnFlag": "Y",
          "queryAcctNbr": "755947919880029", "debitAmount": "-40.01", "debitNums": "1" }
      ],
      "TRANSQUERYBYBREAKPOINT_Z2": [
        { "acctOnlineBal": "2000110921419.82", "currencyNbr": "10", "infoFlag": "1",
          "loanCode": "D", "remarkTextClt": "批量代付业务报文", "reversalFlag": "N",
          "textCode": "EBPP", "transAmount": "-40.01", "transDate": "20220228",
          "transSequenceIdn": "C09468U00012KWZ", "transTime": "140337", "valueDate": "20220228" }
      ]
    },
    "head": { "funcode": "trsQryByBreakPoint", "resultcode": "SUC0000", "...": "..." }
  }
}
```

**续传规则（官方原文逻辑）**：
1. 响应 `Z1.ctnFlag = Y` → 还有记录；
2. 下次请求：`X1.queryAcctNbr` ← 响应 `Z1.queryAcctNbr`，`X1.Y1` 原样携带响应 `Y1`；
3. 循环直到 `ctnFlag = N`。

**断点查询（增量拉取）**：上午 10 点查完，16 点要查 10–16 点新增交易：`X1.queryAcctNbr` 置空，携带上午查询结束时的 `Y1` 即可——`Y1` 即天然增量游标。**FINFLOW 调度建议：把每次同步结束时的 `Y1` 持久化到任务上下文，作为下次增量游标。**

### 4.3 综合账户信息查询 TotalAccountDirect（账户列表/发现）

**场景**：客户编号维度查本企业/集团成员的结算+非结算账户详情（可含余额汇总）；或账号维度查单户。

**请求 body**（`TotalAccountDirectReq`，单记录）：

| 字段名称 | 字段ID | 类型 | 必输 | 描述 |
| --- | --- | --- | --- | --- |
| 客户编号 | `clientNbr` | String(10) | 与账号二选一 | |
| 账号 | `cardNbr` | String(35) | 与客户编号二选一 | |
| 查询类型 | `queryType` | String(1) | N | A 仅活期户 / B 仅非结算户 / C 所有（默认 C，仅按客户编号查询时有效） |
| 币别 | `currency` | String(2) | N | 空=不筛选 |
| 是否查询预警信息 | `queryWarnStatusFlag` | String(1) | N | 默认 N |

**响应**（`TotalAccountDirectRsp` 多记录 + `TotalAccountDirectSum` 单记录统计，仅客户号查询返回 Sum）：

| 字段名称 | 字段ID | 类型 | 描述 |
| --- | --- | --- | --- |
| 分行地区码 | `branchNbr` | String(2) | |
| 账号 | `cardNbr` | String(35) | |
| 户名 | `cardName` | Z(200) | |
| 客户关系号 | `relationNbr` | String(10) | 集团成员编号；普通网银为空 |
| 账户性质 | `cardType` | String(3) | LI 活期结算户 / CD 通知存款 / TD 普通定期 / DT 保证金 / SD 结构性存款 / LAD 大额存单 / OT 其他 |
| 账户状态 | `status` | String(1) | A 活动 / B 冻结 / C 关户 |
| 账户异常信息 | `accountAlarm` | String(20) | A 中止非柜面 / B 中止全部 / C 停止支付 / D 久悬 / E 账户冻结 / F 网银冻结 / G 销户 |
| 开户日期 / 关户日期 | `openDate` / `closedDate` | D | yyyyMMdd；未关户 closedDate 为空 |
| 币别 | `currencyNbr` | String(2) | |
| 联机余额 | `onlieBalance` | F(20,2) | 注意字段名拼写就是 onlieBalance |
| 可用余额 | `availabelBalance` | F(20,2) | 同理注意拼写 availabelBalance |
| 冻结余额 | `freezeBalance` | F(20,2) | |
| 透支额度 | `overDraftLimit` | F(20,2) | |
| 利率 / 利率类型 | `intRate` / `intRateType` | F(11,7) / String(1) | |
| 预警状态 | `warnStatus` | String(200) | 半角逗号拼接；查询失败返回 `-`，稍后重试 |
| 存期到期日期 | `termMatureDate` | D | |

> ⚠️ **拼写陷阱**：该接口响应字段用的是 `onlieBalance` / `availabelBalance`（缺字母），但 `TotalAccountDirectSum` 里又是 `sumAvailableBalance`（规范拼写）。解析器映射时以本文字段表为准，勿凭直觉拼写。

Sum 集合：`sumOnlineBalance` / `sumAvailabelBalance` / `sumFreezeBalance` / `sumOverDraftLimit`。

### 4.4 历史余额 NTQABINF（对账/期初场景）

- 请求 `ntqabinfy`（单记录）：`bbknbr`、`accnbr`(Y)、`bgndat`(Y)、`enddat`(Y)、`ccynbr`(N)。
- **开始与结束日期间隔 ≤31 天**，且必须**早于当日**。
- 响应 `ntqabinfz`（多记录）：`bbknbr`、`accnbr`、`trsdat`(交易日期)、`balamt`(联机余额 M)、`rsv30z`(币种编码)。
- 续传：响应含 `ntqabinfy` 且 `ctnkey` 非空时需续传（原样回传获取后续数据）。

### 4.5 历史冻结/可用余额 NTQRYHSB（需白名单）

- **前提**：需事先联系客户经理配置白名单开通权限，否则不可用。
- 请求 `ntqryhsbx1`（单记录）：`accnbr`(Y)、`ccynbr`(N，空=账号下所有币种；指定币种不存在则报错)、`bgndat`(Y)、`enddat`(Y)，间隔 ≤31 天、须早于当日。
- 响应 `ntqryhsbz1`（多记录）：`accnbr`、`ccynbr`、`trsdat`、`onlblv`、`hldblv`、`lmtovr`、`avlblv` —— 按「日期 × 账户 × 币种」返回余额四口径历史快照。

---

## 五、余额口径统一（对接 FINFLOW 的映射）

招行所有余额接口共用四口径，**含义跨行一致**（此前中信联调已验证 `usableBalance ≡ 招行 avlblv`）：

| 招行字段 | 含义 | FINFLOW 语义 | 说明 |
| --- | --- | --- | --- |
| `onlblv` | 联机余额 | **booked balance**（实际资金） | 余额同步的权威口径 |
| `avlblv` | 可用余额 = 联机−冻结−预期+透支 | **usable balance** | 可付款口径 |
| `hldblv` | 冻结余额（司法+银行） | **hold amount** | |
| `accblv` | 上日余额 = 联机−当日发生额 | 期初/上日余额 | `intcod='S'` 时为子公司虚拟余额，须识别 |

使用规则：
1. `stscod != 'A'`（冻结/关户）的账户余额仍需入库，但账户状态要同步更新；
2. `errcod != SUC0000` 的单户记录按失败处理，**不影响同批其他账户**；
3. 集团场景注意 `intcod='S'`（子公司虚拟余额/头寸额度）与真实资金区分。

---

## 六、工程实践约束（官方明确，违反即出错或降级）

| # | 约束 | 出处 |
| --- | --- | --- |
| 1 | 查询类接口与支付/代发/代理清算以外业务**共享 20 并发** | 概述 |
| 2 | **避免整点、半点发起查询**——账务查询在整点半点并发量大，响应耗时明显增加 | 概述 |
| 3 | 无特殊需求**建议当天查前一天数据，每天一次**（流水/回单数据量大） | 概述 |
| 4 | NTQADINF 一批 ≤ **30 个账户** | 9 |
| 5 | trsQryByBreakPoint 单次 ≤ **200 笔**，`ctnFlag=Y` 必须循环续传 | 10 |
| 6 | 流水近 13 个月可查；13 个月外至 5 年需客户经理开通（有效期默认 1 年） | 10 |
| 7 | NTQABINF / NTQRYHSB 日期区间 ≤ **31 天**，且须早于当日 | 7/8 |
| 8 | `resultcode=SUC0000` 仅=受理成功，业务结果看 body 内 `errcod` | 概述/1.2 |
| 9 | 查询类请求失败可直接重发 | 概述 |
| 10 | 响应字段**尾部可能带空格**，接收方自行 trim | 10 使用说明(4) |
| 11 | 日间流水含「隐藏实时划拨、日终汇总」包装，**以日终数据为终态** | 10 使用说明(1) |
| 12 | `requestNbr` 是 String(10) 不是数字——解析勿按数值处理 | 10 |

---

## 七、同步调度设计建议（FINFLOW 落地）

```
账户/余额同步（每日，避开整点半点，如 09:07）
  ├─ TotalAccountDirect(clientNbr)   → 账户清单刷新（新增/关户/状态变更）
  └─ NTQADINF(30/批)                 → 全量账户实时余额快照
       └─ 四口径入库: onlblv/avlblv/hldblv + accblv(上日)

流水同步（每日 T+1，或增量 Y1 游标）
  └─ trsQryByBreakPoint(cardNbr, beginDate, endDate)
       ├─ 首次: 传 X1，不传 Y1
       ├─ 循环: ctnFlag=Y → 携带 Z1.queryAcctNbr + 响应 Y1 续传
       └─ 增量: 持久化末次 Y1 作为断点游标，下次 queryAcctNbr 置空 + 带游标
```

FINFLOW 字段映射提示（与 `VendorStatementFields` 现行约定一致）：
- 流水唯一键：`transSequenceIdn`（bank 流水号）+ `transDate`；
- 借贷双轨：`loanCode` C/D → `signed_amount` 带符号（C 正 D 负）+ 无符号 `amount` 记账金额 `transAmount`；
- 血缘字段：`yurRef`（网银业务参考号）≈ 对端业务引用；`requestNbr` 流程实例号可关联我方发起的指令；
- 对手方：`ctpAcctNbr`/`ctpAcctName`/`ctpBankName`（收付方参照，不脱敏约定与中信一致）；
- 交易后余额：`acctOnlineBal` 可用于逐笔余额校验（与 NTQADINF 的 `onlblv` 对账闭环）。

---

## 八、离线镜像文件清单

| 路径 | 内容 |
| --- | --- |
| `docs/cmb-clouddc/raw2/` | 账务查询文档集原始抓取（19 篇 HTML + 目录树 `_catalog-DCCT20201214145038074.json`） |
| `docs/cmb-clouddc/markdown2/` | 19 篇 Markdown（本指导文档的字段表全部出处） |
| `tmp/cmb-accountquery-to-md.py` | 抓取结果的 HTML→MD 转换脚本（可复用） |
| `docs/cmb-clouddc/markdown/` | 既有「对接开发」集：报文信封、4.2 请求处理流程（签名/加密）、示例代码说明 |
| `docs/cmb-clouddc/samples/免前置Demo/Api/Java/` | `DcHelper.java` SM2/SM4 全链路参考实现 |

抓取命令（可复用，免登录）：`node docs/cmb-clouddc/fetch-cmb-docs.js DCCT20201214145038074 raw2`

## 九、验收清单（联调前自查）

- [ ] `reqid` 生成器：前 17 位 `yyyyMMddHHmmssSSS` + 唯一后缀，长度 18–51
- [ ] 报文签名/加密走 `DcHelper` 同款流程，`sigtim` 与银行时差 < 1 小时
- [ ] NTQADINF 按 30 户分批，逐户消费 `errcod`，不因单户失败放弃整批
- [ ] 余额四口径落库映射（onlblv/avlblv/hldblv/accblv），识别 `intcod='S'` 虚拟余额
- [ ] 流水同步实现 ctnFlag 循环续传 + Y1 游标持久化
- [ ] 所有字符串字段入库前 trim 尾部空格
- [ ] 调度避开整点/半点；默认 T+1 日终口径
- [ ] 13 个月外历史流水权限（如需要）已联系客户经理开通
- [ ] `requestNbr` 按 String 处理；TotalAccountDirect 字段按本文拼写表映射
