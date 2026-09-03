package com.finance.system.bankdata.adapter.citic.dlink;

import com.finance.system.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default transport: the vendor SDK jar is not on the classpath yet, so every real
 * exchange fails fast (501). A real {@link CiticDlinkSdk} implementation registered
 * later replaces this bean automatically.
 */
@Component
@ConditionalOnMissingBean(CiticDlinkSdk.class)
public class UnavailableCiticDlinkSdk implements CiticDlinkSdk {

    @Override
    public String exchange(String action, String businessXml, String clientId) {
        throw new BusinessException(501, "CITIC DLink SDK is not loaded; enable after the jar-in-JDK17 smoke test");
    }

    @Override
    public String downloadCertificate(String downloadCode, String orgCode, String certPath) {
        throw new BusinessException(501, "CITIC DLink SDK is not loaded; certificate download is unavailable");
    }
}
