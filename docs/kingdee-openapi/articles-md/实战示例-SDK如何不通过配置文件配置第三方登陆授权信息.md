# SDK如何不通过配置文件配置第三方登陆授权信息

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964394179335168&id=393036663222021120&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:46.446Z

---

业务背景

1、客户存在多个星空环境或者一个星空环境存在多个数据中心时，通过sdk的配置文件只允许配置一个第三方登录授权信息，这个时候应该怎么做系统集成对接呢？

2、第三方登录授权信息中配置了集成用户，做webapi集成时，操作用户就为配置信息中的集成用户，但是做不同操作需要切换不同的用户时，应该怎么来配置？

**实现方案**

当环境信息需要动态的变化时，这个时候我们就不能使用配置文件的方式初始化sdk了，需要通过参数化的方式动态的初始化sdk实例，传递不同的配置信息，不同的集成用户，再进行对应的接口调用。

.net sdk示例

![](https://vip.kingdee.com/download/010922a0ff7e99ec44f9bd629897d3380806.png)

.netcore3.1(.net5.0\.net6.0)版本支持传递整个对象

![](https://vip.kingdee.com/download/01093e46968a43294156858e76fb766b9a54.png)

Java sdk示例

![](https://vip.kingdee.com/download/0109b4885420bb754bc8b0aa7573ac3ba1f4.png)

python sdk示例

![](https://vip.kingdee.com/download/010937192f4cb9fc45d3a18ff820f8bbbf31.png)
