package com.finance.system.bankdata.adapter.citic.dlink;

import com.finance.system.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default transport: the vendor SDK jars are not available (CI builds run with the
 * {@code citic-sdk} profile disabled because the commercial jars are not committed), so
 * every real exchange fails fast (501). Mutually exclusive with {@link SdkCiticDlinkSdk}:
 * this bean exists only while {@code bankdata.adapter.citic.real-enabled} is false or absent.
 */
@Component
@ConditionalOnProperty(prefix = "bankdata.adapter.citic", name = "real-enabled",
        havingValue = "false", matchIfMissing = true)
public class UnavailableCiticDlinkSdk implements CiticDlinkSdk {

    @Override
    public String exchange(String action, String businessXml, String clientId) {
        throw new BusinessException(501, "CITIC DLink SDK is not loaded; build with the citic-sdk Maven profile "
                + "and enable bankdata.adapter.citic.real-enabled for the real transport");
    }

    @Override
    public String downloadCertificate(String downloadCode, String orgCode, String certPath) {
        throw new BusinessException(501, "CITIC DLink SDK is not loaded; certificate download is unavailable");
    }
}
