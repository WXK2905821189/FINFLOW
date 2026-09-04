# 403 Forbidden ByRspRetStatusCode -- N001报错原因分析及解决方案

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964512944566016&id=537229068048283648&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:47.322Z

---

403 N001是调用该请求时，当前用户不是登入状态，导致无权访问服务端接口。

参考：[【二开案例.WebApi.从零开发自定义WebApi接口】](https://vip.kingdee.com/article/97030089581136896?productLineId=1&isKnowledge=2)

原因分析：

1、如果是确认是登入前的WebApi接口，请确保该接口存在独立的.WebApi.子命名空间。

2、如果该接口是登入后的，请一定在当前用户登入后才调用该接口，否则提示N001找不到上下文信息。

解决方案：

1、推荐使用官方的sdk

参考：[标准自定义api编写并使用sdk调用](https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964434343990272&id=421729980586822912&productLineId=1)

2、如果使用的是ApiClient类，则参考webapi示例进行登录。

![上传图片](https://vip.kingdee.com/download/0100f1349cb21d3740809a75473532a5bac9.png)
