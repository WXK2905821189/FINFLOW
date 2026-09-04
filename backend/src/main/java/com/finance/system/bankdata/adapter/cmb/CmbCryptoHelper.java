package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithID;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Security;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * SM 国密 cryptographic core for the CMB CloudDC 免前置 adapter, ported verbatim from the
 * vendor demo {@code DcHelper.java} (samples/免前置Demo/Api/Java). Pure static utility, no
 * Spring wiring, so it can be unit-tested against the vendor's test key material directly.
 *
 * <p>Pipeline per request (see docs/cmb-clouddc/markdown/4.2请求处理流程.md):</p>
 * <ol>
 *   <li>recursive ASCII key sort of the JSON document (sigdat placeholder in place) = canonical source;</li>
 *   <li>SM2-with-SM3 sign over that source with the customer private key, userId = uid right-padded to 16 bytes;</li>
 *   <li>fill sigdat with Base64 of the 64-byte raw (r||s) signature, then SM4/CBC/PKCS7 encrypt the JSON;</li>
 *   <li>transport; response is SM4 ciphertext, decrypt then verify the bank signature.</li>
 * </ol>
 */
public final class CmbCryptoHelper {

    private static final int LENGTH_32 = 32;
    private static final int USERID_LEN = 16;
    private static final String SM2_CURVE = "sm2p256v1";
    private static final String SM4_TRANSFORM = "SM4/CBC/PKCS7Padding";
    private static final DateTimeFormatter REQID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter SIGTIM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] REQID_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        // The demo calls Security.addProvider in the application startup; we register once here
        // (idempotent) so the "BC" provider is available to Cipher.getInstance(..., "BC").
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private CmbCryptoHelper() {
    }

    /**
     * SM2 signing identity / SM4 IV: the enterprise uid, right-padded with '0' to exactly
     * 16 bytes (UTF-8). Longer uids are truncated to 16.
     */
    public static byte[] userId(String uid) {
        if (uid == null) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL, "CMB uid must not be null");
        }
        return (uid + "0000000000000000").substring(0, USERID_LEN).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Recursively serialize {@code json} with keys sorted by ASCII at every object level,
     * no whitespace. Mirrors DcHelper.recursiveKeySort; arrays keep element order and each
     * object element is sorted recursively. The sigdat placeholder value must be present in
     * the document before calling this.
     */
    public static String canonicalize(JsonObject json) {
        StringBuilder appender = new StringBuilder();
        appendCanonicalObject(json, appender);
        return appender.toString();
    }

    /** Convenience overload that parses a compact JSON string first. */
    public static String canonicalize(String json) {
        return canonicalize(new com.google.gson.JsonParser().parse(json).getAsJsonObject());
    }

    private static void appendCanonicalObject(JsonObject json, StringBuilder appender) {
        appender.append("{");
        Iterator<String> keys = new TreeSet<>(json.keySet()).iterator();
        boolean first = true;
        while (keys.hasNext()) {
            if (!first) {
                appender.append(",");
            }
            String key = keys.next();
            JsonElement val = json.get(key);
            if (val instanceof JsonObject object) {
                appender.append("\"").append(key).append("\":");
                appendCanonicalObject(object, appender);
            } else if (val instanceof JsonArray array) {
                appender.append("\"").append(key).append("\":[");
                boolean firstElement = true;
                for (JsonElement element : array) {
                    if (!firstElement) {
                        appender.append(",");
                    }
                    if (element instanceof JsonObject object) {
                        appendCanonicalObject(object, appender);
                    } else if (element instanceof JsonArray nested) {
                        appender.append("[");
                        boolean firstNested = true;
                        for (JsonElement nestedElement : nested) {
                            if (!firstNested) {
                                appender.append(",");
                            }
                            appender.append(nestedElement.toString());
                            firstNested = false;
                        }
                        appender.append("]");
                    } else {
                        appender.append(element.toString());
                    }
                    firstElement = false;
                }
                appender.append("]");
            } else {
                appender.append("\"").append(key).append("\":").append(val.toString());
            }
            first = false;
        }
        appender.append("}");
    }

    /** SM2-with-SM3 sign (raw 64-byte r||s, NOT DER), mirroring DcHelper.cmbSM2SignWithSM3. */
    public static byte[] sign(byte[] userId, byte[] privateKey, byte[] canonicalSource) {
        try {
            ECPrivateKeyParameters parameters = encodePrivateKey(privateKey);
            SM2Signer signer = new SM2Signer();
            signer.init(true, new ParametersWithID(parameters, userId));
            signer.update(canonicalSource, 0, canonicalSource.length);
            return decodeDerSignature(signer.generateSignature());
        } catch (Exception e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY, "CMB SM2 sign failed", e);
        }
    }

    /** SM2-with-SM3 verify against the bank public key, mirroring DcHelper.cmbSM2VerifyWithSM3. */
    public static boolean verify(byte[] userId, byte[] publicKey, byte[] canonicalSource, byte[] signature64) {
        try {
            ECPublicKeyParameters parameters = encodePublicKey(publicKey);
            SM2Signer signer = new SM2Signer();
            signer.init(false, new ParametersWithID(parameters, userId));
            signer.update(canonicalSource, 0, canonicalSource.length);
            return signer.verifySignature(encodeDerSignature(signature64));
        } catch (Exception e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY, "CMB SM2 verify failed", e);
        }
    }

    /** SM4/CBC/PKCS7 encryption; key is the 16-byte SM4 key, iv the 16-byte userId. */
    public static byte[] sm4Encrypt(byte[] key, byte[] iv, byte[] data) {
        return sm4(key, iv, data, Cipher.ENCRYPT_MODE);
    }

    /** SM4/CBC/PKCS7 decryption; key/iv as above. */
    public static byte[] sm4Decrypt(byte[] key, byte[] iv, byte[] data) {
        return sm4(key, iv, data, Cipher.DECRYPT_MODE);
    }

    private static byte[] sm4(byte[] key, byte[] iv, byte[] data, int mode) {
        try {
            SecretKeySpec spec = new SecretKeySpec(key, "SM4");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(SM4_TRANSFORM, BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(mode, spec, ivSpec);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY, "CMB SM4 transform failed", e);
        }
    }

    /** Unique request id: 17-digit yyyyMMddHHmmssSSS prefix plus 10 random alphanumerics (18..51 total). */
    public static String newReqId() {
        return REQID_FORMAT.format(LocalDateTime.now()) + randomSuffix(10);
    }

    /** signature.sigtim value: current time yyyyMMddHHmmss (24h). */
    public static String sigTime() {
        return SIGTIM_FORMAT.format(LocalDateTime.now());
    }

    private static String randomSuffix(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(REQID_ALPHABET[RANDOM.nextInt(REQID_ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static ECPrivateKeyParameters encodePrivateKey(byte[] value) {
        BigInteger d = new BigInteger(1, value);
        ECParameterSpec spec = ECNamedCurveTable.getParameterSpec(SM2_CURVE);
        ECDomainParameters ec = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(),
                spec.getH(), spec.getSeed());
        return new ECPrivateKeyParameters(d, ec);
    }

    private static ECPublicKeyParameters encodePublicKey(byte[] value) {
        if (value == null || value.length != LENGTH_32 * 2 + 1) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB public key must be a 65-byte uncompressed point (04 || x || y)");
        }
        byte[] x = new byte[LENGTH_32];
        byte[] y = new byte[LENGTH_32];
        System.arraycopy(value, 1, x, 0, LENGTH_32);
        System.arraycopy(value, LENGTH_32 + 1, y, 0, LENGTH_32);
        ECParameterSpec spec = ECNamedCurveTable.getParameterSpec(SM2_CURVE);
        ECPoint point = spec.getCurve().createPoint(new BigInteger(1, x), new BigInteger(1, y));
        ECDomainParameters ec = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(),
                spec.getH(), spec.getSeed());
        return new ECPublicKeyParameters(point, ec);
    }

    private static byte[] decodeDerSignature(byte[] der) throws Exception {
        ASN1InputStream stream = new ASN1InputStream(new ByteArrayInputStream(der));
        ASN1Sequence sequence = (ASN1Sequence) stream.readObject();
        Enumeration<?> enumeration = sequence.getObjects();
        BigInteger r = ((ASN1Integer) enumeration.nextElement()).getValue();
        BigInteger s = ((ASN1Integer) enumeration.nextElement()).getValue();
        byte[] out = new byte[LENGTH_32 * 2];
        System.arraycopy(format(r.toByteArray()), 0, out, 0, LENGTH_32);
        System.arraycopy(format(s.toByteArray()), 0, out, LENGTH_32, LENGTH_32);
        return out;
    }

    private static byte[] encodeDerSignature(byte[] signature64) {
        byte[] r = new byte[LENGTH_32];
        byte[] s = new byte[LENGTH_32];
        System.arraycopy(signature64, 0, r, 0, LENGTH_32);
        System.arraycopy(signature64, LENGTH_32, s, 0, LENGTH_32);
        ASN1EncodableVector vector = new ASN1EncodableVector();
        vector.add(new ASN1Integer(new BigInteger(1, r)));
        vector.add(new ASN1Integer(new BigInteger(1, s)));
        try {
            return new DERSequence(vector).getEncoded();
        } catch (Exception e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY, "CMB DER encode failed", e);
        }
    }

    private static byte[] format(byte[] value) {
        if (value.length == LENGTH_32) {
            return value;
        }
        byte[] bytes = new byte[LENGTH_32];
        if (value.length > LENGTH_32) {
            System.arraycopy(value, value.length - LENGTH_32, bytes, 0, LENGTH_32);
        } else {
            System.arraycopy(value, 0, bytes, LENGTH_32 - value.length, value.length);
        }
        return bytes;
    }
}
