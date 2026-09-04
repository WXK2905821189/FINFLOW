# 12查询关联非结算户交易流水NonSettleTranQry

> 来源: 招行云直联文档中心 (bizkey=DCCT20201214145038074, 节点=2026082616401738702)
> 抓取: 2026-09-03T06:25:46.188Z

---

12查询关联非结算户交易流水NonSettleTranQry

 使用场景 

 

 使用说明 

 请求报文 

 

 接口名称 

 接口ID 

 数据类型 

 是否必输 

 描述 

 
查询接口

QueryNonSettleTranListByTaskY1

JSONArray

Y

见下面QueryNonSettleTranListByTaskY1描述

 

QueryNonSettleTranListByTaskY1（单记录）

 

 字段名称 

 字段ID 

 数据类型 

 是否必输 

 描述 

 
任务编号

taskNbr

string(32)

Y

 

 
账号

cardNbr

string(35)

Y

 

 
续传键值

continueKey

String

 
首次传空，当返回报文中包含此Y1接口且续传键值字段非空时，需要续传查询

 

 响应报文 

 

 接口名称 

 接口ID 

 数据类型 

 是否必输 

 描述 

 
续传接口

QueryNonSettleTranListByTaskY1

JSONArray

 
见下面QueryNonSettleTranListByTaskY1描述

 
结果列表

QueryNonSettleTranListByTaskZ1

JSONArray

 
见下面QueryNonSettleTranListByTaskZ1描述

 

QueryNonSettleTranListByTaskY1（多记录）

 

 字段名称 

 字段ID 

 数据类型 

 是否必输 

 描述 

 
任务编号

taskNbr

string(32)

 

 

 
账号

cardNbr

string(35)

 

 

 
续传键值

continueKey

String

 
当返回报文中包含此Y1接口且续传键值字段非空时，需要续传查询

 
任务状态

status

String

 
S: 成功

E: 出错

W: 等待执行

I: 处理中

 
结果说明

resultText

String

 

 

QueryNonSettleTranListByTaskZ1（多记录）

 

 字段名称 

 字段ID 

 数据类型 

 是否必输 

 描述 

 
交易账号

transCardNbr

String (35)

 

 

 
 非结算户类型 

 nonSettleType 

 string(1) 

 
 1&nbsp;定期 

 2保证金 

 3&nbsp;通知存款 

 4&nbsp;大额存单 

 5&nbsp;&nbsp;结构性存款 

 
记账交易流水

transSequenceIdn

String(15)

 

 

 
交易时间

transTime

String (6)

 

 

 
交易日期

transDate

D

 

 

 
起息日期

valueDate

D

 

 

 
记账序号

acctTransSeq

M

 

 

 
交易方向

direction

String(1)

 
C: 贷

D: 借

 
交易货币

currencyNbr

String(2)

 
两位币种代码

 
交易金额

transAmount

M

 

 

 
联机余额

acctOnlineBal

M

 

 

 
冲补标志

reversalFlag

String（1）

 
*为冲帐，X为补帐

N或者空， 代表非冲非补的常规交易

 
凭证种类

voucherType

String (4)

 

 

 
凭证号码

voucherNbr

String(20)

 

 

 
交易类型码

textCode

String (8)

 
见附录A.9

 
客户摘要

remarkTextClt

String (200)

 

 

 
对手方账户号码

ctpAcctNbr

String (35)

 

 

 
对手方账户户名

ctpAcctName

String (200)

 

 

 
对手方联行号码

ctpAcctBankId

String (20)

 

 

 
对手方银行名称

ctpAcctBankName

String (400)

 

 

 
收付方地址

remarkTextRcvAdr

String (200)

 

 

 
系统编号

remarkTextBusSys

String (2)

 

 

 
虚拟户编号

remarkTextNarInn

String (16)

 

 

 
客户参考号

remarkTextTrsRef

String (60)

 

 

 
商户号

remarkTextMchNbr

String (20)

 

 

 
业务编号

remarkTextBusNbr

String (20)

 

 

 
票据号码

remarkTextVchNbr

String(20)

 

 

 请求报文范例 

{

&nbsp;&nbsp;&nbsp;&nbsp;"request": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"body": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"QueryNonSettleTranListByTaskY1": [

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"cardNbr": "755994811599000",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"taskNbr": "c6eaa5e8b9094396bd33e47b8d0f605b"

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;]

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;},

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"head": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"funcode": "NonSettleTranQry",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"reqid": "202003161123456780001fbdev01",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"userid": "U004318028"

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;}

}

 响应报文范例 

{

&nbsp;&nbsp;&nbsp;&nbsp;"response": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"body": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"QueryNonSettleTranListByTaskY1": [

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"cardNbr": "755994811599000",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"resultText": " ",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"status": "W",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"taskNbr": "f4eec17e36d14cc48489f78eb1d7c0c4"

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;]

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;},

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"head": {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"bizcode": "",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"funcode": "NonSettleTranQry",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"reqid": "20250617111736905QCDC NonSettleTranQryU004318028",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"resultcode": "SUC0000",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"resultmsg": "",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"rspid": "202506171117369750001cdcserver-5986b7967b-xttkg",

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"userid": "U004318028"

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;}

}
