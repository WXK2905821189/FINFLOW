package com.finance.system.bank.dto;

import java.util.List;

/** AI 公司主体归类建议（V32）：建议只读，应用走 ai-apply 端点由用户勾选确认。 */
public record AiCompanySuggestionResponse(List<Suggestion> suggestions, String model, Long durationMillis) {

    /** 单账户建议：suggestedCompanyName 可能是已有公司名，也可能是建议新建的公司名。 */
    public record Suggestion(Long accountId, String accountName, String suggestedCompanyName,
                             Double confidence, String reason) {
    }
}
