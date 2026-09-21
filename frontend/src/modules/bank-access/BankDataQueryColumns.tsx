import { Alert, Collapse, Descriptions, Tag } from 'antd';
import { dateTime, displayValue, cleanText, money, dateOnly, isUnavailableStatus, isFailedStatus } from '../shared/format';
import { statusTagText } from '../shared/dict';
import { ACCOUNT_STATUS_TEXT, accountStatusColor, INFO_FLAG_TEXT, INTEREST_TYPE_TEXT, LOAN_CODE_TEXT, REVERSAL_TEXT, currencyText } from './bankQueryTexts';
import { resolveBankName } from './useBankNames';
import type { BankDataBalanceRow, BankDataProjectionPage, BankDataStatementRow } from '../../types';

/** 投影行类型：流水与余额二选一（列定义与详情抽屉共用）。 */
export type BankQueryRow = BankDataStatementRow | BankDataBalanceRow;

/**
 * V36 需求 2：成功态「已连接真实银行直联」横幅整删——数据本体即界面，成功不需要自证；
 * 错误态压缩为细条（banner）保留：直连未连接 / 查询失败必须报错，但不再占一大块说明文字。
 * 技术证据类的「真实银行直联报文」标注（报文页 / 报文抽屉）不属于本裁决范围，保留。
 */
export function BankProjectionState({ data }: { data?: BankDataProjectionPage<BankQueryRow> }) {
  if (!data) return null;
  const status = data.status;
  if (data.enabled === false || isUnavailableStatus(status)) {
    return <Alert type="error" banner showIcon message={<span>真实银行直联未连接 · {data.message || '服务端未启用真实银行适配器，无法获取银行数据。'}{status ? `（服务端状态：${statusTagText(status)}）` : ''}</span>} />;
  }
  if (isFailedStatus(status)) {
    return <Alert type="error" banner showIcon message={<span>银行查询失败 · {data.message || '银行接口未成功返回，请检查同步任务或稍后重试。'}{status ? `（服务端状态：${statusTagText(status)}）` : ''}</span>} />;
  }
  return null;
}

