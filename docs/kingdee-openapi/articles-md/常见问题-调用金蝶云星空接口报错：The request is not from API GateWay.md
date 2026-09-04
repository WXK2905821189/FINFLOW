# 调用星空接口报错：The request is not from API GateWay

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964512944566016&id=392982094270016512&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:47.045Z

---

以前公有云用户统一限制走网关，现在已经取消控制，但是有些客户用原来的公有云账套备份还原到私有云，控制参数还存在，需要数据库执行语句取消走网关控制

一、删除走网关控制

连接业务中心数据库，执行sql语句:

delete from T\_BAS\_USERPARAMETER where fkey = 'CDPIsForcedApiGW'

二、缓存管理中清除缓存

用管理员账号登录进去星空环境，

缓存管理中清除掉段名叫这个的：Kingdee\_BOS\_WebApi\_ForcedApiGW

![上传图片](https://vip.kingdee.com/download/0100bbd7d8b72b6947819dddc053365c2736.png)
