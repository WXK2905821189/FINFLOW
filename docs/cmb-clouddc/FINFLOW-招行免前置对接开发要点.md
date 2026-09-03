# FINFLOW × 招行云直联（CloudDC）免前置对接开发要点

> 版本：v1.0（2026-09-03）｜ 对接模式：**免前置（标准模式）**｜ 素材：`docs/cmb-clouddc/`（官方文档离线库 + 官方示例代码）
> 配套代码参照：`docs/cmb-clouddc/samples/免前置Demo/Api/Java/DcHelper.java`（招行官方，唯一权威实现）

---

## 1. 一句话结论

招行银企直连（免前置）= **国密 SM2 签名 + SM4 对称加密的 HTTPS/HTTP POST**。
每次请求带 4 个固定表单参数（UID / ALG=SM / DATA / FUNCODE），报文体为 JSON。
FINFLOW 现有 `CmbMockBankDataAdapter`（仅 13 行 mock）需要按本要点实现一个 `RealCmb...` 适配器，
核心加密逻辑可直接移植招行官方 `DcHelper.java`（包名 cmb，Java 8 + gson + bcprov）。

---

## 2. 免前置模式 vs 其他模式（你们为什么是免前置）

| 模式 | 前置机 | 身份/密钥 | 适用 |
|---|---|---|---|
| 有前置 + USB Key | 需装前置机程序 | 企业数字证书（USB Key） | 传统 |
| 有前置免 USB Key | 需装前置机 | 网银/企业 APP 授权 + 密钥交换 | 传统 |
| **免前置**（贵司） | **不装前置机** | **客户自生成国密密钥 → 网银上传 → 审批** | **ERP/自研系统云端轻量直连** |

免前置关键特征：企业 ERP 软件直接与招行后台通信，无中间件；身份靠"客户私钥 + 银行公钥 + 对称密钥"三把钥匙。

---

## 3. 接入前置条件（免前置专属，9/18 前必须完成）

| # | 事项 | 操作方 | 详见文档 |
|---|---|---|---|
| 1 | **测试资源申请**（企业开放平台→申请测试→创建项目→选"标准模式/免前置"→勾选接口→提交） | 企业（或分行协助） | `markdown/2.1测试.md` |
| 2 | 分行经办岗→审批岗→总行配置岗审批，**测试资源 1 个工作日下发** | 银行侧 | 同上 |
| 3 | **企业互联网出口 IP 白名单**（免前置=ERP 服务器出口 IP；不设则请求被拒；支持 `8.8.8.*` 通配） | 网银操作 | `markdown/2.8企业互联网出口IP白名单设置.md` |
| 4 | **密钥上传**（网银：设置与授权→密钥设置，上传"加密后对称密钥 + 用户公钥"） | 网银操作 | `markdown/2.9免前置密钥设置.md` |
| 5 | **密钥审批**（单管理员直接生效；双管理员需另一管理员审批） | 网银操作 | `markdown/2.10免前置用户密钥审批.md` |
| 6 | 生产开通需**测试验收通过**后由分行配置（测试报告→验收→生产白名单） | 企业+银行 | `markdown/2.1测试.md` §2 |

> 免前置接入密钥是**客户身份识别和通讯安全的基础**：密钥泄露风险时需重新生成上传。
> SaaS 模式协议续约须同步更新密钥。FINFLOW 须支持密钥的**可配置化 + 轮换**。

---

## 4. 密钥体系与报文安全（核心）

### 4.1 四类密钥

| 密钥 | 生成 | 存放 | 用途 |
|---|---|---|---|
| 对称密钥（SM4） | SMKeyTool 生成 | **客户自己保存** | 报文体 SM4 加解密 |
| 加密后对称密钥 | SMKeyTool | **上传银行** | 银行解密拿对称密钥 |
| 用户公钥 | SMKeyTool | **上传银行** | 银行验签 / 加密响应 |
| 用户私钥 | SMKeyTool | **客户自己保存** | 请求 SM2 签名 |

生成工具：`docs/cmb-clouddc/samples/国密生成工具/SMKeyTool.exe`（下拉选"生产/测试"）。

### 4.2 报文安全链路（每次请求）

