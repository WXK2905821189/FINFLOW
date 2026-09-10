package com.finance.system.bank.citic;

import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.dlink.CiticDlinkSdk;
import com.finance.system.common.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operations endpoint for the CITIC DLink cloud-certificate lifecycle.
 *
 * <p>The download code issued by the bank is single-use: once {@code cerMNG} succeeds the
 * code is burned, so this endpoint exists for the joint-testing window and rare re-issues.
 * Body is optional — when omitted the configured {@code CITIC_DOWNLOAD_CODE}/{@code CITIC_ORG_CODE}
 * are used. When the REAL transport is not loaded the unavailable-boundary bean answers 501
 * through the shared {@link CiticDlinkSdk} interface.</p>
 */
@RestController
@RequestMapping("/api")
public class CiticCertificateController {

    private final CiticDlinkSdk citicDlinkSdk;
    private final CiticAdapterProperties properties;

    public CiticCertificateController(CiticDlinkSdk citicDlinkSdk, CiticAdapterProperties properties) {
        this.citicDlinkSdk = citicDlinkSdk;
        this.properties = properties;
    }

    public record CertificateDownloadRequest(String downloadCode, String orgCode) {
    }

    @PostMapping("/citic-certificate/download")
    @PreAuthorize("hasAuthority('bank:manage')")
    public ResponseEntity<Map<String, Object>> download(@RequestBody(required = false) CertificateDownloadRequest request) {
        String downloadCode = firstNonBlank(request == null ? null : request.downloadCode(),
                properties.getSdk().getDownloadCode());
        String orgCode = firstNonBlank(request == null ? null : request.orgCode(),
                properties.getSdk().getOrgCode());
        String certPath = properties.getSdk().getCertPath();
        if (downloadCode == null) {
            throw new BusinessException(400, "download-code is required (request body or CITIC_DOWNLOAD_CODE)");
        }
        if (orgCode == null) {
            throw new BusinessException(400, "org-code is required (request body or CITIC_ORG_CODE)");
        }
        if (certPath == null || certPath.isBlank()) {
            throw new BusinessException(400, "cert-path is required (CITIC_CERT_PATH)");
        }
        String statusXml = citicDlinkSdk.downloadCertificate(downloadCode, orgCode, certPath.trim());
        boolean ok = statusXml != null && statusXml.contains("AAAAAAA");
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "statusXml", statusXml == null ? "" : statusXml,
                "hint", ok ? "certificate downloaded; the download code is now burned"
                        : "download failed - the code may still be valid, check statusXml"));
    }

    private static String firstNonBlank(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }
}