export function StatementDetail({ row }: { row: BankDataStatementRow }) {
  return (
    <>
      <Descriptions className="projection-detail" column={1} size="small" bordered>
      <Descriptions.Item label="交易时间">{dateTime(row.transactionTime)}</Descriptions.Item>
      <Descriptions.Item label="借贷码">{row.loanCode ? `${LOAN_CODE_TEXT[row.loanCode] || row.loanCode}（${row.loanCode}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="金额（带符号 / 银行口径）">{row.signedAmount === undefined ? '--' : <span className="mono">{money(row.signedAmount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="金额（记账口径）">{row.amount === undefined ? '--' : <span className="mono">{money(row.amount)}</span>}</Descriptions.Item>
      <Descriptions.Item label="交易后余额">{row.acctOnlineBal === undefined ? '--' : <span className="mono">{money(row.acctOnlineBal)}</span>}</Descriptions.Item>
      <Descriptions.Item label="流水号"><span className="mono">{displayValue(row.statementNo)}</span></Descriptions.Item>
      <Descriptions.Item label="本方账号"><span className="mono">{displayValue(row.accountMasked || row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方名称">{displayValue(row.counterpartyName)}</Descriptions.Item>
      <Descriptions.Item label="收付方账号"><span className="mono">{displayValue(row.ctpAcctNbr)}</span></Descriptions.Item>
      <Descriptions.Item label="收付方开户行">{displayValue(row.ctpBankName)}</Descriptions.Item>
      <Descriptions.Item label="币种">{currencyText(row.vendorCurrencyCode ?? row.currency, row.currency)}</Descriptions.Item>
      <Descriptions.Item label="你方摘要">{cleanText(row.remarkTextClt)}</Descriptions.Item>
      <Descriptions.Item label="网银业务摘要">{cleanText(row.businessText)}</Descriptions.Item>
      <Descriptions.Item label="扩展摘要">{displayValue(row.extendedRemark)}</Descriptions.Item>
      <Descriptions.Item label="业务名称">{displayValue(row.businessName)}</Descriptions.Item>
      <Descriptions.Item label="票据号">{displayValue(row.billNumber)}</Descriptions.Item>
      <Descriptions.Item label="冲账标志">{row.reversalFlag ? `${REVERSAL_TEXT[row.reversalFlag] || row.reversalFlag}（${row.reversalFlag}）` : '--'}</Descriptions.Item>
      <Descriptions.Item label="信息标志">{row.infoFlag === undefined ? '--' : (INFO_FLAG_TEXT[row.infoFlag] || row.infoFlag)}</Descriptions.Item>
    </Descriptions>
    <Collapse
      ghost
      size="small"
      items={[{
        key: 'raw',
        label: '银行原始字段（技术明细，日常对账一般不用）',
        children: (
          <Descriptions className="projection-detail" column={1} size="small" bordered>
            <Descriptions.Item label="起息日">{dateOnly(row.valueDate)}</Descriptions.Item>
            <Descriptions.Item label="记账方向">{displayValue(row.direction)}</Descriptions.Item>
            <Descriptions.Item label="收付方开户行地址">{displayValue(row.ctpBankAddress)}</Descriptions.Item>
            <Descriptions.Item label="母子公司账号"><span className="mono">{displayValue(row.fatOrSonAccount)}</span></Descriptions.Item>
            <Descriptions.Item label="母子公司名称">{displayValue(row.fatOrSonCompanyName)}</Descriptions.Item>
            <Descriptions.Item label="母子公司开户行">{displayValue(row.fatOrSonBankName)}</Descriptions.Item>
            <Descriptions.Item label="母子公司开户行地址">{displayValue(row.fatOrSonBankAddress)}</Descriptions.Item>
            <Descriptions.Item label="网银流程实例号"><span className="mono">{displayValue(row.requestNbr)}</span></Descriptions.Item>
            <Descriptions.Item label="网银业务参考号"><span className="mono">{displayValue(row.yurRef)}</span></Descriptions.Item>
            <Descriptions.Item label="虚拟户编号">{displayValue(row.virtualNbr)}</Descriptions.Item>
            <Descriptions.Item label="商务支付订单号">{displayValue(row.mchOrderNbr)}</Descriptions.Item>
            <Descriptions.Item label="记账卡号">{displayValue(row.transCardNbr)}</Descriptions.Item>
            <Descriptions.Item label="保留字">{displayValue(row.reserve)}</Descriptions.Item>
          </Descriptions>
        ),
      }]}
    />
    </>
  );
}

export function BalanceDetail({ row }: { row: BankDataBalanceRow }) {
  return (
    <>
      <Descriptions className="projection-detail" column={1} size="small" bordered>
        <Descriptions.Item label="截止时间">{dateTime(row.asOfTime)}</Descriptions.Item>
        <Descriptions.Item label="银行">{displayValue(row.bankCode ? resolveBankName(row.bankCode) : undefined)}</Descriptions.Item>
        <Descriptions.Item label="账号"><span className="mono">{displayValue(row.accountMasked || row.bankAccountNo)}</span></Descriptions.Item>
        <Descriptions.Item label="银行侧账号"><span className="mono">{displayValue(row.bankAccountNo)}</span></Descriptions.Item>
        <Descriptions.Item label="户名">{displayValue(row.bankAccountName)}</Descriptions.Item>
        <Descriptions.Item label="可用余额">{row.availableBalance === undefined ? '--' : <span className="mono">{money(row.availableBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="联机余额">{row.onlineBalance === undefined ? '--' : <span className="mono">{money(row.onlineBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="冻结余额">{row.frozenBalance === undefined ? '--' : <span className="mono">{money(row.frozenBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="上日余额">{row.previousDayBalance === undefined ? '--' : <span className="mono">{money(row.previousDayBalance)}</span>}</Descriptions.Item>
        <Descriptions.Item label="币种">{currencyText(row.vendorCurrencyCode, row.currency)}</Descriptions.Item>
        <Descriptions.Item label="账户状态">{row.accountStatus ? <Tag color={accountStatusColor(row.accountStatus)}>{ACCOUNT_STATUS_TEXT[row.accountStatus] || row.accountStatus}</Tag> : '--'}</Descriptions.Item>
      </Descriptions>
      <Collapse
        ghost
        size="small"
        items={[{
          key: 'raw',
          label: '银行原始字段（技术明细，日常对账一般不用）',
          children: (
            <Descriptions className="projection-detail" column={1} size="small" bordered>
              <Descriptions.Item label="科目">{displayValue(row.accountItem)}</Descriptions.Item>
              <Descriptions.Item label="分行号">{displayValue(row.branchCode)}</Descriptions.Item>
              <Descriptions.Item label="客户关系号">{displayValue(row.customerRelationNo)}</Descriptions.Item>
              <Descriptions.Item label="开户日（opndat）">{row.openDate ? <span className="mono">{row.openDate}</span> : '--'}</Descriptions.Item>
              <Descriptions.Item label="利率类型（inttyp）">{row.interestType ? `${INTEREST_TYPE_TEXT[row.interestType] || row.interestType}（${row.interestType}）` : '--'}</Descriptions.Item>
              <Descriptions.Item label="存期（dpstxt）">{displayValue(row.depositTerm)}</Descriptions.Item>
              <Descriptions.Item label="透支额度（lmtovr）">{row.overdraftLimit === undefined || row.overdraftLimit === null ? '--' : <span className="mono">{money(row.overdraftLimit)}</span>}</Descriptions.Item>
              <Descriptions.Item label="利息码（intcod）">{displayValue(row.interestCode)}{row.interestCode === 'S' ? '（子公司虚拟余额）' : ''}</Descriptions.Item>
              <Descriptions.Item label="年利率（intrat）">{displayValue(row.interestRate)}</Descriptions.Item>
              <Descriptions.Item label="到期日（mutdat）">{row.maturityDate && row.maturityDate !== '00000000' ? <span className="mono">{row.maturityDate}</span> : '--'}</Descriptions.Item>
            </Descriptions>
          ),
        }]}
      />
    </>
  );
}
