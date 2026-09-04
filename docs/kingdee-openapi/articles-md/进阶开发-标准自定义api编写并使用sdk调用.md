# 标准自定义api编写并使用sdk调用

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964434343990272&id=421729980586822912&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:45.888Z

---

如何编写一个自定义api接口，并使用sdk进行调用

## **一、编写标准自定义api接口**

1、自定义接口必须继承AbstractWebApiBusinessService

2、将编译成功的dll文件放入产品安装目录\K3Cloud\website\bin目录下，重启iis

![上传图片](https://vip.kingdee.com/download/01003d141529fc0141f593cc200ccb9c1507.png)

## 二、sdk调用方式

    sdk下载和使用请跳转：[sdk开发指南](https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229963554177453824&id=288990899248563456&productLineId=1)

    接口地址：接口命名空间.接口实现类名.方法,组件名（sdk会自动拼接common.kdsvc）

![上传图片](https://vip.kingdee.com/download/010090b7d939046c48638921a8d75b6758a3.png)
