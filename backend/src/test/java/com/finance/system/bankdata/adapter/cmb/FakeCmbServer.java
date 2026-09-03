package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Test-only fake CMB gateway. It plays the bank side of the protocol over a real local
 * HTTP server: form parsing → SM4 decrypt → SM2 verify (customer public key) → canned
 * response → SM2 sign (bank private key) → SM4 encrypt → raw Base64 body back.
 *
 * <p>Key material is generated ephemerally per instance, so the full sign/encrypt/verify
 * chain is exercised with real cryptography without any vendor secret in the repo.</p>
 */
final class FakeCmbServer implements AutoCloseable {

    static final String TEST_UID = "N003261207";
    static final String TEST_SYM_KEY = "VuAzSWQhsoNqzn0K";

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final int LENGTH_32 = 32;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    private final String url;
    private final byte[] userId;
    private final byte[] customerPrivate;
    private final String customerPublicB64;
    private final byte[] bankPrivate;
    private final String bankPublicB64;
    private final byte[] extraPrivate;
    private final byte[] symKey;

    /** Canned business body per funcode, plaintext response JSON (response.head/body only). */
    private volatile String balanceResponse;
    private volatile String statementResponse;
    /** Optional forced failure modes (checked before canned responses). */
    private volatile String gatewayError;
    private volatile int httpErrorCode = -1;
    private volatile String httpErrorBody = "";
    /** When set, responses are signed with a third key (bank signature becomes invalid). */
    private volatile boolean wrongBankKey;

    FakeCmbServer() throws IOException {
        ECParameterSpec spec = ECNamedCurveTable.getParameterSpec("sm2p256v1");
        SecureRandom random = new SecureRandom();

        this.customerPrivate = randomScalar(spec, random);
        byte[] customerPublicPoint = pointBytes(spec, customerPrivate);
        this.bankPrivate = randomScalar(spec, random);
        byte[] bankPublicPoint = pointBytes(spec, bankPrivate);
        this.extraPrivate = randomScalar(spec, random);

        this.customerPublicB64 = B64.encodeToString(customerPublicPoint);
        this.bankPublicB64 = B64.encodeToString(bankPublicPoint);
        this.userId = CmbCryptoHelper.userId(TEST_UID);
        this.symKey = TEST_SYM_KEY.getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/cdcserver/api/v2", this::handle);
        this.server.setExecutor(executor);
        this.server.start();
        this.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cdcserver/api/v2";
    }

    /** Properties wired to this fake endpoint with the ephemeral keys (client-side identity). */
    CmbAdapterProperties clientProperties() {
        CmbAdapterProperties properties = new CmbAdapterProperties();
        properties.setUrl(url);
        properties.setUid(TEST_UID);
        properties.setPrivateKey(B64.encodeToString(customerPrivate));
        properties.setPublicKey(bankPublicB64);
        properties.setSymKey(TEST_SYM_KEY);
        properties.setRealEnabled(true);
        properties.setConnectTimeoutMs(5000);
        properties.setReadTimeoutMs(5000);
        return properties;
    }

    /** Bank-side identity (what the fake uses to verify/sign). */
    String bankPublicB64() {
        return bankPublicB64;
    }

    void respondBalance(String responseJson) {
        this.balanceResponse = responseJson;
        this.gatewayError = null;
        this.httpErrorCode = -1;
    }

    void respondStatement(String responseJson) {
        this.statementResponse = responseJson;
        this.gatewayError = null;
        this.httpErrorCode = -1;
    }

    void respondGatewayError(String message) {
        this.gatewayError = message;
    }

    void respondHttpError(int code, String body) {
        this.httpErrorCode = code;
        this.httpErrorBody = body;
    }

    /** Makes the bank sign with an unrelated key so the client-side verification must fail. */
    void signResponsesWithWrongKey() {
        this.wrongBankKey = true;
    }

    void clearRequests() {
        requests.clear();
    }

    List<CapturedRequest> requests() {
        return requests;
    }

    byte[] customerPrivate() {
        return customerPrivate;
    }

    byte[] userId() {
        return userId;
    }

