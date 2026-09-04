# 如何解决“未能加载文件或程序集Newtonsoft.Json

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964512944566016&id=572459225461495552&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:47.507Z

---

## **报错场景：**

![上传图片](https://vip.kingdee.com/download/01006d241d277ed04a58a5abf5e9a79ca581.png)

        用户使用[SDK中心-OpenAPI (kingdee.com)](https://openapi.open.kingdee.com/ApiSdkCenter)下载的适用于.net4.0的sdk为8.1.0和9.1.0版本及之后时，如果用户使用他们的同时手动引用Newtonsoft.json.dll组件会导致报错：

        未能加载文件或程序集“Newtonsoft.Json, Version=4.0.0.0, Culture=neutral, PublicKeyToken=30ad4fe6b2a6aeed”或它的某一个依赖项。找到的程序集清单定义与程序集引用不匹配。 (异常来自 HRESULT:0x80131040)

![上传图片](https://vip.kingdee.com/download/0100400439e35fdf4f6483cf324ed7b694e4.png)

## 产生原因：

        .net4.0SDK的8.1.0和9.1.0版本改动了对引用组件Newtonsoft.json.dll的无版本要求，即之前版本使用sdk时必须手动引用我们提供的Newtonsoft.json.dll包，固定版本为4.0.0。而.net4.0SDK的8.1.0和9.1.0版本后无需手动引用，而是使用NuGet管理器安装任意4.0.0版本以上的Newtonsoft.json即可使用。

对于用户的生产场景更灵活，减少和用户已有项目的同名dll的冲突。

### PS：

        这里对.net4.0SDK的8.1.0和9.1.0两个版本做出解释，8.1.0的版本sdk包名为Kingdee.BOS.WebApi.Client.dll,因为同产品包目录使用的Kingdee.BOS.WebApi.Client.dll同名，为了防止某些特殊情况下用户会需要同时引用这两个包的，因此更改了命名空间，重新命名包名为Kingdee.CDP.WebApi.SDK.dll,且版本从9.0.1开始。除了命名空间不同外，两个包的内容是完全一致的，官方更推荐使用Kingdee.CDP.WebApi.SDK。

![上传图片](https://vip.kingdee.com/download/010007494769fb4543ec8e1273c162687f95.png)

## 解决方案：

#### 场景1：

        用户使用的是.net4.0SDK的9.1.0或者8.1.0版本，手动引用了Newtonsoft.json.dll包。且用户的二开项目会单独部署后与产品端通信。

#### 方案1：

        移除原先的Newtonsoft.json引用，右键解决方案点击-》管理NuGet程序包，搜索Newtonsoft.json，选择自己需要的版本安装即可。

#### 场景2：

        同场景1，但不同的是用户二开后的包会放到K3Cloud\WebSite\Bin目录下，即作为产品本身的一部分与产品通信。

#### 方案2：

        首先声明，官方并不建议产品内的通信使用SDK，更建议使用产品内本身的Kingdee.BOS.WebApi.Client.dll包，而不是从SDK中心下载的专门提供给第三方调用的SDK包。

        由于产品内的Newtonsoft.json.dll包与NuGet管理器中下载的Newtonsofe.json.dll包有所不同，因此无法兼容，即使你本身项目执行没有问题，但是放到K3Cloud\WebSite\Bin产品目录下，会自动改为引用该目录下的Newtonsofe.json.dll包，导致程序集无法加载报错。

        如果用户坚持使用，建议改为.net4.0SDK的8.1.0和9.1.0版本之前的包，但是后续不会对该版本包进行升级维护。

#### 点击下载：

Kingdee.BOS.WebApi.Client.dll

[![](https://vipstatic.obs.cn-north-4.myhuaweicloud.com/statics/webfront/icon/ic_default.svg)V8.0.5](https://pkgsfile.open.kingdee.com/OpenApi/Net/NetSDK_V8.0.5.rar)

Kingdee.CDP.WebApi.SDK.dll

[![](https://vipstatic.obs.cn-north-4.myhuaweicloud.com/statics/webfront/icon/ic_default.svg)V9.0.2](https://pkgsfile.open.kingdee.com/OpenApi/Net/NetSDK_V9.0.2.rar)

#### 场景3：

        同场景1，但用户环境特殊或者vs版本过低导致无法使用nuget管理包。

#### 方案3：

        也可以到官网下载标准的Newtonsoft.json包到本地后手动引用，如果仍然不行，手动修改<解决方案名称>.csproj文件中的Newtonsofe.json引用相关代码。

参考如下：

<Reference Include="Newtonsoft.Json, Version=13.0.0.0, Culture=neutral, PublicKeyToken=30ad4fe6b2a6aeed, processorArchitecture=MSIL">

      <HintPath>..\Kingdee.CDP.WebApi.SDK\packages\Newtonsoft.Json.13.0.3\lib\net45\Newtonsoft.Json.dll</HintPath>

<SpecificVersion>False</SpecificVersion>

</Reference>
