# 如何使用SDK?

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963554177453824&id=298050747973900544&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:44.972Z

---

如何使用SDK

欢迎使用星空OpenAPI-SDK。分别以 .Net/Java/Python SDK 为例，介绍如何使用、调试并接入星空API。

SDK支持的API列表

开发者需要清晰明确自己的星空版本，然后基于SDK进行开发。如下图所示。

|  |  |  |
| --- | --- | --- |
| 接口名称 | 接口含义 | 适用产品版本信息（按发布时间升序排列） |
| Save | 保存 | 2017-09-08，PT116278，7.0.352.16 |
| BatchSave | 批量保存 |
| BatchSaveQuery | 批量保存（轮询方式） |
| Audit | 审核 |
| Delete | 删除 |
| UnAudit | 反审核 |
| Submit | 提交 |
| View | 查看 |
| ExecuteBillQuery | 单据查询 |
| Draft | 暂存 |
| Allocate | 分配 |
| ExecuteOperation | 操作接口 |
| FlexSave | 弹性域保存 |
| SendMsg | 发送消息 | 2017-11-10，PT117342，7.1.512.1 |
| Push | 下推 | 2018-02-04，PT120306，7.1.606.1 |
| GroupSave | 分组保存 | 2018-08-10，PT123441，7.2.856.1 |
| Disassembly | 拆单 | 2020-05-26，PT-146836，7.5.1.202005 |
| QueryBusinessInfo | 查询单据信息 |
| QueryGroupInfo | 查询分组信息 |
| WorkflowAudit | 工作流审批 | 2020-10-15，PT-146854，7.5.1.202010 |
| GroupDelete | 分组删除 | 2020-11-26，PT-146861，7.6.0.202011 |
| CancelAllocate | 取消分配 | 2021-03-11,  PT-146867,  7.6.2108.8 |
| SwitchOrg | 切换组织接口 | 2021-04-22,  PT-146874,  7.6.2150.7 |
| CancelAssign | 撤销服务接口 |
| GetSysReportData | 获取报表数据 | 2021-07-22,  PT-146882 ,  7.7.2241.3 |
| AttachmentUpload | 上传附件 | 2021-12-02，PT-146897， 7.7.2374.8 |
| AttachmentDownLoad | 下载附件 |

依赖环境

