# FINFLOW × 招行云直联 · 免前置联调傻瓜教程（测试资源已到位）

> 版本: v1.1 ｜ 2026-09-03 15:50（v1.1 = 路径 B 已联通）
> 依据: 招行《新版银企直联直连测试申请》回执 (上海映脉文化传播有限公司-免前置-20260903-150052.xlsx)
> 测试周期: **2026-09-02 ~ 2026-10-09**（过期需重新申请！）
> 适用: 小棵 / 部署工程师 / AI 三方协作

---

## 0. 一分钟结论（先读这节）

✅ **路径 B（ECS 联调）已于 2026-09-03 15:46-15:48 全部打通**：在 ECS(101.200.72.87) 上
   ① NTQADINF 余额查询 → SUC0000 + 余额 ¥816,065.34（真实数据）
   ② trsQryByBreakPoint 流水断点查询 → SUC0000 + 4 笔真实流水（详见 §4.2）
✅ **本机链路（路径 A 前置）已实测打通**：签名/加密/报文格式全对，只差 IP 白名单（见 §4.1）
⚠️ **路径 A 卡点**：白名单 IP = `101.200.72.87`（ECS），本机出口 IP = `223.70.105.194` 不在名单内；若要走路径 A 需网银自助加 IP

**两条联调路径（路径 B 已完成）：**

| 路径 | 做法 | 状态 |
|---|---|---|
| **B. ECS 联调** | 把联调工具拷到 ECS(101.200.72.87) 上跑（复用 docker 内 java，无需装 JDK） | ✅ **已完成**（见 §4.2） |
| **A. 本机联调** | 测试网银自助加本机 IP 到白名单 → 本机直接发请求 | 备选（约 10 分钟，见 §2） |

> 判定标准（重要！）：返回 `head.resultcode = SUC0000` **只代表银行已接收请求**，不代表业务成功——业务结果要再调查询接口确认（回执注意①）。
> ⚠️ 禁止把测试密钥用于生产；生产密钥必须重新生成（回执注意⑥）。

---

## 1. 测试参数速查表（来自回执，全部原文）

### 1.1 网络与认证

| 参数 | 值 |
|---|---|
| 测试接口地址 | `http://cdctest.cmburl.cn/cdcserver/api/v2`（端口 80） |
| 生产接口地址 | `https://cdc.cmbchina.com/cdcserver/api/v2`（端口 443） |
| 测试企业 | 上海映脉文化传播有限公司 |
| 开通功能 | **账务查询**（余额/流水查询类，非支付经办） |
| 出口 IP 白名单 | `101.200.72.87`（ECS，申请表已填） |
| 网银公众版(测试) | `http://fbpop.uat.cmbchina.cn/CmbBank_FB/UI/Login/FBPOPLogin.aspx`（Chrome/Edge/IE，非证书模式登录） |

### 1.2 用户与账户（两套，可并行测试）

| 项 | 用户 A | 用户 B |
|---|---|---|
| 网银登录用户 | 银企直连测试用户专用12A | 银企直连测试用户专用12B |
| **UID** | `U006855378` | `U006855387` |
| 登录密码 | `88889999`（报错则 `77441100`） | 同左 |
| 结算账户 | `128965327910000` | `128965327910001` |
| 户名 | 银企直连测试用户专用12 | 同左 |

### 1.3 国密密钥（SM，勿外传/勿用于生产）

| 密钥 | 值 |
|---|---|
| 对称密钥(SM4) | `VuAzSWQhsoNqzn0K` |
| 用户SM私钥 | `NBtl7WnuUtA2v5FaebEkU0/Jj1IodLGT6lQqwkzmd2E=` |
| 银行SM公钥 | `BNsIe9U0x8IeSe4h/dxUzVEz9pie0hDSfMRINRXc7s1UIXfkExnYECF4QqJ2SnHxLv3z/99gsfDQrQ6dzN5lZj0=` |

### 1.4 测试辅助信息

| 项 | 值 |
|---|---|
| 收款个人户(测转账用) | 6214830094198511 何桂香 / 6214830498976165 叶桂芳（招行深圳分行） |
| 他行收方(测跨行,不校验) | 行号 `102100099996`，户名"中国工商银行总行清算中心"，账号 2-32 位随便，开户地北京 |
| 网银 hosts | `202.105.111.62 fbpop.uat.cmbchina.cn` / `202.105.111.63 site.cmbchina.com` / `202.105.111.63 uatub.cmbchina.com` |

---

## 2. 路径 A：本机联调配置（推荐，一次性 ~10 分钟）

### Step 2.1 配 hosts（仅登录网银需要；调 API 不需要）

用管理员身份编辑 `C:\Windows\System32\drivers\etc\hosts`，追加三行（生产上线后**删除**！）：

```
202.105.111.62  fbpop.uat.cmbchina.cn
202.105.111.63  site.cmbchina.com
202.105.111.63  uatub.cmbchina.com
```

### Step 2.2 测试网银加 IP 白名单 ⭐（本机联调的关键）

