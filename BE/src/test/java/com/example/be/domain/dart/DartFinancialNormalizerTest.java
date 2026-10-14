package com.example.be.domain.dart;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.example.be.domain.dart.DartTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DartFinancialNormalizerTest {
    private final DartFinancialNormalizer normalizer = new DartFinancialNormalizer();
    private final Set<String> notes = new LinkedHashSet<>();
    private final DartFinancialNormalizer.Period q2 = new DartFinancialNormalizer.Period(2025, 2);
    private final DartFinancialNormalizer.Period q3 = new DartFinancialNormalizer.Period(2025, 3);
    private final DartFinancialNormalizer.Period q4 = new DartFinancialNormalizer.Period(2025, 4);
    private final String RECEIPT = "20260310000001";

    @Test void usesThreeMonthValueInsteadOfCumulativeAndKeepsLossSign() {
        var statement = parse(q2, financial(q2, RECEIPT, "200", "300", "-20", "-10"), false);
        var quarter = normalizer.normalize(q2, Map.of(q2, statement), notes).quarters().getLast();
        assertEquals("200", quarter.revenue());
        assertEquals("-20", quarter.operatingProfit());
        assertEquals(new BigDecimal("-10.00"), quarter.operatingMargin());
        assertFalse(quarter.derived());
    }

    @Test void derivesQ4FromAnnualMinusNineMonthsAndPreservesBothReceipts() {
        var annual = parse(q4, financial(q4, RECEIPT, "1,000", "", "100", ""), false);
        String earlierReceipt = "20251114000001";
        var third = parse(q3, financial(q3, earlierReceipt, "300", "600", "30", "40"), false);
        var series = normalizer.normalize(q4, Map.of(q3, third, q4, annual), notes);
        assertEquals(12, series.quarters().size());
        assertEquals(2023, series.quarters().getFirst().year());
        assertEquals(1, series.quarters().getFirst().quarter());
        var quarter = series.quarters().getLast();
        assertEquals("400", quarter.revenue());
        assertEquals("60", quarter.operatingProfit());
        assertEquals(new BigDecimal("15.00"), quarter.operatingMargin());
        assertTrue(quarter.derived());
        assertEquals(Set.of(RECEIPT, earlierReceipt), quarter.sources().stream().map(DartModels.Filing::rceptNo)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test void missingQuarterStaysInPlaceAndMissingAmountsAreNotZero() {
        var third = parse(q3, financial(q3, RECEIPT, "-", "600", "", "40"), false);
        var quarters = normalizer.normalize(q3, Map.of(q3, third), notes).quarters();
        assertEquals(12, quarters.size());
        assertEquals(2025, quarters.get(10).year());
        assertEquals(2, quarters.get(10).quarter());
        assertNull(quarters.get(10).revenue());
        assertNull(quarters.getLast().revenue());
        assertNull(quarters.getLast().operatingProfit());
    }

    @Test void derivesSamsung2023Q4RevenueWhenStandardAccountDisplayNameChanges() {
        var annualPeriod = new DartFinancialNormalizer.Period(2023, 4);
        var thirdPeriod = new DartFinancialNormalizer.Period(2023, 3);
        // Public DART receipts 20240312000736 / 20231114002109, same ifrs-full_Revenue.
        var annual = parse(annualPeriod, JSON.readTree(financial(annualPeriod, "20240312000736",
                "258935494000000", "", "6566976000000", "").toString().replace("매출액", "영업수익")), false);
        var third = parse(thirdPeriod, JSON.readTree(financial(thirdPeriod, "20231114002109",
                "67404652000000", "191155556000000", "2433534000000", "3742259000000")
                .toString().replace("매출액", "수익(매출액)")), false);
        var quarter = normalizer.normalize(annualPeriod, Map.of(annualPeriod, annual, thirdPeriod, third), notes).quarters().getLast();
        assertEquals("67779938000000", quarter.revenue());
        assertEquals("2824717000000", quarter.operatingProfit());
        assertEquals(new BigDecimal("4.17"), quarter.operatingMargin());
        assertTrue(quarter.derived());
    }

    @Test void derivesHynix2025Q4OperatingProfitWhenStandardAccountDisplayNameChanges() {
        // Public DART receipts 20260317000635 / 20251114001805, same dart_OperatingIncomeLoss.
        var annual = parse(q4, JSON.readTree(financial(q4, "20260317000635", "97146675000000", "",
                "47206319000000", "").toString().replace("\"IS\"", "\"CIS\"")), false);
        var third = parse(q3, JSON.readTree(financial(q3, "20251114001805", "24448929000000", "64320022000000",
                "11383390000000", "28036745000000").toString().replace("영업이익(손실)", "영업이익")
                .replace("\"IS\"", "\"CIS\"")), false);
        var quarter = normalizer.normalize(q4, Map.of(q4, annual, q3, third), notes).quarters().getLast();
        assertEquals("32826653000000", quarter.revenue());
        assertEquals("19169574000000", quarter.operatingProfit());
        assertEquals(new BigDecimal("58.40"), quarter.operatingMargin());
        assertTrue(quarter.derived());
    }

    @Test void zeroRevenueIsRetainedButMarginIsUnavailable() {
        var value = parse(q2, financial(q2, RECEIPT, "0", "200", "-1", "10"), false);
        var quarter = normalizer.normalize(q2, Map.of(q2, value), notes).quarters().getLast();
        assertEquals("0", quarter.revenue());
        assertEquals("-1", quarter.operatingProfit());
        assertNull(quarter.operatingMargin());
    }

    @Test void duplicatedStandardAccountCannotChooseOneByOrder() {
        String data = financial(q2, RECEIPT, "200", "300", "20", "10").toString();
        JsonNode response = JSON.readTree(data.replace("\"dart_OperatingIncomeLoss\"", "\"ifrs-full_Revenue\""));
        var parsed = parse(q2, response, false);
        assertNull(parsed.revenue());
        assertNull(parsed.profit());
        assertTrue(notes.stream().anyMatch(note -> note.contains("계정을 하나로")));
    }

    @Test void customNamedRevenueIsNotGuessedWithoutVerifiedCompanyMapping() {
        JsonNode response = JSON.readTree(financial(q2, RECEIPT, "200", "300", "20", "10").toString()
                .replace("ifrs-full_Revenue", "-표준계정코드 미사용-"));
        assertNull(parse(q2, response, false).revenue());
    }

    @Test void changedStatementBasisOrCorrectedReportsCannotProduceQ4() {
        var annual = parse(q4, financial(q4, RECEIPT, "1000", "", "100", ""), true);
        var third = parse(q3, financial(q3, RECEIPT, "300", "600", "30", "40"), false);
        var quarter = normalizer.normalize(q4, Map.of(q3, third, q4, annual), notes).quarters().getLast();
        assertNull(quarter.revenue());
        assertFalse(quarter.derived());
        var changed = parse(q4, JSON.readTree(financial(q4, RECEIPT, "1000", "", "100", "")
                .toString().replace("\"IS\"", "\"CIS\"")), false);
        assertNull(normalizer.normalize(q4, Map.of(q3, third, q4, changed), notes).quarters().getLast().revenue());
    }

    @Test void anyNonzeroDiscontinuedOperationPreventsQ4ScopeAssumptions() {
        var root = (tools.jackson.databind.node.ObjectNode) financial(q4, RECEIPT, "1000", "", "100", "");
        var rows = (tools.jackson.databind.node.ArrayNode) root.path("list");
        var discontinuation = root.path("list").get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) discontinuation)
                .put("account_id", "ifrs-full_ProfitLossFromDiscontinuedOperations").put("thstrm_amount", "20");
        rows.add(discontinuation);
        var annual = parse(q4, root, false);
        var third = parse(q3, financial(q3, RECEIPT, "300", "600", "30", "40"), false);
        assertNull(normalizer.normalize(q4, Map.of(q3, third, q4, annual), notes).quarters().getLast().revenue());
        assertTrue(notes.stream().anyMatch(note -> note.contains("분기별 사업 범위가 다를 수")));
    }

    @Test void currenciesCannotBeMixedAcrossQuarters() {
        var earlier = parse(q2, financial(q2, RECEIPT, "200", "300", "20", "10"), false);
        var later = parse(q3, JSON.readTree(financial(q3, RECEIPT, "300", "600", "30", "40")
                .toString().replace("KRW", "USD")), false);
        var series = normalizer.normalize(q3, Map.of(q2, earlier, q3, later), notes);
        assertNull(series.currency());
        assertTrue(series.quarters().stream().allMatch(quarter -> quarter.revenue() == null));
    }

    @Test void structuredDataLagIsRetryableAndCannotBecomeAnImmutablePartialSnapshot() {
        var original = filing(RECEIPT, "반기보고서 (2025.06)");
        var correction = filing("20260401000001", "[기재정정]반기보고서 (2025.06)");
        var error = assertThrows(DartUpstreamException.class, () -> normalizer.parse(q2, CORP,
                financial(q2, RECEIPT, "200", "300", "20", "10"), Map.of(RECEIPT, original), correction, notes));
        assertEquals("PENDING_FILING", error.getStatus());
    }

    @Test void foreignCompanyOrPeriodIsNeverAccepted() {
        JsonNode wrongCompany = JSON.readTree(financial(q2, RECEIPT, "200", "300", "20", "10")
                .toString().replace(CORP, "00164779"));
        assertThrows(DartUpstreamException.class, () -> parse(q2, wrongCompany, false));
    }

    @Test void parsesMoneyWithoutFloatRoundingAndRejectsMalformedSeparators() {
        assertEquals(new BigDecimal("-12345678901234567890"), DartFinancialNormalizer.amount("(12,345,678,901,234,567,890)"));
        assertEquals(BigDecimal.ZERO, DartFinancialNormalizer.amount("0"));
        assertNull(DartFinancialNormalizer.amount("1,2,3"));
        assertNull(DartFinancialNormalizer.amount("NaN"));
        assertNull(DartFinancialNormalizer.amount("1e9"));
        assertNull(DartFinancialNormalizer.amount("-"));
    }

    private DartFinancialNormalizer.Statement parse(DartFinancialNormalizer.Period period, JsonNode data, boolean corrected) {
        String receipt = data.path("list").get(0).path("rcept_no").asText();
        var filing = filing(receipt, (corrected ? "[기재정정]" : "") + "정기보고서");
        return normalizer.parse(period, CORP, data, Map.of(receipt, filing), filing, notes);
    }
}
