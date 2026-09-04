# 金蝶云星空 OpenAPI 对接知识底稿

> 来源：金蝶云社区 OpenAPI 专题及专题内文章
> 
> 整理日期：2026-08-31
> 
> 专题地址：<https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963348454780672&id=298029356990162688&type=Knowledge&productLineId=1&lang=zh-CN>

本文是项目对金蝶云星空 OpenAPI 的工作底稿。金蝶页面中的示例数据、应用 ID、应用密钥、账套 ID 和地址均为示例，不能直接用于生产。

## 一、整体认识

- 星空 OpenAPI 用于第三方系统与金蝶云星空之间的数据共享和业务集成。
- 技术形态是 HTTP + JSON 的 RESTful Web API。
- 官方 SDK 提供 .NET、Java、Python 版本，并封装请求参数、第三方授权签名、自动登录、会话管理和返回结果处理。
- 也可以自行发 HTTP 请求，但项目优先采用官方 SDK 或参照官方 SDK 封装统一客户端。
- 官方 API 文档中心支持接口说明、元数据预览、在线测试、模糊搜索和可视化参数填写。

官方入口：

- API 文档：<https://openapi.open.kingdee.com/ApiDoc>
- 在线入口：<https://openapi.open.kingdee.com/ApiHome>
- SDK 下载：<https://openapi.open.kingdee.com/ApiSdkCenter>

## 二、接入主流程

1. 明确星空版本、补丁版本、部署类型（公有云或私有云）、数据中心和目标业务对象。
2. 使用星空产品管理员账号进入“系统管理 -> 第三方系统登录授权”。
3. 新增授权，获取应用 ID 和应用密钥，并配置集成用户。
4. 将集成用户加入指定用户登录列表；如果勾选“允许全部用户登录”，则不需要逐个指定用户。
5. 保存授权并生成测试链接，确认账套 ID（数据中心 ID）和服务地址。
6. 下载与语言匹配的 SDK，配置授权信息。
7. 先用 API 中心在线测试接口和参数，再在项目中实现调用。
8. 对保存、提交、审核等有业务副作用的接口，增加幂等、日志、错误留痕和重试边界。

注意：补丁版本 PT146928（8.0.396.10）以上的授权界面可能采用新流程；旧版本 PT136657（7.3.1310.2）及以上的专题文章仍可作为参考，实际以当前环境界面和金蝶版本为准。

## 三、第三方授权配置

核心配置项如下：

| 配置项 | 含义 | 注意事项 |
| --- | --- | --- |
| `X-KDApi-AcctID` | 数据中心/账套 ID | 从生成测试链接等授权信息中确认 |
| `X-KDApi-UserName` | 集成用户 | 必须符合授权页面的用户范围 |
| `X-KDApi-AppID` | 应用 ID | 属于敏感配置，不应写入源码或日志 |
| `X-KDApi-AppSec` | 应用密钥 | 属于敏感配置，应使用密钥配置或环境变量 |
| `X-KDApi-LCID` | 账套语系 | 中文场景通常为 `2052` |
| `X-KDApi-OrgNum` | 组织编码 | 启用多组织时配置才生效 |
| `X-KDApi-ServerUrl` | 星空服务地址 | 一般要求以 `K3Cloud/` 结尾，实际按环境授权地址确认 |
| `X-KDApi-Proxy` | 代理配置 | 仅在需要代理时配置，具体格式按 SDK 版本说明 |

集成用户规则：

- PT-146894（7.7.0.202111）之前：授权信息中的集成用户必填，SDK 配置中的用户名必须是该用户。
- PT-146894 及以后：勾选“允许全部用户登录”时，可使用用户列表中的任一用户；未勾选时，SDK 配置用户必须在指定用户登录列表中。
- 集成用户仍需具备目标单据和操作的许可权限。
- 配置文件和项目文件使用 UTF-8；集成用户名含中文时尤其要检查编码。

## 四、SDK 使用要点

SDK 的关键行为：