```
原始 JSON 报文
   │ ① 对 KEY 按 ASCII 排序、去掉空格换行 → 得待签名字符串
   ▼
预填 signature.sigtim=yyyyMMddHHmmss(24h) + sigdat="__signature_sigdat__"
   │ ② SM2-with-SM3 签名(用户私钥, userId=网银用户号16位右补0) → 回填 sigdat
   ▼
整体 JSON
   │ ③ SM4-CBC 加密(symKey, 向量=userId) → Base64
   ▼
HTTP POST: 表单 UID=<userid>&ALG=SM&DATA=URLEncode(Base64密文)&FUNCODE=<接口码>
   │
   ▼ 响应 = SM4 密文(Base64) → ④ SM4 解密 → JSON
```

**细节红线**：
- 国密算法向量长度 16 位 = 用户 ID 右补 0（`DcHelper.getUserId()` 已实现）
- 签名用 SM2-with-SM3；BC 库 `Security.addProvider(new BouncyCastleProvider())` 必须在启动类调用
- 请求须设 `System.setProperty("sun.net.http.retryPost", "false")`（POST 自动重试会导致重复交易）
- 响应以 `CDCServer:` 开头 = 网关层错误（非业务响应）

---

## 5. 报文结构规范

### 5.1 HTTP 层

| 参数 | 说明 |
|---|---|
| URL | 测试 `http://cdctest.cmburl.cn:80/cdcserver/api/v2`（见 demo，注意是 http）；生产 `https://cdc.cmbchina.com/cdcserver/api/v2` |
| UID | 企业网银用户号（免前置在网银 UBank 直联设置页查看，示例 `N003261207`） |
| ALG | 固定 `SM` |
| DATA | URLEncode( SM4密文 Base64 ) |
| FUNCODE | 业务接口码（如 `NTQADINF` / `trsQryByBreakPoint`） |

### 5.2 JSON 报文

```json
{
  "request": {
    "head": { "funcode": "NTQADINF", "userid": "N002432758", "reqid": "yyyyMMddHHmmssSSS+自定义(18~51位)" },
    "body": { "...业务字段..." }
  },
  "signature": { "sigtim": "20190823093102", "sigdat": "____" }
}
```

| 字段 | 约束 |
|---|---|
| head.funcode | 接口码，String(20) |
| head.userid | 网银用户号 String(10) |
| head.reqid | **免前置必输**；前 17 位必须 `yyyyMMddHHmmssSSS`，后自定义，总长 18–51 |
| signature.sigtim | 当前时间 yyyyMMddHHmmss(24h)；银行验签与当前相差 >1h 报错 |
| signature.sigdat | 先填 `__signature_sigdat__`，签名后替换为 Base64 签名 |

---

## 6. FINFLOW 需要对接的业务接口（按优先级）

### 6.1 余额：批量查询余额 NTQADINF（首选）⭐

- 支持**多账户批量 ≤30 个/次**；单个错不影响其他返回（优于 NTQACINF 全错全无）
- 请求 `body`: `ntqadinfx`(多记录) = bbknbr(分行号,如 002 深圳)/accnbr(账号)/ccynbr(币种)
- 返回 `ntqadinfz`(多记录)：余额、账户性质、开户行、利率等
- FINFLOW 映射：balance entry（参考中信 DLBALQRY 处理方式，可查快照 + 逐页）

### 6.2 流水：账户交易信息查询 trsQryByBreakPoint ⭐

- 支持近 13 个月对公金融交易（13 个月~5 年需另申请权限，不建议频繁查）
- **断点续传**：单次 ≤200 条；请求带 `TRANSQUERYBYBREAKPOINT_Y1`(续传游标)
  - 请求 X1: 户口号 cardNbr（多币种时账号 acctNbr 是行内续传用，无需关心业务含义）
  - 返回 Z1: `ctnFlag`(Y=还有记录)、`queryAcctNbr`(下次携带)、借/贷笔数金额
  - 返回 Z2(明细): 交易日、借贷标志等
- **幂等/去重**：流水无统一交易序号，需自行组合（交易日期+金额+对手+摘要 hash，或依赖 reqid 追溯）
- FINFLOW 映射：statement entry（方向 EXPENSE/INCOME、时间、金额、对手、摘要）

### 6.3 其他（视需求启用）

| 接口码 | 用途 | 说明 |
|---|---|---|
| DCLISMOD | 可经办业务模式查询 | 上线自检第一步 |
| DCLISACC | 可经办账户列表 | 账户同步 |
| NTQACINF | 结算账户详细信息 | 多账户全错全无，备选 |
| NTQABINF | 历史余额 | 补缺口 |
| DCSIGREC / DCTASKID / ASYCALHD | 回单查询 | 二期 |
| BB1PAYOP / BB1PAYQR | 企银支付单笔经办/查询 | 若需对外付款再开（**支付功能 FINFLOW 一期不做动账**） |

