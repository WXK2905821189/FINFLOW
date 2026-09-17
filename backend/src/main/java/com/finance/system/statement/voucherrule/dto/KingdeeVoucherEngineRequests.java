package com.finance.system.statement.voucherrule.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎端点请求体（WP-B）：批量预览解析 / 单条确认推送。
 */
public final class KingdeeVoucherEngineRequests {

    private KingdeeVoucherEngineRequests() {
    }

    /** POST /api/kingdee/voucher-rule/preview：流水 id 列表（不存在者跳过）。 */
    public record PreviewRequest(List<Long> statementIds) {
    }

    /**
     * POST /api/kingdee/voucher-rule/push：确认单条流水按某条规则制证。
     *
     * @param manualAmounts MANUAL 分录行的金额（key = 主凭证 debitLines+creditLines 拼接后的
     *                      0 基索引，第二张凭证紧随其后——与预览响应的字段顺序一致）
     */
    public record PushRequest(Long statementId,
                              Integer ruleNo,
                              Map<Integer, BigDecimal> manualAmounts) {
    }
}
