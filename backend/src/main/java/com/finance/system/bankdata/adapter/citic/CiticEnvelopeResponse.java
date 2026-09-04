package com.finance.system.bankdata.adapter.citic;

/** Parsed outer DLGECOMM response; {@code businessXml} is the decoded inner business XML. */
public record CiticEnvelopeResponse(
        String status,
        String statusText,
        String businessXml
) {
}
