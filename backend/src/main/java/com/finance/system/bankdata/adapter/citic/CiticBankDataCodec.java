package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;

import java.nio.charset.Charset;
import java.util.Base64;

/** Pure codec and status boundary. It neither calls a bank nor accesses certificate material. */
public final class CiticBankDataCodec {

    private static final Charset GBK = Charset.forName("GBK");

    private CiticBankDataCodec() {
    }

    public static String encodeGbkBase64(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new BusinessException(400, "CITIC payload is required");
        }
        return Base64.getEncoder().encodeToString(payload.getBytes(GBK));
    }

    public static String decodeGbkBase64(String encodedPayload) {
        if (encodedPayload == null || encodedPayload.isBlank()) {
            throw new BusinessException(400, "CITIC encoded payload is required");
        }
        try {
            return new String(Base64.getDecoder().decode(encodedPayload), GBK);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "CITIC encoded payload is invalid");
        }
    }

    public static CiticParsedResponse parseStatus(CiticTransportResponse response) {
        if (response == null) {
            throw new BusinessException(400, "CITIC transport response is required");
        }
        String transportStatus = response.httpStatus() >= 200 && response.httpStatus() < 300
                && "SUCCESS".equalsIgnoreCase(response.transportCode()) ? "SUCCESS" : "FAILED";
        String businessStatus = businessStatus(response.businessCode());
        boolean accepted = "SUCCESS".equals(transportStatus)
                && ("SUCCESS".equals(businessStatus) || "PENDING".equals(businessStatus));
        return new CiticParsedResponse(transportStatus, businessStatus, trim(response.bankRequestNo()), accepted,
                "PENDING".equals(businessStatus) ? "CITIC response accepted but requires reconciliation"
                        : "UNKNOWN".equals(businessStatus) ? "CITIC response status is unknown and requires manual reconciliation"
                        : accepted ? "CITIC transport and business status accepted" : "CITIC response requires reconciliation");
    }

    private static String businessStatus(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return switch (value.trim().toUpperCase()) {
            case "SUCCESS", "AAAAAAA" -> "SUCCESS";
            case "PENDING", "PROCESSING", "AAAAAAE" -> "PENDING";
            case "FAILED", "EEEEEEE" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
