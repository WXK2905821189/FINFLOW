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
 * <p>{@code payload} is the parsed view stored when the page was collected;
 * {@code responsePayload} is the bank's decrypted response, verbatim (null on rows
 * captured before the ODS evidence columns existed, or after retention purge);
 * {@code requestEvidence} is the request-side facts JSON (endpoint, funcode, plaintext
 * request, duration, HTTP status, auxiliary exchange). {@code payloadBytes} /
 * {@code responsePayloadBytes} are reported alongside the bodies so a caller can tell
 * an empty-but-successful response from a truncated one without parsing the payload.
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
        int payloadBytes,
        String responsePayload,
        int responsePayloadBytes,
        String requestEvidence,
        boolean hasBankRaw
) {
}
