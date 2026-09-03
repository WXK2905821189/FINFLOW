package com.finance.system.bankdata.adapter.citic.dlink;

import com.citicbank.dlink.sdk.open.OpenCommunication;
import com.finance.system.bankdata.adapter.citic.CiticAdapterProperties;
import com.finance.system.bankdata.adapter.citic.CiticBankDataCodec;
import com.finance.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SdkCiticDlinkSdk}. The SDK singleton is stubbed out by overriding
 * the transport hooks so no vendor network call or real SDK initialization is performed.
 */
class SdkCiticDlinkSdkTest {

    /** Stub transport: returns a canned outer response; never touches the real SDK. */
    static final class StubSdk extends SdkCiticDlinkSdk {

        String fakeOuterResponse;
        String lastOuterRequest;

        StubSdk(CiticAdapterProperties properties) {
            super(properties);
        }

        @Override
        protected OpenCommunication communication() {
            return null;
        }

        @Override
        protected String sendOuter(OpenCommunication comm, String outerXml) {
            lastOuterRequest = outerXml;
            return fakeOuterResponse;
        }
    }

    private static CiticAdapterProperties configuredProperties() {
        CiticAdapterProperties properties = new CiticAdapterProperties();
        properties.getSdk().setUrl("http://bank.example:8080/DLink/DLServlet/Open");
        properties.getSdk().setUserName("finflow-user");
        properties.getSdk().setCashFlag("0");
        properties.getSdk().setCertPath("build/tmp/cert");
        properties.getSdk().setOpenCommCustom(false);
        return properties;
    }

    private static String outerResponse(String status, String encodedContent) {
        return "<stream><status>" + status + "</status>"
                + (encodedContent == null ? "" : "<responseContent>" + encodedContent + "</responseContent>")
                + "</stream>";
    }

    @Test
    void exchangeReturnsDecodedBusinessXmlOnSuccess() {
        CiticAdapterProperties properties = configuredProperties();
        StubSdk sdk = new StubSdk(properties);
        String inner = "<stream><action>DLBALQRY</action><status>AAAAAAA</status></stream>";
        sdk.fakeOuterResponse = outerResponse("AAAAAAA", CiticBankDataCodec.encodeGbkBase64(inner));

        String result = sdk.exchange("DLBALQRY", "<request/>", "req-1");

        assertEquals(inner, result);
        // outer request must carry the business payload base64-encoded and the trace client id
        assertNotNull(sdk.lastOuterRequest);
        assertTrue(sdk.lastOuterRequest.contains("<action>DLGECOMM</action>"));
        assertTrue(sdk.lastOuterRequest.contains("<clientID>req-1</clientID>"));
        assertTrue(sdk.lastOuterRequest.contains("<requestContent>"));
    }

    @Test
    void exchangeFailsWhenOuterStatusIsNotSuccess() {
        CiticAdapterProperties properties = configuredProperties();
        StubSdk sdk = new StubSdk(properties);
        sdk.fakeOuterResponse = outerResponse("EEEEEEE", "c2hvdWxkLW5vdC1iZS1yZWFk"); // "should-not-be-read"

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sdk.exchange("DLTRNALL", "<request/>", "req-2"));

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("EEEEEEE"));
    }

    @Test
    void exchangeFailsWhenOuterAcceptsButCarriesNoBusinessContent() {
        CiticAdapterProperties properties = configuredProperties();
        StubSdk sdk = new StubSdk(properties);
        sdk.fakeOuterResponse = outerResponse("AAAAAAA", null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sdk.exchange("DLBALQRY", "<request/>", "req-3"));

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("no business content"));
    }

    @Test
    void exchangeFailsFastWhenSdkUrlIsMissing() {
        CiticAdapterProperties properties = configuredProperties();
        properties.getSdk().setUrl("");
        SdkCiticDlinkSdk sdk = new SdkCiticDlinkSdk(properties);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sdk.exchange("DLBALQRY", "<request/>", "req-4"));

        assertEquals(500, exception.getCode());
        assertTrue(exception.getMessage().contains("url"));
    }

    @Test
    void exchangeFailsFastWhenUserNameIsMissing() {
        CiticAdapterProperties properties = configuredProperties();
        properties.getSdk().setUserName(null);
        StubSdk sdk = new StubSdk(properties);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sdk.exchange("DLBALQRY", "<request/>", "req-5"));

        assertEquals(500, exception.getCode());
        assertTrue(exception.getMessage().contains("user-name"));
    }
}
