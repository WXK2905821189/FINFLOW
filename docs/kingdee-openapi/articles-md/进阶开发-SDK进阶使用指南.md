# SDK进阶使用指南

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964434343990272&id=576099720138053888&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:46.171Z

---

**SDK设计目的**

SDK是为了简化用户的调用过程而诞生的，使开发更容易上手，只需要关注业务本身而不用理解复杂的http调用过程。

![上传图片](https://vip-admin.kingdee.com/download/01007172cd92f5b7459a99c462ccea4e0cd1.png)

**SDK主要做了以下工作：**

1、sdk封装处理了请求参数，用户只需要给具体的接口业务数据即可。详情参考<https://openapi.open.kingdee.com/ApiDoc>中的请求参数示例。

2、sdk中的每个接口调用时header都携带了第三方登录授权加密信息。第三方登录授权信息从配置文件中读取，或者由用户在代码中赋值。

3、sdk中的k3cloudApi类的一个实例就代表了一个会话。当新建了一个k3cloudApi的实例并执行第一次接口调用成功后，返回的sessionId将被存储，且该实例的每次接口调用都会携带sessionId。因此每个新的K3CloudApi实例在第一次调用接口时用时会较长，后续继续使用时速度会显著变快。

4、对返回结果做处理和封装。

 

对于非WebApi的接口(即接口本身没有继承AbstractWebApiBusinessService类）

调用时需要先进行一次手动登录，再执行后续操作。

JAVA示例如下：

K3CloudApi api= new K3CloudApi();

Object[] parameters = new Object[] { 

"1774791181494921216",//数据中心

 "demo2",//用户 

"266181\_247qxwCt4qAYwZ0I443tUzUsyMxZ2oKt",//appid

 "07317571e65c4781b68f8a55083b9176",//app密钥 

2052 };

//先执行一次登录

System.out.println(api.execute("Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret", parameters)); 

//再调用业务接口

String data = "{\n" + " \"FormId\": \"BD\_Department\",\n" + " \"FieldKeys\": \"FNUMBER,FNAME,FHELPCODE,FFORBIDSTATUS,FDEPTID,FUseOrgId.fnumber,FDocumentStatus \",\n" + " \"FilterString\": \"FNumber='BM000144'\",\n" + " \"OrderString\": \"\",\n" + " \"TopRowCount\": 0,\n" + " \"StartRow\": 0,\n" + " \"Limit\": 2000,\n" + " \"SubSystemId\": \"\"\n" + "}";

List list = api.executeBillQuery(data);

System.out.println(list);
