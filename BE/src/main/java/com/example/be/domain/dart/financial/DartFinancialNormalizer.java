package com.example.be.domain.dart.financial;

import com.example.be.domain.dart.exception.DartUpstreamException;
import com.example.be.domain.dart.model.DartModels;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure normalization, deliberately refusing ambiguous or incomparable accounts. */
final class DartFinancialNormalizer {
    record Period(int year, int quarter) implements Comparable<Period> {
        Period {
            if (year < 2015 || quarter < 1 || quarter > 4) throw new IllegalArgumentException("Invalid DART period");
        }
        int index() { return year * 4 + quarter - 1; }
        Period previous(int count) {
            int value = index() - count;
            return new Period(Math.floorDiv(value, 4), Math.floorMod(value, 4) + 1);
        }
        String reportCode() { return switch (quarter) { case 1 -> "11013"; case 2 -> "11012";
            case 3 -> "11014"; default -> "11011"; }; }
        String label() { return year + "년 " + quarter + "분기"; }
        @Override public int compareTo(Period other) { return Integer.compare(index(), other.index()); }
    }

    record Metric(String accountId, String accountName, String statement, String currency,
                  BigDecimal amount, BigDecimal cumulative) {
        boolean sameBasis(Metric other) {
            // Only allowlisted standard accounts reach this point. Their display label can change
            // (e.g. 영업이익 -> 영업이익(손실)) without changing the taxonomy identity.
            return other != null && accountId.equals(other.accountId)
                    && statement.equals(other.statement) && currency.equals(other.currency);
        }
    }

    record Statement(Period period, String currency, Metric revenue, Metric profit,
                     DartModels.Filing source, boolean comparable) { }
    record Series(String currency, List<DartModels.Quarter> quarters) { }

    private static final Set<String> REVENUE_IDS = Set.of("ifrs-full_Revenue", "ifrs_Revenue");
    private static final Set<String> PROFIT_IDS = Set.of("dart_OperatingIncomeLoss", "ifrs-full_ProfitLossFromOperatingActivities");

    Statement parse(Period period, String corpCode, JsonNode response,
                    Map<String, DartModels.Filing> filings, DartModels.Filing latest, Set<String> notes) {
        if (response == null || "013".equals(response.path("status").asText())) return null;
        JsonNode list = response.path("list");
        if (!list.isArray() || list.isEmpty()) throw new DartUpstreamException("BAD_RESPONSE");
        List<JsonNode> income = new ArrayList<>();
        Set<String> receipts = new LinkedHashSet<>();
        Set<String> currencies = new LinkedHashSet<>();
        boolean discontinued = false;
        for (JsonNode row : list) {
            if (!corpCode.equals(text(row, "corp_code")) || !Integer.toString(period.year()).equals(text(row, "bsns_year"))
                    || !period.reportCode().equals(text(row, "reprt_code"))) throw new DartUpstreamException("BAD_RESPONSE");
            if (!Set.of("IS", "CIS").contains(text(row, "sj_div"))) continue;
            String receipt = text(row, "rcept_no");
            if (!receipt.matches("[0-9]{14}")) throw new DartUpstreamException("BAD_RESPONSE");
            receipts.add(receipt);
            String currency = text(row, "currency");
            if (!currency.matches("[A-Z]{3}")) {
                notes.add(period.label() + " 통화를 확인할 수 없습니다.");
                return null;
            }
            currencies.add(currency);
            income.add(row);
            // A changed discontinued-operation perimeter can invalidate annual minus nine-month arithmetic.
            if (text(row, "account_id").contains("DiscontinuedOperations")
                    && (nonZero(text(row, "thstrm_amount")) || nonZero(text(row, "thstrm_add_amount")))) discontinued = true;
        }
        if (income.isEmpty()) {
            notes.add(period.label() + " 손익계산서가 제공되지 않았습니다.");
            return null;
        }
        if (receipts.size() != 1 || currencies.size() != 1) {
            notes.add(period.label() + " 통화 또는 공시 출처가 섞여 있어 실적을 표시하지 않습니다.");
            return null;
        }
        String receipt = receipts.iterator().next();
        DartModels.Filing source = filings.get(receipt);
        if (source == null || latest == null || !receipt.equals(latest.rceptNo())) {
            // A final report can appear before its structured financial data. Do not freeze this lag as PARTIAL.
            throw new DartUpstreamException("PENDING_FILING");
        }
        Metric revenue = metric(income, REVENUE_IDS, "매출", period, notes);
        Metric profit = metric(income, PROFIT_IDS, "영업이익", period, notes);
        if (discontinued) notes.add("중단영업이 포함된 공시가 있어 분기별 사업 범위가 다를 수 있습니다. 각 분기 공시 기준 값이며 과거 분기를 동일 사업 범위로 재작성하지 않았습니다.");
        return new Statement(period, currencies.iterator().next(), revenue, profit, source,
                !source.corrected() && !discontinued);
    }

