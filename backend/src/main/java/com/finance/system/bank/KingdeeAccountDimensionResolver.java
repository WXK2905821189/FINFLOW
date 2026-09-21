package com.finance.system.bank;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.statement.kingdee.BankAccountDimensionResolver;
import com.finance.system.statement.kingdee.KingdeeProperties;
import org.springframework.stereotype.Component;

/**
 * {@link BankAccountDimensionResolver} 实现（2026-09-21）：按流水所属账户取金蝶档案编码。
 *
 * <p>取值口径：</p>
 * <ol>
 *   <li>流水有账户归属 → 用该账户的 {@code kingdee_account_number}（账户级映射，见
 *   {@link KingdeeAccountMappingService#dimensionValueFor}）；账户未映射则**抛 400 阻断**
 *   并提示去「银行账户」页补映射；</li>
 *   <li>流水无账户归属（历史/导入流水）→ 回退配置项
 *   {@code KINGDEE_DEFAULT_BANK_ACCOUNT_NUMBER}（可为空；为空时由组装器给出提示）。</li>
 * </ol>
 */
@Component
public class KingdeeAccountDimensionResolver implements BankAccountDimensionResolver {

    private final BankAccountMapper bankAccountMapper;
    private final KingdeeAccountMappingService mappingService;
    private final KingdeeProperties props;

    public KingdeeAccountDimensionResolver(BankAccountMapper bankAccountMapper,
                                           KingdeeAccountMappingService mappingService,
                                           KingdeeProperties props) {
        this.bankAccountMapper = bankAccountMapper;
        this.mappingService = mappingService;
        this.props = props;
    }

    @Override
    public String resolve(StatementRecord statement) {
        if (statement == null || statement.getBankAccountId() == null) {
            return blankToNull(props.getDefaultBankAccountNumber());
        }
        BankAccount account = bankAccountMapper.selectById(statement.getBankAccountId());
        if (account == null) {
            return blankToNull(props.getDefaultBankAccountNumber());
        }
        String mapped = mappingService.dimensionValueFor(account);
        return blankToNull(mapped);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
