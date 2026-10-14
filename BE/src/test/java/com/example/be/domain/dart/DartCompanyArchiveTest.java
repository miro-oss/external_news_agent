package com.example.be.domain.dart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static com.example.be.domain.dart.DartTestFixtures.zip;
import static org.junit.jupiter.api.Assertions.*;

class DartCompanyArchiveTest {
    private static final String COMPANY = """
            <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
            <stock_code>005930</stock_code><modify_date>20261014</modify_date></list>
            """;
    private final DartCompanyArchive parser = new DartCompanyArchive();

    @Test void readsUtf8CatalogAndPreservesLeadingZerosAndUnlistedCompany() throws Exception {
        var companies = parser.parse(zip("CORPCODE.xml", "<result>" + COMPANY
                + COMPANY.replace("00126380", "00164779").replace("005930", "") + "</result>"));
        assertEquals(2, companies.size());
        assertEquals("00126380", companies.getFirst().company().corpCode());
        assertEquals("삼성전자", companies.getFirst().company().corpName());
        assertEquals("005930", companies.getFirst().company().stockCode());
        assertEquals("", companies.getLast().company().stockCode());
        assertEquals("2026-10-14", companies.getFirst().modifiedAt().toString());
    }

    @Test void readsHttp200XmlErrorStatusWithoutExposingProviderMessage() {
        var error = assertThrows(DartUpstreamException.class, () -> parser.parse(
                "<result><status>020</status><message>synthetic-private-detail</message></result>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("020", error.getStatus());
        assertFalse(error.getMessage().contains("synthetic"));
        assertNull(error.getCause());
    }

    @Test void acceptsAlphanumericStockCodesFromPublishedDartCorpus() throws Exception {
        var companies = parser.parse(zip("CORPCODE.xml", "<result>" + COMPANY
                + COMPANY.replace("00126380", "01933981").replace("삼성전자", "비엔케이제3호스팩")
                        .replace("005930", "0068Y0")
                + COMPANY.replace("00126380", "01817920").replace("삼성전자", "페스카로")
                        .replace("005930", "0015S0") + "</result>"));
        assertEquals(3, companies.size());
        assertEquals("0068Y0", companies.get(1).company().stockCode());
        assertEquals("0015S0", companies.get(2).company().stockCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0068y0", "0068-0", "12345", "1234567", "00가000"})
    void rejectsMalformedStockCodeBeforeReplacingCatalog(String stockCode) throws Exception {
        byte[] archive = zip("CORPCODE.xml", "<result>" + COMPANY.replace("005930", stockCode) + "</result>");
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class, () -> parser.parse(archive)).getStatus());
    }

    @Test void blocksDoctypeAndExternalEntityResolution() throws Exception {
        byte[] archive = zip("CORPCODE.xml", """
                <!DOCTYPE result [<!ENTITY xxe SYSTEM "file:///definitely-not-present">]>
                <result>
                """ + COMPANY.replace("삼성전자", "&xxe;") + "</result>");
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class, () -> parser.parse(archive)).getStatus());
    }

    @Test void rejectsTraversalNamesDuplicatesAndEmptyCorpus() throws Exception {
        byte[] traversal = zip("../CORPCODE.xml", "<result>" + COMPANY + "</result>");
        byte[] duplicate = zip("CORPCODE.xml", "<result>" + COMPANY + COMPANY + "</result>");
        byte[] empty = zip("CORPCODE.xml", "<result/>");
        assertThrows(DartUpstreamException.class, () -> parser.parse(traversal));
        assertThrows(DartUpstreamException.class, () -> parser.parse(duplicate));
        assertThrows(DartUpstreamException.class, () -> parser.parse(empty));
    }

    @Test void refusesInvalidIdentifiersAndDatesBeforeReplacingAnyCatalog() throws Exception {
        byte[] identifier = zip("CORPCODE.xml", "<result>" + COMPANY.replace("00126380", "126380") + "</result>");
        byte[] date = zip("CORPCODE.xml", "<result>" + COMPANY.replace("20261014", "20261340") + "</result>");
        assertThrows(DartUpstreamException.class, () -> parser.parse(identifier));
        assertThrows(DartUpstreamException.class, () -> parser.parse(date));
    }

    @Test void limitsInflatedContentEvenWhenZipDoesNotDeclareEntrySize() throws Exception {
        byte[] compressed = zip("CORPCODE.xml", "<result>" + COMPANY + " ".repeat(8192) + "</result>");
        assertTrue(compressed.length < 1024);
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class,
                () -> new DartCompanyArchive(1024).parse(compressed)).getStatus());
    }
}