    byte[] symKeyBytes() {
        return symKey;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            try {
                doHandle(exchange);
            } catch (Throwable t) {
                t.printStackTrace();
                throw t;
            }
        } finally {
            exchange.close();
        }
    }

    private void doHandle(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(readAll(exchange.getRequestBody()));
        CapturedRequest captured = new CapturedRequest(form.get("FUNCODE"), form.get("UID"),
                form.get("ALG"), form.get("DATA"));
        String decrypted = null;
        if (form.get("DATA") != null) {
            // parseForm already URL-decoded each field once; do NOT decode again here,
            // a second pass would turn Base64 '+' into a space (0x20) and corrupt the data.
            decrypted = new String(CmbCryptoHelper.sm4Decrypt(symKey, userId,
                    B64D.decode(form.get("DATA"))), StandardCharsets.UTF_8);
            captured.decryptedRequest = JsonParser.parseString(decrypted).getAsJsonObject();
            captured.signatureValid = verifyClientSignature(decrypted, captured.decryptedRequest);
        }
        requests.add(captured);

        String body;
        int code = 200;
        if (gatewayError != null) {
            body = "CDCServer:" + gatewayError;
        } else if (httpErrorCode != -1) {
            code = httpErrorCode;
            body = httpErrorBody;
        } else if ("NTQADINF".equals(captured.funcode)) {
            body = signedResponse(balanceResponse == null ? "{}" : balanceResponse);
        } else {
            body = signedResponse(statementResponse == null ? "{}" : statementResponse);
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    /** Verifies like the real bank: replace sigdat → canonical sort → SM2 verify. */
    private boolean verifyClientSignature(String decrypted, JsonObject root) {
        try {
            String signatureValue = root.getAsJsonObject("signature").get("sigdat").getAsString();
            int index = decrypted.indexOf(signatureValue);
            if (index < 0) {
                return false;
            }
            String replaced = decrypted.substring(0, index) + CmbRequestBuilder.SIG_PLACEHOLDER
                    + decrypted.substring(index + signatureValue.length());
            String canonical = CmbCryptoHelper.canonicalize(replaced);
            return CmbCryptoHelper.verify(userId, B64D.decode(customerPublicB64),
                    canonical.getBytes(StandardCharsets.UTF_8), B64D.decode(signatureValue));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Signs + encrypts a plaintext response JSON (response envelope only) like the real bank. */
    private String signedResponse(String responseJson) {
        byte[] signingKey = wrongBankKey ? extraPrivate : bankPrivate;
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonObject signature = new JsonObject();
        signature.addProperty("sigtim", CmbCryptoHelper.sigTime());
        signature.addProperty("sigdat", CmbRequestBuilder.SIG_PLACEHOLDER);
        root.add("signature", signature);
        String canonical = CmbCryptoHelper.canonicalize(root);
        byte[] raw = CmbCryptoHelper.sign(userId, signingKey,
                canonical.getBytes(StandardCharsets.UTF_8));
        root.getAsJsonObject("signature").addProperty("sigdat", B64.encodeToString(raw));
        // disableHtmlEscaping so Base64 '=' stays '=' in the transmitted plaintext (the client
        // locates the signature by indexOf of the decoded value).
        String plain = new GsonBuilder().disableHtmlEscaping().create().toJson(root);
        byte[] cipher = CmbCryptoHelper.sm4Encrypt(symKey, userId,
                plain.getBytes(StandardCharsets.UTF_8));
        return B64.encodeToString(cipher);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(key, value);
        }
        return form;
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static byte[] randomScalar(ECParameterSpec spec, SecureRandom random) {
        BigInteger n = spec.getN();
        BigInteger d;
        do {
            d = new BigInteger(n.bitLength(), random);
        } while (d.signum() == 0 || d.compareTo(n) >= 0);
        return fixed32(d);
    }

    private static byte[] pointBytes(ECParameterSpec spec, byte[] privateRaw) {
        BigInteger d = new BigInteger(1, privateRaw);
        ECPoint q = spec.getG().multiply(d).normalize();
        byte[] out = new byte[LENGTH_32 * 2 + 1];
        out[0] = 0x04;
        System.arraycopy(fixed32(q.getAffineXCoord().toBigInteger()), 0, out, 1, LENGTH_32);
        System.arraycopy(fixed32(q.getAffineYCoord().toBigInteger()), 0, out, 1 + LENGTH_32, LENGTH_32);
        return out;
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[LENGTH_32];
        if (raw.length > LENGTH_32) {
            System.arraycopy(raw, raw.length - LENGTH_32, out, 0, LENGTH_32);
        } else {
            System.arraycopy(raw, 0, out, LENGTH_32 - raw.length, raw.length);
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** What the fake observed for one client POST. */
    static final class CapturedRequest {

        final String funcode;
        final String uid;
        final String alg;
        final String data;
        JsonObject decryptedRequest;
        boolean signatureValid;

        CapturedRequest(String funcode, String uid, String alg, String data) {
            this.funcode = funcode;
            this.uid = uid;
            this.alg = alg;
            this.data = data;
        }
    }
}
