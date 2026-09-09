# yi-kd-web-client-java 评估报告（供 9/10 真实网关开发输入）

> 评估日期：2026-09-04 ｜ 评估对象：https://gitee.com/lnsyzjw/yi-kd-web-client-java（MIT）
> 对照基准：`docs/kingdee-openapi-knowledge-base.md`（我方接入底稿）
> 用途：判断该开源库对 FINFLOW「金蝶入账」模块（9/10 真实网关替换 Mock）的可借鉴/可引入程度
> 源码快照：`tmp/yi-kd-src/yi-kd-web-client-java-master/`（2026-09-04 下载，58 个 Java 文件，核心约 1200 行）

---

## 1. 仓库是什么

金蝶云星空 WebAPI 的第三方 Java 8 客户端库（C# 版 `yi-kd-web-client` 的多语言移植族，**非金蝶官方 SDK**）。核心入口 `YiK3CloudClient`，覆盖：

| 能力面 | 内容 |
|---|---|
| 认证模式（7 种） | K3Cloud 直连系：SHA256 签名 / SHA1 签名 / `LoginByAppSecret` 第三方授权 / 集成密钥 .cnf / 旧用户名密码；另有 **galaxyapi 网关签名** `LoginByApiSignHeaders`（X-Kd-Appkey / X-Api-Signature 独立请求头体系） |
| 动态表单操作 | Save / BatchSave / Submit / Audit / UnAudit / View / ExecuteBillQuery / Delete / 下推（ExecuteOperation） |
| 附加能力 | 自定义 WebAPI（.kdsvc 直连）、附件分块上传 `AttachmentHelper`（带进度回调）、SSO V1~V4、会话 Cookie 自动复用 + 手动 Logout |
| 工程形态 | 仅依赖 Jackson（**无厂商二进制 jar**）；自带 JUnit5 测试（不连真实金蝶）；**未上 Maven Central**（需本地 install 或整包引入） |
| 留痕设计 | `RequestWebModel` 记录每次调用的 RequestUrl / RealRequestBody / RealResponseBody（见 §4.1） |

## 2. 关键判断：不能直接引入，模式不完全等价

| 维度 | 该库主形态 | 我方目标形态（底稿 §3/§10） |
|---|---|---|
| 服务地址 | 私有云 `…/K3Cloud/` 结尾 | 开放平台网关（9/5 申请对象，待拍板） |
| 调用路径 | `Kingdee.BOS.WebApi.ServicesStub.*.common.kdsvc` stub | 开放平台网关 REST |
| 会话 | 登录接口 → Cookie/sessionId 复用 | SDK 网关形态（X-KDApi-* 八头） |
| 授权模型 | **同一套第三方授权**（AppID/AppSec/AcctID/UserName/LCID 字段同名，`GetLoginJson` 用 XKDApi* 命名） | 同左（字段同源） |

**结论**：授权配置模型同源，但调用与鉴权形态是「K3Cloud 直连系」。底稿 §10 待确认第 2 条（公有云开放平台 vs 私有云直连、最终 ServerUrl）未拍板前，直接整包引入有错配风险——**先卡形态，再定借法**（见 §5）。

## 3. 仓库结构速览（可裁剪范围）

```
YiKdWebClient/src/main/java/YiKdWebClient/
├── YiK3CloudClient.java            # 669 行，客户端门面（Login/Logout/Save/BatchSave/Submit/Audit/ExecuteBillQuery…）
├── CommonFunctionHelper.java       # ServerUrl 规范化、字段处理工具
├── AuthService/                    # 可插拔认证：每模式一个 service 类
│   ├── LoginByAppSecret.java       # 第三方系统登录授权（核心关注，§4.2）
│   ├── LoginBySign.java            # SHA256 签名信息
│   ├── LoginBySimplePassport.java  # 旧用户名密码
│   ├── LoginByApiSignHeaders.java  # galaxyapi 网关签名（X-Kd-Appkey/X-Api-Signature）
│   ├── ValidateLogin.java / ValidateUserEnDeCode.java / (集成密钥 .cnf 相关)
├── Model/                          # AppSettingsModel / LoginType / OperationType / RequestWebModel …
├── CommonService/                  # HttpTransport(JDK HttpURLConnection + CookieManager) / WebHelperServices / JsonHelperServices
├── ToolsHelper/AttachmentHelper.java   # 附件分块上传
└── ComWebHelper/WebHelper.java
```

