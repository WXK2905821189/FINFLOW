package com.finance.system.bankdata.aggregation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vendor status vocabulary coverage for {@link BankDataStatus#fromVendor(String)}.
 *
 * <p>Regression anchor (2026-09-03 gap ticket): the CMB wire success code {@code SUC0000}
 * was missing from the vocabulary, so a real STATEMENT_PULL that the bank answered
 * successfully was classified UNKNOWN and its rows were never projected into
 * {@code bank_data_statement}. Keep this test aligned with every bank success code added
 * on the adapter side (CITIC uses AAAAAAA/AAAAAAE/EEEEEEE).
 */
class BankDataStatusTest {

    @Test
    void cmbSuccessCodeMapsToSuccess() {
        assertEquals(BankDataStatus.SUCCESS, BankDataStatus.fromVendor("SUC0000"),
                "CMB wire success code must project as SUCCESS");
    }

    @Test
    void citicSuccessCodeMapsToSuccess() {
        assertEquals(BankDataStatus.SUCCESS, BankDataStatus.fromVendor("AAAAAAA"),
                "CITIC wire success code must project as SUCCESS");
    }

    @Test
    void genericSuccessVocabularyMapsToSuccess() {
        assertEquals(BankDataStatus.SUCCESS, BankDataStatus.fromVendor("SUCCESS"));
        assertEquals(BankDataStatus.SUCCESS, BankDataStatus.fromVendor("OK"));
        assertEquals(BankDataStatus.SUCCESS, BankDataStatus.fromVendor("suc0000"),
                "vendor codes are normalized to upper case");
    }

    @Test
    void citicProcessingAndFailureCodesMap() {
        assertEquals(BankDataStatus.PENDING, BankDataStatus.fromVendor("AAAAAAE"));
        assertEquals(BankDataStatus.FAILED, BankDataStatus.fromVendor("EEEEEEE"));
    }

    @Test
    void unknownOrBlankVendorValueMapsToUnknown() {
        assertEquals(BankDataStatus.UNKNOWN, BankDataStatus.fromVendor("DCAT003"),
                "bank error codes outside the vocabulary conservatively require manual reconciliation");
        assertEquals(BankDataStatus.UNKNOWN, BankDataStatus.fromVendor(null));
        assertEquals(BankDataStatus.UNKNOWN, BankDataStatus.fromVendor("   "));
    }

    @Test
    void projectionGateFollowsSuccessOrPartial() {
        assertTrue(BankDataStatus.SUCCESS.allowsProjection());
        assertTrue(BankDataStatus.PARTIAL.allowsProjection());
        assertFalse(BankDataStatus.UNKNOWN.allowsProjection());
        assertFalse(BankDataStatus.PENDING.allowsProjection());
    }
}