- 一个 `K3CloudApi` 实例代表一个会话。
- 实例第一次成功调用时会登录并保存 `sessionId`，首次调用较慢；同一实例后续调用会复用会话。
- 多个星空环境、多个数据中心或需要切换集成用户时，不要只依赖单份配置文件，应按授权信息动态创建 SDK 实例。
- 对非 WebAPI 接口（接口类本身没有继承 `AbstractWebApiBusinessService`），先手动调用登录服务，再调用业务接口。

SDK 支持的常见标准操作包括：

`Save`、`BatchSave`、`BatchSaveQuery`、`Audit`、`Delete`、`UnAudit`、`Submit`、`View`、`ExecuteBillQuery`、`Draft`、`Allocate`、`ExecuteOperation`、`FlexSave`、`SendMsg`、`Push`、`GroupSave`、`Disassembly`、`QueryBusinessInfo`、`QueryGroupInfo`、`WorkflowAudit`、`GroupDelete`、`CancelAllocate`、`SwitchOrg`、`CancelAssign`、`GetSysReportData`、`AttachmentUpload`、`AttachmentDownLoad`。

> 具体接口是否可用取决于星空版本和补丁版本，必须在 API 文档中心核对。

### Java 项目建议

本项目后端为 Java，优先核对并引入最新 Java SDK。授权配置通常放在 `kdwebapi.properties`，典型字段为上表中的 `X-KDApi-*` 配置项。配置文件应放在 SDK 能读取到的位置，文章示例强调优先检查项目根目录和资源目录的读取逻辑。

建议项目封装以下边界：

- `KingdeeClientFactory`：按环境、账套、组织和集成用户创建客户端。
- `KingdeeSessionHolder`：复用会话，但对会话失效负责重新登录。
- `KingdeeApiExecutor`：统一超时、错误码解析、日志脱敏和重试策略。
- 业务适配器：按单据和业务流程封装 `Save`、`Submit`、`Audit`、`ExecuteBillQuery` 等调用，不在控制器中直接拼接金蝶 JSON。

## 五、在线测试与接口开发

推荐验证顺序：

1. 在 API 文档中按单据或基础资料名称搜索业务对象。
2. 选择操作接口，如“查看”“保存”“提交”。
3. 使用“API 在线测试”并先测试连接。
4. 查看类接口直接填写编号或 ID 等参数。
5. 保存类接口使用可视化参数填写功能，根据业务逻辑生成 JSON。
6. 将生成的 JSON 放入在线测试请求参数，确认返回结构后再编码。
7. 保存测试历史，作为联调样例和回归样例。

对于查询接口，`FormId`、`FieldKeys`、`FilterString`、`OrderString`、`TopRowCount`、`StartRow`、`Limit`、`SubSystemId` 是常见参数，但每个业务对象的字段和过滤条件仍需以 API 文档为准。

## 六、附件接口

### 上传

接口：`AttachmentUpload`。

常见参数包括：`FileName`、`FormId`、`IsLast`、`InterId`、`BillNo`、`AliasFileName`、`SendByte`、`FileId`。

- `SendByte` 是文件分块的 Base64 内容。
- 单据头附件的 `EntryInterId` 不填或填 `-1`。
- 大文件需要分块上传。
- 第一个分块的 `FileId` 为空；服务端返回后，从第二个分块开始必须携带返回的 `FileId`。
- 最后一块将 `IsLast` 设为 `true`。

### 下载

接口：`AttachmentDownLoad`。

常见参数包括：`FileId`、`StartIndex`。

- 返回结果包含 `FileName`、`FilePart`、`IsLast`、`StartIndex` 等信息。
- 小文件可一次读取并对 Base64 解码。
- 大文件按返回的 `StartIndex` 循环下载，按顺序解码并追加写入文件，直到 `IsLast=true`。
- 项目需要限制文件大小、校验下载分块顺序，并避免把文件内容写入业务日志。

## 七、自定义 WebAPI

标准自定义 API 的基本要求：

1. 接口实现类继承 `AbstractWebApiBusinessService`。
2. 编译 DLL 后放入 `K3Cloud\\website\\bin`。
3. 重启 IIS 使程序集生效。
4. SDK 调用地址格式为“接口命名空间.接口实现类名.方法,组件名”，SDK 会自动拼接 `common.kdsvc`。

