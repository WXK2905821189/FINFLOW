package com.finance.system.bankdata.adapter.citic.dlink;

import com.citicbank.dlink.sdk.open.DLinkSdkException;
import com.citicbank.dlink.sdk.open.DefaultOpenCommunication;
import com.citicbank.dlink.sdk.open.OpenCommunication;
import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.CiticEnvelopeCodec;
import com.finance.system.bankdata.adapter.citic.CiticEnvelopeResponse;
import com.finance.system.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real transport over the vendor CITIC DLink SDK (DLGECOMM).
 *
 * <p>Lives in the {@code citic-sdk} Maven profile source root so the pom only needs the
 * commercial jars (installed into local ~/.m2) when this profile is active; the 501
 * placeholder {@link UnavailableCiticDlinkSdk} remains the boundary otherwise.</p>
 *
 * <p>The bean activates on the same master switch as {@code RealCiticBankDataAdapter}
 * ({@code bankdata.adapter.citic.real-enabled=true}); configuration is injected into the
 * SDK through {@link OpenCommunication#setCfgPropertiesByte(byte[])} once, before the
 * singleton communication instance is created. Initialization is lazy so enabling the flag
 * without bank settings fails fast on the first real call instead of breaking startup.</p>
 */
@Component
@ConditionalOnProperty(prefix = "bankdata.adapter.citic", name = "real-enabled", havingValue = "true")
public class SdkCiticDlinkSdk implements CiticDlinkSdk {

    private static final Logger log = LoggerFactory.getLogger(SdkCiticDlinkSdk.class);

    /** Outer action is fixed by the vendor; the business action travels inside the payload. */
    private static final String OUTER_ACTION = "DLGECOMM";

    /** Vendor success marker on the outer response. */
    private static final String OK_STATUS = "AAAAAAA";

    private final CiticAdapterProperties.Sdk cfg;
    private volatile OpenCommunication communication;

    public SdkCiticDlinkSdk(CiticAdapterProperties properties) {
        this.cfg = properties.getSdk();
    }

    @Override
    public String exchange(String action, String businessXml, String clientId) {
        OpenCommunication comm = communication();
        String outerXml = CiticEnvelopeCodec.buildOuterRequest(
                requireConfigured(cfg.getUserName(), "user-name (CITIC_USER_NAME)"),
                businessXml,
                cfg.getCashFlag(),
                clientId);
        String outerResponse = sendOuter(comm, outerXml);
        CiticEnvelopeResponse envelope = CiticEnvelopeCodec.parseOuterResponse(outerResponse);
        if (!OK_STATUS.equals(envelope.status())) {
            throw new BusinessException(502, "CITIC transport rejected: status=" + envelope.status()
                    + (envelope.statusText() == null || envelope.statusText().isBlank() ? ""
                    : " text=" + envelope.statusText()));
        }
        if (envelope.businessXml() == null) {
            throw new BusinessException(502, "CITIC transport accepted but outer response carries no business content");
        }
        return envelope.businessXml();
    }

    @Override
    public String downloadCertificate(String downloadCode, String orgCode, String certPath) {
        OpenCommunication comm = communication();
        try {
            String statusXml = comm.cerMNG(downloadCode, orgCode, certPath);
            log.info("CITIC certificate download returned: {}", statusXml);
            return statusXml;
        } catch (Exception exception) {
            throw new BusinessException(502, "CITIC certificate download failed: " + exception.getMessage());
        }
    }

    /** Sends one outer DLGECOMM request. Overridable in tests. */
    protected String sendOuter(OpenCommunication comm, String outerXml) {
        try {
            return comm.send(OUTER_ACTION, outerXml, cfg.getCertPath());
        } catch (RuntimeException exception) {
            throw new BusinessException(502, "CITIC transport send failed: " + exception.getMessage());
        }
    }

    /** Returns the initialized singleton. Overridable in tests. */
    protected OpenCommunication communication() {
        OpenCommunication current = communication;
        if (current == null) {
            synchronized (this) {
                current = communication;
                if (current == null) {
                    current = initialize();
                    communication = current;
                }
            }
        }
        return current;
    }

    private OpenCommunication initialize() {
        String url = requireConfigured(cfg.getUrl(), "url (CITIC_SDK_URL)");
        String certPath = requireConfigured(cfg.getCertPath(), "cert-path (CITIC_CERT_PATH)");
        try {
            Files.createDirectories(Path.of(certPath));
        } catch (IOException exception) {
            throw new BusinessException(500, "CITIC certificate directory is not usable: " + certPath
                    + " (" + exception.getMessage() + ")");
        }
        // Vendor contract: configuration must be injected before the singleton is first created;
        // later injections are ignored (verified: getSdkProp keeps the first value).
        OpenCommunication.setCfgPropertiesByte(renderSdkProperties(url).getBytes(StandardCharsets.ISO_8859_1));
        try {
            OpenCommunication created;
            if (cfg.isOpenCommCustom()) {
                created = new CiticTokenCommunication(cfg.getToken());
            } else {
                created = new DefaultOpenCommunication();
            }
            log.info("CITIC DLink SDK initialized (mode={}, url={})",
                    cfg.isOpenCommCustom() ? "custom-token" : "default", url);
            return created;
        } catch (DLinkSdkException exception) {
            throw new BusinessException(502, "CITIC DLink SDK initialization failed: "
                    + exception.getMessage());
        }
    }

    private String renderSdkProperties(String url) {
        // Key set mirrors the vendor citicbank-sdk.properties; ASCII-only, ISO-8859-1 encoded.
        String hostIp = blankToNull(cfg.getHostIp());
        String logPath = cfg.getLogPath() == null || cfg.getLogPath().isBlank()
                ? System.getProperty("java.io.tmpdir") + "/citicbank-dlink-sdk-logs"
                : cfg.getLogPath();
        return new StringBuilder()
                .append("CITICBANK.URL=").append(url).append('\n')
                .append("CITICBANK.proxy.http.hostname=\n")
                .append("CITICBANK.proxy.http.port=\n")
                .append("CITICBANK.proxy.username=\n")
                .append("CITICBANK.proxy.password=\n")
                .append("CITICBANK.host.ip=").append(hostIp == null ? "" : hostIp).append('\n')
                .append("CITICBANK.OpenCommunicationCustom=").append(cfg.isOpenCommCustom()).append('\n')
                .append("CITICBANK.log.path=").append(logPath).append('\n')
                .append("CITICBANK.log.limit=100\n")
                .append("CITICBANK.log.history=30\n")
                .append("CITICBANK.sftp.actions=\n")
                .toString();
    }

    private static String requireConfigured(String value, String key) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            throw new BusinessException(500, "bankdata.adapter.citic.sdk." + key
                    + " is not configured; REAL CITIC mode requires it before first use");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Custom-token variant for container/cloud deployments where the hardware MAC is not a
     * stable binding (docker MAC changes on every recreate). The token is customer-chosen,
     * must be identical between certificate download and every send, and is issued from
     * {@code bankdata.adapter.citic.sdk.token} (CITIC_TOKEN).
     */
    static final class CiticTokenCommunication extends OpenCommunication {

        private final String token;

        CiticTokenCommunication(String token) throws DLinkSdkException {
            super();
            this.token = token;
        }

        @Override
        public String tokenCustom() {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("bankdata.adapter.citic.sdk.token (CITIC_TOKEN) is required "
                        + "when open-comm-custom=true; the certificate download must use the same token");
            }
            return token;
        }

        @Override
        public String macAddressCustom() {
            return detectMacAddress();
        }
    }

    /** Best-effort hardware MAC probe used by the custom-token variant when the SDK asks. */
    private static String detectMacAddress() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                    continue;
                }
                byte[] hardwareAddress = networkInterface.getHardwareAddress();
                if (hardwareAddress == null || hardwareAddress.length == 0) {
                    continue;
                }
                StringBuilder mac = new StringBuilder();
                for (byte octet : hardwareAddress) {
                    if (!mac.isEmpty()) {
                        mac.append('-');
                    }
                    mac.append(String.format("%02X", octet));
                }
                return mac.toString();
            }
        } catch (Exception ignored) {
            // no MAC available (e.g. container without physical NIC); custom token remains the binding
        }
        return "";
    }
}
