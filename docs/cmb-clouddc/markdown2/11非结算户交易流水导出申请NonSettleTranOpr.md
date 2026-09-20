
11非结算户交易流水导出申请NonSettleTranOpr

使用场景

    客户希望通过线上渠道获取五类非结算账户（保证金、大额存单、结构性存款、定期存款、通知存款）的交易明细。

使用说明

    为向客户提供非结算账户（保证金、大额存单、结构性存款、定期存款、通知存款）的交易明细，利用结算引擎，支持客户按结算户导出其关联非结算账户的交易明细，同步提供直联接口。

请求报文

 |
接口名称
 |
接口ID
 |
数据类型
 |
是否必输
 |
描述
 |
 |
查询接口
 |
NonSettleTranExportTaskRegX1
 |
JSONArray
 |
Y
 |
长度为1，见下面NonSettleTranExportTaskRegX1描述
 |

NonSettleTranExportTaskRegX1（单记录）

 |
字段名称
 |
字段ID
 |
数据类型
 |
是否必输
 |
描述
 |
 |
账号
 |
cardNbr
 |
string(35)
 |
Y
 |

 |
 |
开始日期
 |
beginDate
 |
D
 |
Y
 |
yyyyMMdd

开始日期必须大于五年前的今天
 |
 |
结束日期
 |
endDate
 |
D
 |
Y
 |
yyyyMMdd，必须小于当天。
 |
 |
交易方向
 |
direction
 |
string(1)
 |

 |
空不筛选

C贷

D借
 |
 |
最小交易金额
 |
lowAmount
 |
M
 |

 |

 |
 |
最大交易金额
 |
highAmount
 |
M
 |

 |

 |
 |
非结算户类型
 |
nonSettleType
 |
string(1)
 |
Y
 |
1 定期

2保证金

3 通知存款

4 大额存单

5  结构性存款

*全部
 |

响应报文

 |
接口名称
 |
接口ID
 |
数据类型
 |
是否必输
 |
描述
 |
 |
响应接口
 |
NonSettleTranExportTaskRegZ1
 |
JSONArray
 |

 |
见下面NonSettleTranExportTaskRegZ1描述
 |

NonSettleTranExportTaskRegZ1

 |
字段名称
 |
字段ID
 |
数据类型
 |
是否必输
 |
描述
 |
 |
任务编号
 |
taskNbr
 |
string(32)
 |

 |

 |

请求报文范例

{

    "request": {

        "body": {

            "NonSettleTranExportTaskRegX1": [

                {

                    "cardNbr": "755947919810515",

                    "beginDate": "20230401",

                    "endDate": "20230502",

                    "nonSettleType": "*"

                }

            ]

        },

        "head": {

            "funcode": "NonSettleTranOpr",

            "reqid": "202003161123456780001fbdev01",

            "userid": "N002986827"

        }

    }

}

响应报文范例
