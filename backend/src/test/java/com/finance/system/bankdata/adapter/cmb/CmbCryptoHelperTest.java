package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 crypto core self tests. SM4 round-trips and identity vectors use the vendor demo's
 * built-in TEST key material (public test material — never production keys, never committed
 * configuration); the SM2 sign/verify round-trip derives the customer public key from the
 * demo private key since only the bank-side public key ships in the demo.
 */
class CmbCryptoHelperTest {

    // Test-only material from ApiDemo.java (招商银行官方示例，测试位占位).
    private static final String DEMO_PRIVATE_KEY_B64 = "NBtl7WnuUtA2v5FaebEkU0/Jj1IodLGT6lQqwkzmd2E=";
    private static final String DEMO_PUBLIC_KEY_B64 = "BNsIe9U0x8IeSe4h/dxUzVEz9pie0hDSfMRINRXc7s1UIXfkExnYECF4QqJ2SnHxLv3z/99gsfDQrQ6dzN5lZj0=";
    private static final String DEMO_SYM_KEY = "VuAzSWQhsoNqzn0K";
    private static final String DEMO_UID = "N003261207";

    @Test
    void userIdPadsUidWithZerosTo16Bytes() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        assertEquals(16, userId.length);
        assertEquals("N003261207000000", new String(userId, StandardCharsets.UTF_8));
    }

    @Test
    void userIdTruncatesOverLongUid() {
        assertEquals(16, CmbCryptoHelper.userId("N12345678901234567890").length);
    }

    @Test
    void sm4RoundTripWithVendorTestKeys() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        byte[] key = DEMO_SYM_KEY.getBytes(StandardCharsets.UTF_8);
        String plaintext = "{\"request\":{\"head\":{\"funcode\":\"NTQADINF\"}},\"signature\":{\"sigdat\":\"__signature_sigdat__\",\"sigtim\":\"20260903093000\"}}";
        byte[] cipher = CmbCryptoHelper.sm4Encrypt(key, userId, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] decrypted = CmbCryptoHelper.sm4Decrypt(key, userId, cipher);
        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    void sm4DecryptWithWrongKeyFails() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        byte[] key = DEMO_SYM_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] cipher = CmbCryptoHelper.sm4Encrypt(key, userId, "hello".getBytes(StandardCharsets.UTF_8));
        byte[] wrongKey = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        assertThrows(CmbCallException.class, () -> CmbCryptoHelper.sm4Decrypt(wrongKey, userId, cipher));
    }

    @Test
    void sm2SignVerifyRoundTripWithVendorTestPrivateKey() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        byte[] privateKey = Base64.getDecoder().decode(DEMO_PRIVATE_KEY_B64);
        byte[] source = "{\"request\":{\"body\":{\"accnbr\":\"755947919810515\"}},\"signature\":{\"sigdat\":\"__signature_sigdat__\",\"sigtim\":\"20260903093000\"}}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] signature = CmbCryptoHelper.sign(userId, privateKey, source);

        // SM2-with-SM3 raw signature is r||s, each 32 bytes → 64 bytes total.
        assertEquals(64, signature.length);
        // Public key derived from the demo private key (bank-side demo public key cannot
        // verify our customer signature).
        byte[] derivedPublic = derivePublicKey(privateKey);
        assertTrue(CmbCryptoHelper.verify(userId, derivedPublic, source, signature));
    }

    @Test
    void verifyRejectsTamperedSource() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        byte[] privateKey = Base64.getDecoder().decode(DEMO_PRIVATE_KEY_B64);
        byte[] derivedPublic = derivePublicKey(privateKey);
        byte[] source = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] signature = CmbCryptoHelper.sign(userId, privateKey, source);

        byte[] tampered = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
        assertFalse(CmbCryptoHelper.verify(userId, derivedPublic, tampered, signature));
    }

    @Test
    void verifyRejectsSignatureFromAnotherKey() {
        byte[] userId = CmbCryptoHelper.userId(DEMO_UID);
        byte[] privateKey = Base64.getDecoder().decode(DEMO_PRIVATE_KEY_B64);
        // Bank's own demo public key (different keypair) must NOT verify our signature.
        byte[] bankPublic = Base64.getDecoder().decode(DEMO_PUBLIC_KEY_B64);
        byte[] source = "{\"request\":{\"body\":{}}}".getBytes(StandardCharsets.UTF_8);
        byte[] signature = CmbCryptoHelper.sign(userId, privateKey, source);
        assertFalse(CmbCryptoHelper.verify(userId, bankPublic, source, signature));
    }

    @Test
    void canonicalizeSortsKeysRecursivelyAndKeepsArrayOrder() {
        String json = "{\"request\":{\"head\":{\"userid\":\"N002\",\"funcode\":\"NTQADINF\",\"reqid\":\"R1\"},"
                + "\"body\":{\"ntqadinfx\":[{\"accnbr\":\"A1\",\"bbknbr\":\"69\"}]}},"
                + "\"signature\":{\"sigdat\":\"__signature_sigdat__\",\"sigtim\":\"T1\"}}";
        String canonical = CmbCryptoHelper.canonicalize(json);
        // keys at each object level sorted; no spaces/newlines.
        assertEquals("{\"request\":{\"body\":{\"ntqadinfx\":[{\"accnbr\":\"A1\",\"bbknbr\":\"69\"}]},"
                + "\"head\":{\"funcode\":\"NTQADINF\",\"reqid\":\"R1\",\"userid\":\"N002\"}},"
                + "\"signature\":{\"sigdat\":\"__signature_sigdat__\",\"sigtim\":\"T1\"}}", canonical);
    }

    @Test
    void reqIdStartsWith17DigitTimestampAndIsUnique() {
        Pattern prefix = Pattern.compile("^\\d{17}[0-9A-Z]{10}$");
        String reqId = CmbCryptoHelper.newReqId();
        assertTrue(prefix.matcher(reqId).matches(), "unexpected reqid: " + reqId);
        assertEquals(27, reqId.length());

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(CmbCryptoHelper.newReqId());
        }
        assertEquals(2000, seen.size());
    }

    @Test
    void sigTimeIsFourteenDigitTimestamp() {
        assertTrue(Pattern.compile("^\\d{14}$").matcher(CmbCryptoHelper.sigTime()).matches());
    }

    /** Derives the uncompressed 04||x||y public point from a raw private scalar (sm2p256v1). */
    private byte[] derivePublicKey(byte[] privateRaw) {
        var spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("sm2p256v1");
        BigInteger d = new BigInteger(1, privateRaw);
        var point = spec.getG().multiply(d).normalize();
        byte[] out = new byte[65];
        out[0] = 0x04;
        byte[] x = fixed32(point.getAffineXCoord().toBigInteger());
        byte[] y = fixed32(point.getAffineYCoord().toBigInteger());
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }
}
