/**
 * 推送失败诊断（2026-09-21 起）：把金蝶/网关的原始报错翻译成「原因 + 可执行动作」。
 *
 * <p>背景：真实账套首次推送暴露 `AR_RECEIVEBILL: 字段"往来单位"是必填项`，
 * 根因是自动建档的基础资料停在「暂存(A)」未审核（FIX-006），而报错文本完全看不出这一点。
 * 用户诉求：「报错一定要说明」——所以失败一律落到「原文 + 根因 + 步骤」三件套。</p>
 *
 * <p>本模块是纯函数，不依赖 React，后续规则引擎/批量结果也能复用同一套话术。</p>
 */

export type PushDiagnosis = {
  /** 一句话结论，如「金蝶拒收 · 基础资料未审核」 */
  headline: string;
  /** 给非技术使用者看的根因解释 */
  cause: string;
  /** 处置步骤，按顺序执行 */
  steps: string[];
  /** 判定依据（可追溯：哪条实测/哪份清单得出这个结论） */
  evidence?: string;
  /** 是否建议在本页直接「重试推送」（档案侧改完才有效） */
  retryable: boolean;
};

const hasAny = (text: string, ...needles: string[]) => needles.some((n) => text.includes(n));

/**
 * 识别已知失败形态。未命中返回通用诊断（仍给出原文与排查方向），命中需要新增形态时在此追加分支。
 */
export const diagnosePushFailure = (message?: string | null): PushDiagnosis | null => {
  const raw = (message || '').trim();
  if (!raw) {
    return null;
  }

  if (hasAny(raw, '基础资料未审核', '基础资料未提交')
    || (raw.includes('往来单位') && raw.includes('必填'))) {
    return {
      headline: '金蝶拒收 · 对手方档案未审核',
      cause: '对手方「' + extractedName(raw) + '」在金蝶已建档但不是「已审核」状态。'
        + '金蝶业务单据只能引用已审核的基础资料，暂存档案会被判定为未填该字段——不是字段漏传。',
      steps: [
        '在「金蝶 › 基础资料 › 客户/供应商」打开该档案，提交并审核；',
        '回到本页点「重试推送」（幂等，不会产生重复单据）；',
        '若该对手方实为集团内部主体，可改在规则里映射到组织机构维度，避免建外部档案。',
      ],
      evidence: '判定依据：ExecuteBillQuery BD_Customer 返回 FDocumentStatus=A（暂存）；见 docs/pending-fixes.md FIX-006',
      retryable: true,
    };
  }

  if (raw.includes('Counterparty not found in Kingdee base data')) {
    return {
      headline: '金蝶缺少该对手方档案',
      cause: '对手方在 BD_Customer / BD_Supplier 中都查不到，且系统未自动建档'
        + '（自动建档开关关闭或建档失败）。',
      steps: [
        '确认流水的对手方名称是否为有效企业名（银行返回的可能是账户简称或片段）；',
        '在金蝶手工建立客户/供应商档案，或开启对手方自动建档后重试；',
        '名称明显不完整时，先修正流水对手方名称再制证。',
      ],
      retryable: true,
    };
  }

  if (hasAny(raw, 'real gateway is not enabled', 'UNAVAILABLE')) {
    return {
      headline: '金蝶网关未启用',
      cause: '服务端的金蝶网关当前未切到真实模式（仍是模拟或未激活状态），推送没有真正发出。',
      steps: [
        '请运维确认已配置 KINGDEE_MOCK_MODE=false 与 KINGDEE_REAL_ENABLED=true 并重启服务；',
        '在「金蝶制证」页点「连接测试」，确认返回「已连接金蝶」；',
        '连接正常后回到本页重试推送。',
      ],
      retryable: true,
    };
  }

  if (raw.includes('借贷不平衡') || raw.includes('不平衡')) {
    return {
      headline: '分录借贷不平衡',
      cause: '凭证分录的借方合计与贷方合计不相等，金蝶拒绝保存。',
      steps: [
        '在凭证草稿工作台修正分录金额或增删分录行；',
        '确认合计行显示「借贷平衡」后再推送。',
      ],
      retryable: false,
    };
  }

  if (raw.includes('账期') && hasAny(raw, '已结账', 'CLOSED')) {
    return {
      headline: '账期已结账',
      cause: '该流水所属账期已结账（CLOSED），服务端对结账月份的制证与推送一律拦截。',
      steps: [
        '如需在已结账月份补单，请先由超管在「结账管理」解锁该账期；',
        '或改为按正确账期重新归集流水后制证。',
      ],
      retryable: false,
    };
  }

  return {
    headline: '金蝶拒绝了该单据',
    cause: '金蝶返回了未归类的校验错误，需要按原文逐项核对字段。',
    steps: [
      '阅读下方「金蝶原文」，错误通常直接点名字段；',
      '核对分录科目是否存在、金额是否平衡、往来单位是否已审核；',
      '处理后在「金蝶制证」页点「连接测试」确认链路，再回到本页重试推送。',
    ],
    retryable: true,
  };
};

/** 从报错文本里尽力取出对手方名（形如 （北分108））；取不到则给中性描述。 */
const extractedName = (raw: string): string => {
  const match = raw.match(/[（(]([^）)]{1,40})[）)]/);
  return match ? match[1] : '该对手方';
};