1. 浏览器打开 `http://fbpop.uat.cmbchina.cn/CmbBank_FB/UI/Login/FBPOPLogin.aspx`
2. 用 12A 用户登录：用户名选"银企直连测试用户专用12A"、密码 `88889999`（报错换 `77441100`）
3. 菜单：**网银设置 → 企业管理 → 银企直连 → 设置与授权 → IP 白名单管理**
4. 添加本机出口 IP：**`223.70.105.194`**（如果换网络/重启路由器可能变化，届时以银行报错提示为准）
5. 保存后通常即时生效；若无效等 1~2 分钟或重登

> 若公司内网出口是固定 IP，可把办公网出口 IP 也加进去，多人联调互不阻塞。

### Step 2.3 网络连通性自检（可选）

```bash
telnet cdctest.cmburl.cn 80     # 能连上=网络通（回执注意④）
curl -v http://cdctest.cmburl.cn/cdcserver/api/v2   # 有 HTTP 响应即可，403 属预期
```

---

## 3. 联调工具（linklab，已就绪可跑）

### 3.1 文件清单

```
docs/cmb-clouddc/linklab/
├── DcHelper.java      ← 官方加解密核心（SM2签名+SM4加密，勿改）
├── CmbQryDemo.java    ← 联调探针：参数可用环境变量覆盖
└── out/               ← 编译产物
```

依赖（已下载到 `tmp/cmb-lib/`，无需联网）：`bcprov-jdk15on-1.61.jar` + `gson-2.8.6.jar`

### 3.2 本机编译 & 运行（默认查 U006855378 的 128965327910000 余额）

```bash
cd docs/cmb-clouddc/linklab
javac -encoding UTF-8 -cp "C:/Users/王小棵/Documents/ChatGPT/财务系统/tmp/cmb-lib/*" -d out DcHelper.java CmbQryDemo.java
java -cp "out;C:/Users/王小棵/Documents/ChatGPT/财务系统/tmp/cmb-lib/*" cmb.CmbQryDemo
```

**预期输出（白名单加好后）**：返回 JSON 中 `"resultcode":"SUC0000"` + `ntqadinfz` 余额记录。
**IP 未加白名单时**：`DCAT003-您的IP未定义在白名单中…`（见 §4，这是链路正常的信号）。

### 3.3 换账号/换接口（环境变量覆盖，不碰代码）

```bash
# 查用户 B 的账户余额
CMB_UID=U006855387 CMB_ACCNBR=128965327910001 java -cp "out;C:/.../cmb-lib/*" cmb.CmbQryDemo

# 流水断点续传查询 trsQryByBreakPoint（v1.1 起内置报文；cardNbr 默认=CMB_ACCNBR，可另设 CMB_CARDNBR）
# 日期区间默认 20260901~20260903，可 CMB_BEGINDATE/CMB_ENDDATE 覆盖；响应 Y1=续传键、Z1.ctnFlag=Y 需续传
CMB_FUNCODE=trsQryByBreakPoint CMB_BEGINDATE=20260901 CMB_ENDDATE=20260903 java -cp "out;C:/.../cmb-lib/*" cmb.CmbQryDemo
```

> 注：docker 容器跑环境变量用 `-e KEY=VALUE`；CmbQryDemo 内含测试密钥（勿提交 git，linklab 已 .gitignore）。

### 3.4 路径 B：ECS 上联调 ✅（2026-09-03 15:46 已执行成功）

1. 打包：`linklab/`（DcHelper + CmbQryDemo + out/）+ `tmp/cmb-lib/` 两个 jar，scp 到 ECS `101.200.72.87`（已落 `/opt/cmb-linklab/`，含 `run.sh` 一键脚本）
2. ECS 无需装 JDK：**复用 docker 镜像 `eclipse-temurin:17-jre` / `finflow-app:latest`**（`docker run --rm -v $PWD:/work -w /work --entrypoint java ...`）
3. 运行结果：出口 IP 天然在白名单内 → **直接 SUC0000**（见 §4.2）
4. 注意 ECS 若 NAT 出口多 IP，先 `curl ifconfig.me` 确认出口 IP = 101.200.72.87

ECS 侧文件（root）：`/opt/cmb-linklab/`（src=源码 / out=class / lib=jar / run.sh=一键运行）；本机同包在 `tmp/cmb-linklab-ecs.tar.gz`。

---

## 4. 已验证记录（2026-09-03 本机实测）

**请求**：NTQADINF（批量查余额）/ UID=U006855378 / 128965327910000 / bbknbr=12
**结果**：`IOException: DCAT003-您的IP未定义在白名单中223.70.105.194…`

**解读（trust-but-verify 口径）**：
| 验证点 | 结论 |
|---|---|
| DNS + 80 端口连通 | ✅ cdctest.cmburl.cn → 202.105.111.101，connect 0.11s |
| HTTP 表单四参数(UID/ALG=SM/DATA/FUNCODE) | ✅ 网关正常受理 |
| 密钥配对 / SM2 签名 / SM4 加密 / reqid 格式 | ✅ 全部通过（若错误会在更早环节报验签/解密错误，而非走到 IP 白名单校验） |
| IP 白名单 | ❌ 本机出口 223.70.105.194 不在名单 → 走路径 A 需加白名单（路径 B 不受影响，见 §4.2） |

