package com.finance.system.statement.kingdee.real;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.statement.kingdee.KingdeeProperties;
import com.kingdee.bos.webapi.entity.IdentifyInfo;
import com.kingdee.bos.webapi.sdk.K3CloudApi;

/**
 * Thin wrapper around the official K3Cloud WebAPI SDK (kingdee-sdk Maven profile).
 *
 * <p>Lives in the profile source folder so builds without the vendor jar compile the
 * fail-closed {@code UnavailableKingdeeVoucherGateway} instead (citic-sdk pattern).
 * Credentials come from {@link KingdeeProperties} (env vars only, never committed).
 *
 * <p>Methods return the RAW JSON response so the gateway can persist request/response
 * message-level evidence (RequestWebModel pattern from the yi-kd-web-client evaluation).
 */
public class KingdeeSdkClient {

    private final K3CloudApi api;

    public KingdeeSdkClient(KingdeeProperties props) {
        IdentifyInfo info = new IdentifyInfo();
        info.setServerUrl(required(props.getServerUrl(), "kingdee.server-url"));
        info.setdCID(required(props.getAcctId(), "kingdee.acct-id"));
        info.setAppId(required(props.getAppId(), "kingdee.app-id"));
        info.setAppSecret(required(props.getAppSec(), "kingdee.app-sec"));
        info.setUserName(required(props.getUserName(), "kingdee.user-name"));
        info.setlCID(props.getLcid() == null ? 2052 : props.getLcid());
        this.api = new K3CloudApi(info);
    }

    /** For tests: inject a prepared api instance. */
    KingdeeSdkClient(K3CloudApi api) {
        this.api = api;
    }

    public String save(String formId, String json) {
        try {
            return api.save(formId, json);
        } catch (Exception e) {
            throw new BusinessException(502, "Kingdee save failed: " + rootMessage(e));
        }
    }

    public String excuteOperation(String formId, String operationNumber, String json) {
        try {
            return api.excuteOperation(formId, operationNumber, json);
        } catch (Exception e) {
            throw new BusinessException(502, "Kingdee " + operationNumber + " failed: " + rootMessage(e));
        }
    }

    public String executeBillQueryJson(String json) {
        try {
            return api.executeBillQueryJson(json);
        } catch (Exception e) {
            throw new BusinessException(502, "Kingdee bill query failed: " + rootMessage(e));
        }
    }

    private static String required(String value, String configKey) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(500, "Kingdee real gateway requires " + configKey);
        }
        return value;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