> 对应离线文档：`markdown/` 下账务查询（19 篇）、支付转账（10 篇）已全量抓取。

---

## 7. 与 FINFLOW 现有架构的落地差距

### 7.1 现状

| 项 | 现状 |
|---|---|
| 接口 | `BankDataAdapter`（adapterCode/collect/executionMode），安全底线 SIMULATED 默认 |
| 集合 | `BankDataCollection(bankRequestNo, entries, balances, hasMore, nextCursor, ...)` |
| 条目 | `BankDataEntry(statementNo, bankAccountId, transactionTime, direction, amount, currency, counterparty, summary)` |
| 招行 | **仅有 `CmbMockBankDataAdapter`（13 行 mock）**，无 cmb 专属包 |
| 参照 | 中信 `adapter/citic/` 全套（codec/envelope/properties）可照抄架构 |

### 7.2 需新建（cmb 包，参照 citic 包结构）

```
adapter/cmb/
├── CmbAdapterProperties.java    # url(测试/生产), uid, privateKey, publicKey, symKey, funcode 映射
├── CmbCryptoHelper.java         # 移植 DcHelper: SM2签名(SM2withSM3) + SM4-CBC + ASCII排序 + reqid 生成
├── CmbHttpGateway.java          # POST x-www-form-urlencoded: UID/ALG=SM/DATA/FUNCODE; 响应解密
├── CmbRequestBuilder.java       # head/body/signature 组装, funcode 常量
├── CmbResponseParser.java       # SM4 解密 → JSON → BankDataCollection 映射(含 ctnFlag→hasMore/queryAcctNbr→nextCursor)
├── CmbStatementQuery.java       # trsQryByBreakPoint 请求体
├── CmbBalanceQuery.java         # NTQADINF 请求体
└── RealCmbBankDataAdapter.java  # @ConditionalOnProperty real-enabled; adapterCode=CMB
```

### 7.3 安全与配置

- 密钥等敏感项进配置中心/环境变量，**严禁硬编码**（demo 里是明文占位，仅作联调用）
- 沿用 v0.3 安全底线：real 适配器默认关闭，须 `bankdata.adapter.call.real-adapters-enabled=true` + brand 开关双保险
- 依赖：`org.bouncycastle:bcprov-jdk15on:1.61` + `com.google.code.gson:gson`（或换 jackson 统一 FINFLOW 序列化，但 SM 签名排序逻辑按 demo 原样保留）

---

## 8. 联调/生产环境关键参数速查

| 项 | 测试 | 生产 |
|---|---|---|
| URL | `http://cdctest.cmburl.cn:80/cdcserver/api/v2` | `https://cdc.cmbchina.com/cdcserver/api/v2` |
| 银行公钥 | demo `publicKey` 占位（测试值） | demo 中另一段注释值（须与分行确认） |
| 出口 IP | 申请时填 ERP 测试出口 IP | 网银白名单生产出口 IP（FINFLOW 部署后 ECS IP） |
| 用户号 UID | 开放平台下发测试资源中给 | 生产网银 UBank 直联设置页 |
| 密钥 | SMKeyTool 选"测试"生成 | SMKeyTool 选"生产"生成 |

---

## 9. 参考资料索引

| 资料 | 位置 |
|---|---|
| 官方 Java 实现（第一参考） | `docs/cmb-clouddc/samples/免前置Demo/Api/Java/{DcHelper,ApiDemo}.java` |
| 免前置开发流程 | `docs/cmb-clouddc/markdown/4. 免前置对接开发流程.md` |
| 请求/响应处理 | `docs/cmb-clouddc/markdown/4.2请求处理流程.md` |
| 报文规范 | `docs/cmb-clouddc/markdown/1.1请求报文.md` `1.2返回报文.md` `2.报文规范.md` |
| 密钥生成 | `docs/cmb-clouddc/markdown/4.1密钥生成.md` |
| 网银操作 | `docs/cmb-clouddc/markdown/2.9/2.10/2.11/2.8` 等 |
| 余额/流水接口 | `docs/cmb-clouddc/markdown/9.批量查询余额NTQADINF.md` `10.账户交易信息查询trsQryByBreakPoint.md` |
| 密钥工具 | `docs/cmb-clouddc/samples/国密生成工具/SMKeyTool.exe` |