### 4.2 ECS 实测全通（2026-09-03 15:46-15:48，路径 B，`/opt/cmb-linklab`）

**① NTQADINF 批量查余额**（UID=U006855378 / 128965327910000）：
```
head.resultcode = SUC0000；账户级 errcod = SUC0000
accblv=816065.34 账面 / avlblv=816064.12 可用 / hldblv=0.00 冻结
户名=银企直连测试用户专用12 · 活期 · 深圳分行(bbknbr=75) · 开户 20240530
```

**② trsQryByBreakPoint 流水断点查询**（区间 20260901~20260903，币种 10）：
```
head.resultcode = SUC0000
Z1 汇总: ctnFlag=N(已查完) / debitNums=4 笔 / debitAmount=-0.27 / creditAmount=0.00
Y1 续传键: {acctNbr=128965327980000, transDate=20260902, expectNextSequence=5}
Z2 明细 4 笔(9/2 支付测试)：-0.01 / -0.02 / -0.22 / -0.02，均收方=何桂香 6214830094198511(招行深圳)
  transSequenceIdn= C0547IL00009WTZ 等(幂等键可用) · yurRef= DSTEST20260902… · textCode=CPAA
```

**闭环验证（trust-but-verify）**：
| 验证点 | 结果 |
|---|---|
| 两接口跨请求一致性 | ✅ Z2 首笔前余额 816065.60 − 4 笔合计 0.27 = **816065.34 = NTQADINF 当前余额**，账实闭环 |
| 断点续传结构 | ✅ Y1/Z1 续传键正常返回；ctnFlag=N 表示无更多数据（>200 笔时 ctnFlag=Y，需带 queryAcctNbr+Y1 续查，见接口文档 §续传） |
| bbknbr 语义 | ✅ 请求可传分行号，但行内按账户归属返回真实机构（响应 bbknbr=75 深圳）——**接入时以账户归属为准** |
| 限流红线 | ✅ 两次查询间隔 >10s 均成功（同账号查询须 ≥10s，脚本勿死循环） |

---

## 5. 判定与排障速查

### 5.1 SUC0000 的正确理解（回执注意①）

- `resultcode=SUC0000` = 银行**已接收**请求，**不代表业务处理成功**
- 查询类：看响应里业务字段（如余额、流水记录）
- 经办类（本项目暂不涉及）：要调对应查询接口确认银行是否已收到，确认没有记录才可重发，**重发时业务参考号必须与原来一致**，否则可能重复提交

### 5.2 常见错误码

| 现象 | 含义 | 处理 |
|---|---|---|
| `DCAT003 您的IP未定义在白名单中` | 出口 IP 不在白名单 | §2.2 网银加 IP；或改从 ECS 发 |
| 返回密文乱码/解密失败 | 对称密钥错 or 响应处理 bug | 核对 SM4 密钥 |
| 业务码提示账号无权限 | UID 与账号不匹配 / 功能未开通 | 12A↔`…000`、12B↔`…001` 配对用；确认开通的是"账务查询" |
| `resultcode` 非 SUC0000 | 见 resultmsg 描述 | 按描述处理（回执注意①） |

### 5.3 限流与频率红线（回执注意③⑦）

- ⚠️ **账务/交易管家查询：同一账号间隔 ≥10 秒**才能再查（断点续传除外）——联调脚本别写成死循环！
- 定时任务**避开整点/半点/每 10 分钟高峰**触发
- 代发对账单(DCAGPPDF)若启用：大批量请求均匀延展，勿集中整点打

---

## 6. 下一步行动（可直接照做）

- [x] **路径 B（ECS 联调）** ✅ 2026-09-03 15:46-15:48 双接口全通（余额+流水，见 §4.2）
- [ ] **招行适配器落地**：FINFLOW 后端按《FINFLOW-招行免前置对接开发要点.md》实现 Cmb real adapter（替代现 CmbMockBankDataAdapter；密钥位用本表 §1.3；SM2 签名/SM4 加密可移植 linklab 已验证的 DcHelper 流程）
- [ ] 流水幂等键设计确认：transSequenceIdn 作为唯一键（对齐中信 oriNum 做法），raw 落库留痕
- [ ] （可选）路径 A：按 §2.2 把 `223.70.105.194` 加进测试网银 IP 白名单 → 本机直接调式更便捷
- [ ] 联调通过后：9/18 联通报告 + 生产前密钥重生成（测试密钥仅测试环境有效）

---

*配套文档：`FINFLOW-招行免前置对接开发要点.md`（技术细节）/ `FINFLOW-招行免前置AI行动手册.md`（分阶段任务）*
*本教程含测试密钥，请勿外传、勿提交公开仓库；测试密钥仅测试环境有效。*