如果接口是登录后的接口，必须先建立当前用户会话再调用；否则可能出现 `403 Forbidden ByRspRetStatusCode -- N001`。登录前可调用的接口应放在独立的 `.WebApi.` 子命名空间中。

## 八、日志与故障定位

### WebAPI 日志

V7.1 以上支持为业务数据中心单独创建或注册日志库。管理员进入“基础管理 -> 公共设置 -> 参数设置 -> 日志管理”：

- 错误日志强制记录。
- 业务日志和调试日志可配置。
- 可按业务对象设置记录范围。
- 可配置保留天数和自动清理规则。

私有云还可查看 `K3Cloud\\WebSite\\App_Data\\Log` 下的日志；文章示例中常见关键字为 `WebApiModule` 和 `ERROR - WebApiModule`。公有云日志通常需要通过运维提单获取。

### 常见问题

| 现象 | 优先排查 |
| --- | --- |
| “会话信息已丢失，请重新登录” | 升级 SDK；核对账套、ServerUrl、AppID、AppSec、集成用户和用户列表；确认配置文件位置及 UTF-8 编码；检查 cloud 日志 |
| `Fail to VerifyThirtyPassport` | 第三方授权不存在、被禁用，或应用 ID/密钥不正确 |
| `Fail to RestoreSessionByApi` 用户不存在 | 集成用户名乱码、用户不存在或无单据许可权限 |
| `时间戳验证失败，请求链接已失效` | 检查第三方授权链接有效时间，文章建议非 0 时使用默认值 10 |
| `The request is not from API GateWay` | 公有云备份迁移到私有云后可能残留强制走网关参数；需管理员和数据库维护人员按金蝶方案清理参数并清缓存 |
| `403 ... N001` | 当前用户未登录或接口需要登录上下文；优先使用官方 SDK，或先用 ApiClient 完成登录 |
| .NET `Newtonsoft.Json` 程序集冲突 | SDK 8.1.0/9.1.0 及以后通常不需要手动引用固定 4.0.0；独立项目可改用 NuGet；部署到产品 Bin 目录时优先使用产品自带客户端库 |

## 九、对本项目的落地要求

金蝶适配层至少应保留以下数据：

- 环境标识、服务地址、数据中心 ID、组织编码、集成用户。
- 授权应用 ID 的引用标识；应用密钥只存密文或安全配置，不进入普通业务表和日志。
- 金蝶业务对象 `FormId`、接口操作、请求 JSON、响应摘要、金蝶业务单据内码/编号。
- 请求追踪 ID、调用开始结束时间、HTTP 状态、金蝶 `Result.ResponseStatus`、错误码和错误信息。
- 会话失效次数、重新登录次数、重试次数和最终状态。

实现原则：

- 查询接口与写入接口分离；写入接口默认不自动重试，除非能证明请求未到达金蝶且业务具备幂等条件。
- `Save` 成功不等于后续 `Submit` 或 `Audit` 成功，按金蝶工作流和单据状态拆分处理。
- 业务单据保存前先在 API 在线测试中确认必填字段、基础资料编码、组织和分录结构。
- 金蝶返回的成功标识不能只看 HTTP 200，必须解析业务响应状态和错误明细。
- 多组织、多账套、多环境不能共享静态单例客户端配置；客户端实例和连接配置要有明确隔离。
- 生产日志脱敏，至少隐藏 `AppSec`、完整请求头、附件内容和可能包含敏感信息的业务字段。

## 十、待确认事项

以下内容专题文章没有给出项目环境的最终答案，需要结合当前金蝶环境确认：

- 当前星空产品版本和补丁版本，决定授权页面、SDK 版本和接口可用性。
- 公有云还是私有云，最终 `ServerUrl`、网络访问方式和日志获取方式。
- 当前账套 ID、组织编码、集成用户及其许可权限。
- 项目需要的具体业务对象、FormId、字段编码、单据状态流转和审批链。
- 是否需要附件上传下载、批量保存、分页查询、下推、分配或自定义 WebAPI。
- Java SDK 当前官方版本、JDK 兼容范围和是否存在与现有 Spring/Jackson 依赖的冲突。
- 金蝶接口的限流、超时、并发、会话有效期和生产运维支持规则。

