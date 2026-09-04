# 如何获取WebApi日志？

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963554177453824&id=298122933035956480&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:45.409Z

---

**背景**

通常，我们在进行WebAPI接口调用时，因为无法发现一些过程中的数据问题，所以我们可以通过借助WebAPI日志来分析问题。

**如何启用WebAPI日志？**

金蝶云V7.1以上版本已提供为业务数据中心单独创建日志库的功能，将业务数据中心与日志的存储分离。

登录管理中心→进入数据中心列表→选择业务数据中心，创建或注册日志库（根据是否创建过日志库选择对应方式）

![](https://vip.kingdee.com/download/0100a741e544fcc242a782f10970e6abe3df.png)

查看已经创建的日志库信息

![](https://vip.kingdee.com/download/01000a4ff8d4c15c42f7882ad0af6a7bc1c5.png)

![](https://vip.kingdee.com/download/0100436393007d8c4ffc8a98bb57ce9ba970.png)

administrator用户登录业务数据中心，进入【基础管理-公共设置-参数设置】，日志管理节点开启对应日志的记录

![](https://vip.kingdee.com/download/01007417a92972304020960a9a6cd36d8fc8.png)

![](https://vip.kingdee.com/download/01009e67bbae1bc54b45a7e6aebb16e4526b.png)

**日志启用**

1.【公共设置-参数设置-日志管理】，可以设置日志启用及记录范围。

2.记录类型：错误日志强制记录，业务日志与调试日志允许用户选择。

3.记录范围：默认选择业务对象，由用户在下方列表设置对哪些业务对象进行监控。

**自动清理**

1.【公共设置-参数设置-日志管理】，可以设置日志自动清理规则。

2. 通过设置日志保留天数及保留类型，对于保留范围之外的日志进行自动清理。

**如何查看WebAPI日志库记录信息**

![](https://vip.kingdee.com/download/0100587931f5a1d0462689dc6cb562e499b5.png)

![](https://vip.kingdee.com/download/01008e364cde74c54c0593b336aad3328378.png)

也可以通过查询 WebSite/AppData下面记录日志

记录在本地log 文件中 kingdee.bos.log

![](https://vip.kingdee.com/download/0100dd74d25769a74265969f51d3e0a4c94d.png)