    Series normalize(Period latest, Map<Period, Statement> data, Set<String> notes) {
        Set<String> currencies = new LinkedHashSet<>();
        for (Statement statement : data.values()) if (statement != null) currencies.add(statement.currency());
        boolean mixedCurrency = currencies.size() > 1;
        if (mixedCurrency) notes.add("분기별 통화가 달라 같은 시계열의 실적을 표시하지 않습니다.");
        String currency = currencies.size() == 1 ? currencies.iterator().next() : null;
        List<DartModels.Quarter> quarters = new ArrayList<>();
        for (int offset = 11; offset >= 0; offset--) {
            Period period = latest.previous(offset);
            Statement current = data.get(period);
            Statement q3 = period.quarter() == 4 ? data.get(period.previous(1)) : null;
            BigDecimal revenue = null, profit = null;
            List<DartModels.Filing> sources = new ArrayList<>();
            if (current != null) sources.add(current.source());
            if (q3 != null) sources.add(q3.source());
            if (!mixedCurrency && current != null) {
                if (period.quarter() == 4) {
                    if (q3 != null && current.comparable() && q3.comparable()
                            && current.currency().equals(q3.currency())) {
                        revenue = difference(current.revenue(), q3.revenue());
                        profit = difference(current.profit(), q3.profit());
                    }
                    if (revenue == null || profit == null) notes.add(period.label()
                            + " 연간·9개월의 계정, 정정 또는 회계 범위를 확인할 수 없어 일부 계산값을 표시하지 않습니다.");
                } else {
                    revenue = current.revenue() == null ? null : current.revenue().amount();
                    profit = current.profit() == null ? null : current.profit().amount();
                }
            }
            if (revenue == null || profit == null) notes.add(period.label() + " 일부 실적 자료가 없습니다.");
            BigDecimal margin = revenue != null && revenue.signum() != 0 && profit != null
                    ? profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP) : null;
            quarters.add(new DartModels.Quarter(period.year(), period.quarter(), decimal(revenue), decimal(profit),
                    margin, period.quarter() == 4 && (revenue != null || profit != null), List.copyOf(sources)));
        }
        return new Series(currency, List.copyOf(quarters));
    }

    private BigDecimal difference(Metric annual, Metric q3) {
        return annual != null && annual.sameBasis(q3) && annual.amount() != null && q3.cumulative() != null
                ? annual.amount().subtract(q3.cumulative()) : null;
    }

    private Metric metric(List<JsonNode> rows, Set<String> ids,
                          String label, Period period, Set<String> notes) {
        List<JsonNode> selected = rows.stream().filter(row -> ids.contains(text(row, "account_id"))).toList();
        // A display name is not a verified taxonomy mapping. Custom accounts remain unavailable.
        if (selected.size() != 1) {
            notes.add(period.label() + " " + label + " 계정을 하나로 확인할 수 없습니다.");
            return null;
        }
        JsonNode row = selected.getFirst();
        String name = normalizedName(text(row, "account_nm"));
        BigDecimal amount = amount(text(row, "thstrm_amount"));
        BigDecimal cumulative = amount(text(row, "thstrm_add_amount"));
        return new Metric(text(row, "account_id"), name, text(row, "sj_div"), text(row, "currency"), amount, cumulative);
    }

    static BigDecimal amount(String input) {
        if (input == null || input.isBlank() || input.length() > 128) return null;
        String value = input.trim();
        boolean parentheses = value.startsWith("(") && value.endsWith(")");
        if (parentheses) value = "-" + value.substring(1, value.length() - 1);
        if (!value.matches("[-+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) return null;
        try {
            BigDecimal result = new BigDecimal(value.replace(",", ""));
            return result.precision() <= 40 && result.scale() <= 12 ? result : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private boolean nonZero(String input) {
        BigDecimal value = amount(input);
        return value == null ? !input.isBlank() && !"-".equals(input) : value.signum() != 0;
    }
    private static String normalizedName(String name) { return name.replaceAll("\\s+", ""); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
}
