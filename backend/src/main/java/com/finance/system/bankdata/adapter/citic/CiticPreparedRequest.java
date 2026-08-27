package com.finance.system.bankdata.adapter.citic;

/** Contains an encoded business envelope only, never a credential or private key. */
public record CiticPreparedRequest(String requestId, String gbkBase64Payload, String certificateAlias) {
}
