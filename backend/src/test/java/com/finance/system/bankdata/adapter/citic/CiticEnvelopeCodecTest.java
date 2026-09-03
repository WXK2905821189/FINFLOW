package com.finance.system.bankdata.adapter.citic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiticEnvelopeCodecTest {

    @Test
    void buildsOuterRequestWithGbkBase64Payload() {
        String businessXml = "<?xml version=\"1.0\" encoding=\"GBK\"?><stream><action>DLBALQRY</action>"
                + "<accountNo>81107</accountNo></stream>";
        String outer = CiticEnvelopeCodec.buildOuterRequest("finflow-user", businessXml, "0", "req-1");

        assertTrue(outer.startsWith("<?xml version=\"1.0\" encoding=\"GBK\"?>"));
        assertTrue(outer.contains("<action>DLGECOMM</action>"));
        assertTrue(outer.contains("<userName>finflow-user</userName>"));
        assertTrue(outer.contains("<CASHFLAG>0</CASHFLAG>"));
        assertTrue(outer.contains("<clientID>req-1</clientID>"));
        // Round-trip the encoded payload back to the exact business XML.
        int start = outer.indexOf("<requestContent>") + "<requestContent>".length();
        int end = outer.indexOf("</requestContent>", start);
        assertEquals(businessXml, CiticBankDataCodec.decodeGbkBase64(outer.substring(start, end)));
    }

    @Test
    void preservesGbkContentThroughOuterResponseRoundTrip() {
        String businessXml = "<stream><action>DLTRNALL</action><status>AAAAAAA</status>"
                + "<list name=\"userDataList\"><row><tranNo>T1</tranNo>"
                + "<abstract>中文摘要-货款</abstract></row></list></stream>";
        String encoded = CiticBankDataCodec.encodeGbkBase64(businessXml);
        String outerResponse = "<stream><status>AAAAAAA</status><statusText/>"
                + "<responseContent>" + encoded + "</responseContent></stream>";

        CiticEnvelopeResponse parsed = CiticEnvelopeCodec.parseOuterResponse(outerResponse);

        assertEquals("AAAAAAA", parsed.status());
        assertNull(parsed.statusText());
        assertEquals(businessXml, parsed.businessXml());
    }

    @Test
    void toleratesMissingResponseContent() {
        CiticEnvelopeResponse parsed = CiticEnvelopeCodec.parseOuterResponse(
                "<stream><status>EEEEEEE</status><statusText>无交易</statusText></stream>");

        assertEquals("EEEEEEE", parsed.status());
        assertTrue(parsed.statusText().contains("无交易"));
        assertNull(parsed.businessXml());
    }

    @Test
    void boundsClientIdToThirtyCharacters() {
        String bounded = CiticEnvelopeCodec.clientId("abcdefghij-abcdefghij-abcdefghij-abcdefghij", 2);

        assertTrue(bounded.length() <= 30);
        assertTrue(bounded.endsWith("-P2"));
    }
}
