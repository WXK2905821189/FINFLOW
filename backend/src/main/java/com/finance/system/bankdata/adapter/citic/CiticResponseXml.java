package com.finance.system.bankdata.adapter.citic;

import com.finance.system.common.exception.BusinessException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses CITIC business-layer XML responses. Tags are case-sensitive and match the
 * vendor field tables exactly; unknown or newly appended fields are skipped rather
 * than failing, per the vendor contract for controlFlag &gt;= 1 responses.
 */
public final class CiticResponseXml {

    private CiticResponseXml() {
    }

    public static CiticStatementPage parseStatementPage(String businessXml) {
        Element root = parseRoot(businessXml);
        String containerAccount = firstChildText(root, "accountNo");
        List<Element> rows = rowsOf(root);
        List<CiticStatementRow> statementRows = new ArrayList<>(rows.size());
        for (Element row : rows) {
            statementRows.add(new CiticStatementRow(
                    firstChildText(row, "tranDate"),
                    firstChildText(row, "tranTime"),
                    firstChildText(row, "tranNo"),
                    firstChildText(row, "sumTranNo"),
                    decimal(firstChildText(row, "tranAmount")),
                    firstChildText(row, "creditDebitFlag"),
                    firstChildText(row, "oppAccountNo"),
                    firstChildText(row, "oppAccountName"),
                    firstChildText(row, "oppOpenBankName"),
                    firstChildText(row, "abstract"),
                    decimal(firstChildText(row, "balance")),
                    firstChildText(row, "oriNum")));
        }
        return new CiticStatementPage(
                firstChildText(root, "status"),
                firstChildText(root, "statusText"),
                containerAccount,
                firstChildText(root, "accountName"),
                integer(firstChildText(root, "totalRecords")),
                integer(firstChildText(root, "returnRecords")),
                List.copyOf(statementRows));
    }

    public static List<CiticBalanceRow> parseBalanceRows(String businessXml) {
        return parseBalanceQuery(businessXml).rows();
    }

    public static CiticBalanceResult parseBalanceQuery(String businessXml) {
        Element root = parseRoot(businessXml);
        List<Element> rows = rowsOf(root);
        List<CiticBalanceRow> balanceRows = new ArrayList<>(rows.size());
        for (Element row : rows) {
            balanceRows.add(new CiticBalanceRow(
                    firstChildText(row, "status"),
                    firstChildText(row, "statusText"),
                    firstChildText(row, "accountNo"),
                    firstChildText(row, "accountName"),
                    firstChildText(row, "currencyID"),
                    firstChildText(row, "openBankName"),
                    firstChildText(row, "lastTranDate"),
                    decimal(firstChildText(row, "usableBalance")),
                    decimal(firstChildText(row, "balance")),
                    decimal(firstChildText(row, "forzenAmt"))));
        }
        return new CiticBalanceResult(
                firstChildText(root, "status"),
                firstChildText(root, "statusText"),
                List.copyOf(balanceRows));
    }

    static Element parseRoot(String businessXml) {
        if (businessXml == null || businessXml.isBlank()) {
            throw new BusinessException(400, "CITIC business XML response is required");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(businessXml)));
            return document.getDocumentElement();
        } catch (Exception exception) {
            throw new BusinessException(400, "CITIC business XML response cannot be parsed");
        }
    }

    private static List<Element> rowsOf(Element root) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element) || !"list".equals(element.getTagName())) {
                continue;
            }
            if (!"userDataList".equals(element.getAttribute("name"))) {
                continue;
            }
            List<Element> rows = new ArrayList<>();
            NodeList rowNodes = element.getChildNodes();
            for (int j = 0; j < rowNodes.getLength(); j++) {
                Node rowNode = rowNodes.item(j);
                if (rowNode instanceof Element rowElement && "row".equals(rowElement.getTagName())) {
                    rows.add(rowElement);
                }
            }
            return rows;
        }
        return List.of();
    }

    static String firstChildText(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return trimToNull(element.getTextContent());
            }
        }
        return null;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Integer integer(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
