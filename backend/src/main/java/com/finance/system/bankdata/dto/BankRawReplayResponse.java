package com.finance.system.bankdata.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of replaying a stored verbatim bank response through the current mapping rules
 * and diffing it against the view captured when the page was first collected.
 *
 * <p>{@code replayable} is false when the row predates the ODS evidence columns, was
 * purged, or its adapter has no replay mapping. {@code differences} lists JSON paths
 * where the replayed view disagrees with the stored one (capped), with volatile fields
 * (balance as-of timestamps, pagination cursor) excluded.</p>
 */
public record BankRawReplayResponse(
        Long rawMessageId,
        String adapterCode,
        String mappingVersion,
        boolean replayable,
        boolean matches,
        int storedEntryCount,
        int replayedEntryCount,
        List<String> differences,
        String replayedPayload,
        LocalDateTime replayedAt
) {
}