认证可插拔设计（LoginType 枚举 + 每模式独立 service 类）正是底稿建议的 `KingdeeClientFactory / KingdeeApiExecutor` 边界形状，可作为我方包结构蓝本。

## 4. 对我方真正有价值的「零件」（MIT，可裁剪借鉴）

### 4.1 报文级留痕模型 —— 最值得抄（全文）

`Model/RequestWebModel.java` —— 正是我方「原始报文先落库 + request_id 追溯」（底稿 §9）的现成 Java 样板：

```java
package YiKdWebClient.Model;

import YiKdWebClient.CommonService.HttpTransport;
import java.net.CookieManager;

/** 一次实际 HTTP 请求及响应。 */
public class RequestWebModel {
    public CookieManager Cookie = HttpTransport.newCookieManager();
    public String RequestUrl = "";
    public String RealRequestBody = "";
    public String RealResponseBody = "";

    public CookieManager getCookie() { return Cookie; }
    public void setCookie(CookieManager value) { Cookie = value; }
    public String getRequestUrl() { return RequestUrl; }
    public void setRequestUrl(String value) { RequestUrl = value == null ? "" : value; }
    public String getRealRequestBody() { return RealRequestBody; }
    public void setRealRequestBody(String value) { RealRequestBody = value == null ? "" : value; }
    public String getRealResponseBody() { return RealResponseBody; }
    public void setRealResponseBody(String value) { RealResponseBody = value == null ? "" : value; }
}
```

客户端门面在类级再暴露两个实例字段，登录与业务操作各留一份（`YiK3CloudClient.java:39-40`）：

```java
public RequestWebModel ReturnLoginWebModel = new RequestWebModel();
public RequestWebModel ReturnOperationWebModel = new RequestWebModel();
```

对应我方落地：造我方 `KdRawMessage` 或等价 DTO 时，字段划分照抄 `RequestUrl/RequestBody/ResponseBody` 即可，无需重新设计；登录报文与业务报文分别留痕正好匹配「凭证推送失败时可查证是哪一步出的问题」。

### 4.2 第三方授权登录的报文骨架（LoginByAppSecret 核心逻辑）

`AuthService/LoginByAppSecret.java`（节选）——字段顺序即金蝶要求的数组顺序（AcctID/UserName/AppID/AppSec/LCID），`UnsafeRelaxedJsonEscaping` 对应 .NET 反序列化细节：

```java
public RequestWebModel Login(String url, String json) { return Login(url, json, true); }

public RequestWebModel Login(String url, String json, boolean UnsafeRelaxedJsonEscaping) {
    RequestWebModel model = new RequestWebModel();
    String loginUrl = CommonFunctionHelper.GetServerUrl(url)
            + "Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    model.RequestUrl = loginUrl;
    model.RealRequestBody = json;
    try {
        WebHelperServices web = new WebHelperServices();
        web.Timeout = Timeout;
        web.RequestHeaders = RequestHeaders;
        model.RealResponseBody = web.SendHttpRequest(loginUrl, json);  // 异常也写入 RealResponseBody
        model.Cookie = web.cookies;                                    // 会话 Cookie 落进 model
    } catch (Exception exception) {
        model.RealResponseBody = messageOf(exception);
    }
    return model;
}

public String GetLoginJson(AppSettingsModel settings, boolean UnsafeRelaxedJsonEscaping) {
    Object[] parameters = new Object[] {
            settings.getXKDApiAcctID(),
            settings.getXKDApiUserName(),
            settings.getXKDApiAppID(),
            settings.getXKDApiAppSec(),
            settings.getXKDApiLCID()
    };
    ...
}
```

