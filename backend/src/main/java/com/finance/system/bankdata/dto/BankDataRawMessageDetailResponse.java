package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;

/**
 * A captured bank response together with the payload it carried.
 *
 * <p>This is the one surface in the pipeline that hands out the raw response body,
 * which is exactly why it sits behind its own permission ({@code bankdata:raw:view})
 * instead of inheriting the broader {@code bankdata:view}: everything else exposes
 * digests only.
 *
 * <p>{@code payloadBytes} is reported alongside the body so a caller can tell an
 * empty-but-successful response from a truncated one without parsing the payload.
 */
public record BankDataRawMessageDetailResponse(
        Long id,
        Long taskId,
        String taskNo,
        Long bankAccountId,
        String adapterCode,
        String bankRequestNo,
        String contentSha256,
        LocalDateTime receivedAt,
        LocalDateTime retentionUntil,
        LocalDateTime purgedAt,
        boolean realDirect,
        String payload,
        int payloadBytes
) {
}
