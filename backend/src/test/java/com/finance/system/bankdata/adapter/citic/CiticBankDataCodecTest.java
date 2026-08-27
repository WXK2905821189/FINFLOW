package com.finance.system.bankdata.adapter.citic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiticBankDataCodecTest {

    @Test
    void preservesGbkPayloadThroughBase64Boundary() {
        String encoded = CiticBankDataCodec.encodeGbkBase64("中信-模拟流水");

        assertEquals("中信-模拟流水", CiticBankDataCodec.decodeGbkBase64(encoded));
    }

    @Test
    void keepsTransportAndBusinessStatusesSeparate() {
        CiticParsedResponse accepted = CiticBankDataCodec.parseStatus(
                new CiticTransportResponse(200, "SUCCESS", "SUCCESS", "BANK-1", "unused"));
        CiticParsedResponse rejected = CiticBankDataCodec.parseStatus(
                new CiticTransportResponse(200, "SUCCESS", "FAILED", "BANK-2", "unused"));

        assertTrue(accepted.accepted());
        assertEquals("SUCCESS", accepted.transportStatus());
        assertEquals("SUCCESS", accepted.businessStatus());
        assertFalse(rejected.accepted());
        assertEquals("SUCCESS", rejected.transportStatus());
        assertEquals("FAILED", rejected.businessStatus());
    }

    @Test
    void acceptsOnlyLogicalCertificateAliases() {
        assertEquals("citic-sandbox-v1", new CiticCertificateReference("citic-sandbox-v1").alias());
        assertThrows(RuntimeException.class, () -> new CiticCertificateReference("-----BEGIN PRIVATE KEY-----"));
    }
}
