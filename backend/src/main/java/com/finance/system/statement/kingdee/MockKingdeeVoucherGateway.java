package com.finance.system.statement.kingdee;

import com.finance.system.domain.entity.StatementRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kingdee", name = "mock-mode", havingValue = "true", matchIfMissing = true)
public class MockKingdeeVoucherGateway implements KingdeeVoucherGateway {

    @Override
    public KingdeeVoucherResult push(StatementRecord statement) {
        return new KingdeeVoucherResult(
                "KD-MOCK-" + statement.getStatementNo(), "PUSHED", "Accepted by Kingdee mock gateway");
    }

    @Override
    public KingdeeVoucherResult pushGlVoucher(String payloadJson) {
        return new KingdeeVoucherResult(
                "GL-MOCK-" + Math.abs(payloadJson.hashCode()), "PUSHED",
                "Accepted by Kingdee mock gateway (GL_VOUCHER draft)");
    }

    @Override
    public KingdeeConnectionStatus ping() {
        return new KingdeeConnectionStatus(false, "MOCK",
                "当前为模拟网关（kingdee.mock-mode=true），推送只产生模拟凭证号，未连接真实金蝶");
    }

    /**
     * 模拟账套科目表：仅保留方案 B 校验链路的判定所需样本——
     * 1002 挂银行账号维度（ZDY0001），1001/6603.04 无维度。
     * 真实环境请以账套实际科目表为准（Real 网关只读拉取 BD_Account）。
     */
    @Override
    public java.util.List<KingdeeAccountRef> queryAccountCatalog() {
        return java.util.List.of(
                new KingdeeAccountRef("1001", "库存现金", null),
                new KingdeeAccountRef("1002", "银行存款", "ZDY0001"),
                new KingdeeAccountRef("6603.04", "手续费", null),
                new KingdeeAccountRef("6602", "管理费用", null),
                new KingdeeAccountRef("2241.06", "外部往来", null));
    }

    /**
     * 模拟账套银行账号档案：含真实形态的样本——账号即编码（可自动匹配）、
     * 虚拟账户编码（须人工指定）、同账号跨组织重名（须组织消歧）。
     */
    @Override
    public java.util.List<KingdeeBankAccountRef> queryBankAccountCatalog() {
        return java.util.List.of(
                new KingdeeBankAccountRef("898902383810809", "北京雪云锐创科技有限公司", "400"),
                new KingdeeBankAccountRef("11050160520009100036", "北京雪云锐创科技有限公司", "400"),
                new KingdeeBankAccountRef("admin@xiaopiu.com", "北京雪云锐创科技有限公司", "400"),
                new KingdeeBankAccountRef("110922659010201", "招商银行股份有限公司北京首体科技金融支行", "410"),
                new KingdeeBankAccountRef("110922659010201", "招商银行上海分行营业部", "411"));
    }

    /**
     * 模拟档案状态回查：内置样例里 VEN0001/VEN0002/KH0001/EMP0001 已审核(C)，
     * VEN0003 暂存(A)——让导入回查链路在 mock 下也有可断言的行为（含暂存警示路径）。
     */
    @Override
    public java.util.Map<String, String> queryBaseDataDocumentStatus(String formId, java.util.Collection<String> numbers) {
        java.util.Map<String, String> statuses = new java.util.LinkedHashMap<>();
        for (String number : numbers) {
            if ("VEN0001".equals(number) || "VEN0002".equals(number)
                    || "KH0001".equals(number) || "EMP0001".equals(number)) {
                statuses.put(number, "C");
            } else if ("VEN0003".equals(number)) {
                statuses.put(number, "A");
            }
        }
        return statuses;
    }
}
