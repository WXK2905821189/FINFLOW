package com.finance.system.bankdata.adapter.cmb;

/**
 * Runtime failure of any stage of a CMB CloudDC call pipeline (transport / gateway /
 * crypto / parse). {@link Kind} lets callers and tests tell error classes apart.
 */
public class CmbCallException extends RuntimeException {

    public enum Kind {
        /** Gateway-layer error: raw response began with {@code CDCServer:}. */
        GATEWAY,
        /** HTTP-level failure (non-200 response, network error). */
        TRANSPORT,
        /** SM2 signature verification failure or SM4/decrypt failure. */
        SECURITY,
        /** Malformed JSON / missing expected fields / bad configuration values. */
        PROTOCOL
    }

    private final Kind kind;

    public CmbCallException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CmbCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
