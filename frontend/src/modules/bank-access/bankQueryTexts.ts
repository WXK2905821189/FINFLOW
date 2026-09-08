/**
 * Bank-data query pages: bank-side code → display text mappings and payload helpers.
 * Split out of BankDataQueryPage.tsx to keep the page component focused on state.
 */

/** 借贷码是银行自己的口径，不做业务翻译——翻译过一次就对不上银行导出的明细了。 */
export const LOAN_CODE_TEXT: Record<string, string> = { C: '贷方（收）', D: '借方（付）' };
export const REVERSAL_TEXT: Record<string, string> = { '*': '冲账', X: '补账', N: '正常' };
/** NTQADINF stscod：A=活动 B=冻结 C=关户。冻结/关户账户配着余额数字是 reconciliation 风险，须可视化。 */
export const ACCOUNT_STATUS_TEXT: Record<string, string> = { A: '活动', B: '冻结', C: '关户' };
export const accountStatusColor = (code?: string) => (code === 'A' ? 'green' : code === 'B' ? 'red' : code === 'C' ? 'default' : 'default');
/** NTQADINF inttyp（节选常见值，未收录的原样展示银行码）。 */
export const INTEREST_TYPE_TEXT: Record<string, string> = {
  ZZZ: '不计息', TD1: '定期7天', TD2: '定期3月', TD3: '定期6月', TD4: '定期1年',
  TD5: '定期2年', TD6: '定期3年', TD7: '定期5年', TD8: '定期1天', CQ: '活期',
};
export const INFO_FLAG_TEXT: Record<string, string> = {
  '': '付方账号 / 子公司',
  '1': '收方账号 / 子公司',
  '2': '收方账号 / 母公司',
  '3': '原收方账号 / 子公司',
};
/** 银行代码显示名（仅展示层映射；未收录代码原样显示）。 */
export const BANK_NAME_TEXT: Record<string, string> = { CMB: '招商银行' };

/** Pretty-print the bank payload; fall back to the raw text when it is not JSON. */
export const prettyPayload = (payload: string) => {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
};
