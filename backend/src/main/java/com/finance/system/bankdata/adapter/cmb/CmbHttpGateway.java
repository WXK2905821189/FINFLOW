package com.finance.system.bankdata.adapter.cmb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP transport + request signing/encryption + response decryption/verification for the CMB
 * CloudDC 免前置 gateway. Plain class (not a Spring bean) — the real adapter owns one instance.
 *
 * <p>Per request: canonicalize(document) → SM2 sign → fill sigdat → SM4/CBC encrypt → POST
 * form {@code UID/ALG=SM/DATA/FUNCODE} (DATA is URL-encoded Base64 ciphertext) → decrypt the
 * response body → verify the bank signature → return the decrypted JSON as text. A raw body
 * starting with {@code CDCServer:} is a gateway-layer error and is surfaced as
 * {@link CmbCallException.Kind#GATEWAY}.</p>
 */
public class CmbHttpGateway {

    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final CmbAdapterProperties properties;
    // NOT HtmlSafe: the default Gson escapes '=' to \u003d, which would corrupt Base64 sigdat
    // values in the transmitted plaintext and break the signatureValue indexOf/replace step.
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    static {
        // Vendor requirement: never auto-retry a POST (a retry after a timeout could duplicate
        // an operation that already reached the bank).
        try {
            System.setProperty("sun.net.http.retryPost", "false");
        } catch (SecurityException ignored) {
            // Property is a hint; refusing it must not block the gateway.
        }
    }

    public CmbHttpGateway(CmbAdapterProperties properties) {
        this.properties = properties;
    }

    /**
     * Sign + encrypt + POST + decrypt + verify, returning the decrypted response JSON.
     *
     * @param funcode  interface code, must match document head.funcode (form FUNCODE)
     * @param document plain-text request document incl. signature.sigdat placeholder
     */
    public String exchange(String funcode, JsonObject document) {
        requireConfigured();
        String uid = properties.getUid().trim();
        byte[] userId = CmbCryptoHelper.userId(uid);
        byte[] privateKey = B64_DECODER.decode(properties.getPrivateKey().trim());
        byte[] publicKey = B64_DECODER.decode(properties.getPublicKey().trim());
        byte[] symKey = properties.getSymKey().getBytes(StandardCharsets.UTF_8);

        // ① canonical source for signing (placeholder sigdat) → ② SM2-with-SM3 → fill sigdat.
        String canonical = CmbCryptoHelper.canonicalize(document);
        byte[] signature = CmbCryptoHelper.sign(userId, privateKey, canonical.getBytes(StandardCharsets.UTF_8));
        document.getAsJsonObject("signature").addProperty("sigdat",
                B64_ENCODER.encodeToString(signature));

        // ③ SM4/CBC encrypt the plain JSON.
        String plain = gson.toJson(document);
        byte[] cipherText = CmbCryptoHelper.sm4Encrypt(symKey, userId,
                plain.getBytes(StandardCharsets.UTF_8));

        // ④ form POST.
        Map<String, String> form = new LinkedHashMap<>();
        form.put("UID", uid);
        form.put("ALG", "SM");
        form.put("DATA", URLEncoder.encode(B64_ENCODER.encodeToString(cipherText), StandardCharsets.UTF_8));
        form.put("FUNCODE", funcode);
        String raw = httpPost(form);

        if (raw.startsWith("CDCServer:")) {
            throw new CmbCallException(CmbCallException.Kind.GATEWAY, raw);
        }

        // ⑤ decrypt → ⑥ verify bank signature → return text for the parser.
        byte[] decrypted;
        try {
            decrypted = CmbCryptoHelper.sm4Decrypt(symKey, userId, B64_DECODER.decode(raw.trim()));
        } catch (CmbCallException e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY,
                    "CMB response could not be decrypted (gateway returned an unreadable payload)", e);
        }
        String responseText = new String(decrypted, StandardCharsets.UTF_8);
        verifyResponse(responseText, userId, publicKey);
        return responseText;
    }

    /**
     * Verifies the bank signature. Primary path mirrors the vendor demo: single-occurrence
     * replace of the Base64 sigdat value with the placeholder, then verify the raw payload
     * bytes (the bank signs the document as transmitted). Fallback path canonical-sorts first
     * in case the bank ever emits a non-sorted response.
     */
    private void verifyResponse(String responseText, byte[] userId, byte[] publicKey) {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseText).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB decrypted response is not valid JSON", e);
        }
        JsonElement signatureElement = root.get("signature");
        String signatureValue = signatureElement == null ? null
                : signatureElement.getAsJsonObject().has("sigdat")
                ? signatureElement.getAsJsonObject().get("sigdat").getAsString() : null;
        if (signatureValue == null || signatureValue.isBlank()) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB response is missing signature.sigdat");
        }
        byte[] signature;
        try {
            signature = B64_DECODER.decode(signatureValue.trim());
        } catch (IllegalArgumentException e) {
            throw new CmbCallException(CmbCallException.Kind.SECURITY,
                    "CMB response signature is not valid Base64", e);
        }
        int index = responseText.indexOf(signatureValue);
        if (index < 0) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB response signature value is not present in the payload");
        }
        String replaced = responseText.substring(0, index) + CmbRequestBuilder.SIG_PLACEHOLDER
                + responseText.substring(index + signatureValue.length());
        if (CmbCryptoHelper.verify(userId, publicKey, replaced.getBytes(StandardCharsets.UTF_8), signature)) {
            return;
        }
        String canonical = CmbCryptoHelper.canonicalize(replaced);
        if (CmbCryptoHelper.verify(userId, publicKey, canonical.getBytes(StandardCharsets.UTF_8), signature)) {
            return;
        }
        throw new CmbCallException(CmbCallException.Kind.SECURITY,
                "CMB response signature verification failed");
    }

    private String httpPost(Map<String, String> form) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(properties.getUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(properties.getConnectTimeoutMs());
            connection.setReadTimeout(properties.getReadTimeoutMs());
            connection.setInstanceFollowRedirects(true);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type", FORM_CONTENT_TYPE);

            byte[] body = createLinkString(form).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String content = stream == null ? "" : readFully(stream);
            if (content.startsWith("CDCServer:")) {
                throw new CmbCallException(CmbCallException.Kind.GATEWAY, content);
            }
            if (code < 200 || code >= 300) {
                throw new CmbCallException(CmbCallException.Kind.TRANSPORT,
                        "CMB gateway HTTP " + code + (content.isEmpty() ? "" : ": " + content));
            }
            return content;
        } catch (CmbCallException e) {
            throw e;
        } catch (IOException e) {
            throw new CmbCallException(CmbCallException.Kind.TRANSPORT,
                    "CMB request to gateway failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Validates that the endpoint and all key material are present; throws PROTOCOL otherwise. */
    public void requireConfigured() {
        if (blank(properties.getUrl()) || blank(properties.getUid()) || blank(properties.getPrivateKey())
                || blank(properties.getPublicKey()) || blank(properties.getSymKey())) {
            throw new CmbCallException(CmbCallException.Kind.PROTOCOL,
                    "CMB adapter is enabled but gateway config is incomplete "
                            + "(bankdata.adapter.cmb url/uid/private-key/public-key/sym-key must all be set)");
        }
    }

    private static String createLinkString(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                builder.append("&");
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return builder.toString();
    }

    private static String readFully(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