![](https://vip.kingdee.com/download/01008c9c91344bdf4defb1f122bd27dd654e.png)

前置信息

首选需要获取第三方授权信息，具体操作查看《[如何获取第三方登录授权？](https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963554177453824&id=298030366575393024&productLineId=1)》

SDK下载

前往OpenAPI网站的SDK中心下载最新版本的SDK>> [前往下载](https://openapi.open.kingdee.com/ApiSdkCenter)

NET版本SDK使用指南

引用关键文件

开发者需引用资料包中SDK 文件夹内名为Kingdee.BOS.WebApi.Client.dll的文件。如下图所示。

![](https://vip.kingdee.com/download/010029137a7bbc994d62b2402efc43cfb10c.png)

第三方授权登录配置文件说明

将第三方系统登录授权信息，严格按照格式，加入第三方产品项目的配置文件中，示例如下。

<?xml version="1.0" encoding="utf-8" ?>

<configuration>

<appSettings>

<!-- 当前使用的 账套ID(即数据中心id) -->

*<!-- 第三方系统登录授权的账套ID（即open.kingdee.com网站的第三方系统登录授权中的数据中心标识）-->*

*<!-- 在第三方系统登录授权页面点击“生成测试链接”按钮后即可查看   -->*

<add key="X-KDApi-AcctID" value="5\*\*\*\*\*\*\*\*\*\*\*\*\*"/>

<!-- 第三方系统登录授权的 集成用户名称  -->

<!-- 补丁版本为PT-146894 [7.7.0.202111]及后续的版本，则为指定用户登录列表中任一用户  -->

<!-- 若第三方系统登录授权已勾选“允许全部用户登录”，则无以上限制  -->

<add key="X-KDApi-UserName" value="\*\*\*\*\*\*\*\*\*\*\*\*\*"/>

<!-- 第三方系统登录授权的 应用ID  -->

<add key="X-KDApi-AppID" value="207\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*"/>

<!-- 第三方系统登录授权的 应用密钥  -->

<add key="X-KDApi-AppSec" value="\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*e754a9"/>

<!-- 账套语系，默认2052  -->

<add key="X-KDApi-LCID" value="2052"/>

<!-- 组织编码，启用多组织时配置对应的组织编码才有效  -->

<!--<add key="X-KDApi-OrgNum" value="\*\*\*\*\*"/>-->

<!-- 服务Url地址(私有云和公有云都须配置星空产品地址，K3Cloud/结尾)-->

<add key="X-KDApi-ServerUrl" value="[http://\*\*\*\*\*\*\*\*\*\*\*\*\*/k3cloud/"/>](http://*************/k3cloud/"/>) 

</appSettings>

</configuration>

情况1：如果是基于本地桌面应用程序运行的项目工程，则新增App.config配置文件，加入上面的第三方系统登录授权信息，如下图所示。

![](https://vip.kingdee.com/download/0100b557f08096e2466383429f7814fe4c79.png)

（PS：该配置文件和对应的项目文件都应为utf-8编码，当用户名为中文名时尤其需要注意。）

Java版本SDK使用指南

引用关键文件

开发者需引用资料包中SDK 文件夹内名为k3cloud-webapi-sdk.jar的文件。如下图所示。

![](https://vip.kingdee.com/download/01003cd31e4bd93a48deb5326e931ab2eacc.png)

第三方授权登录配置文件说明

将第三方系统登录授权信息，严格按照格式，加入第三方产品项目的配置文件中，示例如下。

# 当前使用的 账套ID(即数据中心id)

# *第三方系统登录授权的账套ID（即open.kingdee.com网站的第三方系统登录授权中的数据中心标识）*

*# *在第三方系统登录授权页面点击“生成测试链接”按钮后即可查看**

X-KDApi-AcctID = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的 集成用户名称

# 补丁版本为PT-146894 [7.7.0.202111]及后续的版本，则为指定用户登录列表中任一用户

# 若第三方系统登录授权已勾选“允许全部用户登录”，则无以上限制

X-KDApi-UserName = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的应用ID

X-KDApi-AppID = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的应用密钥

X-KDApi-AppSec = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 账套语系，默认2052

# X-KDApi-LCID = 2052

# 组织编码，启用多组织时配置对应的组织编码才有效

# X-KDApi-OrgNum = \*\*\*\*\*\*\*

# 服务Url地址(私有云和公有云都须配置星空产品地址，K3Cloud/结尾)

X-KDApi-ServerUrl =\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

情况1：如果是基于本地应用程序运行的项目工程，则新增项目配置文件 kdwebapi.properties，如下图所示。

![](https://vip.kingdee.com/download/0100124e7f99402942a3a993efbf321149af.png)

（PS：该配置文件和对应的项目文件都应为utf-8编码，当用户名为中文名时尤其需要注意。）

Python版本SDK使用指南

引用关键文件

开发者需安装k3cloud\_webapi\_sdk文件。如下所示。

#使用pip安装指令格式：

pip install {后缀为.whl的SDK包文件的本地完整目录}

pip install F:\Python\k3cloud\_webapi\_sdk-1.0.0-py3-none-any.whl

第三方授权登录配置文件说明

将第三方系统登录授权信息，严格按照格式，加入第三方产品项目的配置文件中，示例如下。

# 服务Url地址(私有云和公有云都须配置星空产品地址，K3Cloud/结尾)

X-KDApi-ServerUrl = http://172.17.55.175/k3cloud/

# 当前使用的 账套ID(即数据中心id)

# *第三方系统登录授权的账套ID（即open.kingdee.com网站的第三方系统登录授权中的数据中心标识）*

*# *在第三方系统登录授权页面点击“生成测试链接”按钮后即可查看**

X-KDApi-AcctID = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的 集成用户名称

# 补丁版本为PT-146894 [7.7.0.202111]及后续的版本，则为指定用户登录列表中任一用户

# 若第三方系统登录授权已勾选“允许全部用户登录”，则无以上限制

X-KDApi-UserName = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的应用ID

X-KDApi-AppID = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 第三方系统登录授权的应用密钥

X-KDApi-AppSec = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

# 账套语系，默认2052

# X-KDApi-LCID = 2052

# 组织编码，启用多组织时配置对应的组织编码才有效

# X-KDApi-OrgNum = \*\*\*\*\*\*\*

# 若使用代理，配置此参数

# X-KDApi-Proxy = \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*

情况1：如果是基于本地应用程序运行的项目工程，则新增项目配置文件conf.ini节点为config：（配置参数名称不区分大小写）如下所示。

![](https://vip.kingdee.com/download/0100ccacdf9eb089424b86df7421f031bad9.png)

（PS：该配置文件和对应的项目文件都应为utf-8编码，当用户名为中文名时尤其需要注意。）

PHP版本SDK使用指南

引用关键文件

开发者需引用资料包中SDK 文件夹内名为kingdee-webapi-sdk-v8.0.5.phar的文件。如下图所示。

![](https://vip.kingdee.com/download/0109c951f047d5e14f88b86940ce9675e32d.png)

第三方授权登录配置文件示例说明

![上传图片](https://vip.kingdee.com/download/0100fbe912a44526421c886fb9d6f785e767.png)

![](https://vipstatic.obs.cn-north-4.myhuaweicloud.com/statics/webfront/ueditor/images/loading.gif)
