package com.finance.system.bankdata.adapter;

/**
 * Request-side facts captured around one adapter invocation, carried on
 * {@link BankDataCollection} so the sync executor can persist them next to the
 * parsed view.
 *
 * <p>Nothing here is business vocabulary — it is wire evidence. {@code responseText}
 * is the bank's decrypted response, verbatim: this is the value that makes the ODS
 * layer honest, because it survives independently of any parser defect.
 * {@code auxiliary} carries a secondary exchange made during the same collect call
 * (CMB page 1 also takes an NTQADINF balance snapshot before the statement page).</p>
 *
 * <p>Key material (symmetric keys, private keys) must never be placed in any field.
 * The executor serializes the request side into {@code request_evidence} explicitly
 * and keeps {@code responseText} out of that JSON — it lands in its own column.</p>
 */
public record BankExchangeEvidence(
        String endpoint,
        String funcode,
        String plainRequest,
        String responseText,
        Long durationMs,
        Integer httpStatus,
        AuxiliaryCall auxiliary) {

    /** A secondary exchange made while producing this collection (may be null). */
    public record AuxiliaryCall(String funcode, String plainRequest, String responseText,
                                Long durationMs, Integer httpStatus) {
    }
}