注意 `settings.getXKDApi*` 五件套与底稿 §3 的 X-KDApi-* 八项头部字段名完全同源——**授权参数命名可直接对齐**，只是直连版走 Cookie、开放平台网关版走请求头。

### 4.3 会话生命周期管理

`YiK3CloudClient` 门面：
- 公开 `CookieManager Cookie` + `RequestHeaders`（可插 header）
- `Login()`（96 行起）/ `Logout()`（164 行起）
- 每个操作重载三档：`Save(formid, json)` / `+AutoLogin` / `+AutoLogin, AutoLogout` —— 可精确控制「整批共用会话」还是「单次独立会话」

对应底稿建议的 `KingdeeSessionHolder`：会话复用 + 过期自动重登 + 显式登出，这个重载粒度值得直接照搬（我方推凭证批量任务时按任务维度控制会话生命周期）。

### 4.4 附件分块上传

`ToolsHelper/AttachmentHelper.java`（FileId/IsLast/分块循环 + 进度回调）。底稿 §6 只给了接口约定，这里是有 Java 落地代码的参考；若我方后续凭证需挂附件（如回单影像），可直接对照。

### 4.5 依赖干净

仅 Jackson，无厂商 jar → 裁剪进 Spring Boot 3 / JDK17 无冲突顾虑。代价：Java 8 语法与 C# 移植命名风格需适配；未上 Central，若整包引入需 vendor 或本地 install。

## 5. 它补不上的（无论借不借都绕不开）

1. **无任何 GL_VOUCHER（凭证）示例** —— 凭证 JSON 结构、FormId、字段必填组合必须按目标环境 API 文档中心核对（底稿 §五 在线测试流程照旧）；
2. 错误处理只覆盖直连常见问题（连接/登录层），业务失败码（如 N001/403）需自研；
3. 自带测试不连真实金蝶，网关/字段正确性需我方用真实环境联调验证（9/11）。

## 6. 落地路径建议（配合 9/10 替换 Mock 派工）

**先卡形态，再定借法**（取决于 9/5 开通申请批复结果）：

| 分支 | 处置 | 预计复用度 |
|---|---|---|
| A. 申请到的是**开放平台网关** | 该库只作参考蓝本：抄 §4.1 留痕模型、§4.3 会话三档重载、§4.5 认证可插拔包结构；自行按开放平台 SDK 封装 `RealKingdeeVoucherGateway` | 思路级 60%+，代码级低 |
| B. 实际给的是 **K3Cloud 直连**（ServerUrl 带 K3Cloud/） | 裁剪整包接入（剥离 LoginBySign/SimplePassport/.cnf 等多余认证，只留 LoginByAppSecret），复用度 70%+，可显著压缩 9/10 交付风险 | 代码级 70%+ |

两分支共同要求（与该库无关、必须独立完成）：
- 凭证 FormId / JSON 结构与金蝶侧核对（ApiDoc 在线测试先行，见随附 OpenAPI 文档扒取记录）；
- `RealKingdeeVoucherGateway` 仍需遵守 FINFLOW 既有契约：`KingdeeVoucherResult(voucherNo,status,message)` + 状态机 CAS + 审计；
- 生产凭据不入库不提交（CI release-contract 拒金蝶生产凭据的现状保持）。

## 7. 结论

- 该库**不能直接引入**（形态为私有云 K3Cloud 直连 stub，与开放平台网关 REST 不兼容）；
- 但授权配置模型同源、5 类零件可直接裁剪借鉴（§4），作为 9/10 开发的参考蓝本价值明确；
- 形态决策（开放平台 vs 直连）是唯一卡住落地路径的外部依赖，优先级高于一切编码工作。

---
*附件：仓库源码快照 `tmp/yi-kd-src/yi-kd-web-client-java-master/`（仅本机，勿提交 git）；评估期间同步进行 openapi.open.kingdee.com ApiDoc 文档扒取（见 docs/kingdee-openapi/openapi-docs/，登录态 openapi-storage-state.json 勿进 git）。*
