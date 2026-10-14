package com.example.be.domain.dart;

import com.example.be.global.config.ApiTimeZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Supplier;

import static com.example.be.domain.dart.DartFinancialNormalizer.Period;
import static com.example.be.domain.dart.DartFinancialNormalizer.Statement;

/** Called by the independent report worker, never by an HTTP request or report transaction. */
@Service
public class DartFinancialService {
    private static final Pattern REPORT_PERIOD = Pattern.compile("(?:사업|반기|분기)보고서\\s*\\((\\d{4})\\.(\\d{2})\\)");
    private final DartClient client;
    private final DartProperties properties;
    private final Clock clock;
    private final DartFinancialNormalizer normalizer = new DartFinancialNormalizer();

    @Autowired
    public DartFinancialService(DartClient client, DartProperties properties) {
        this(client, properties, Clock.system(ApiTimeZone.ZONE));
    }

    DartFinancialService(DartClient client, DartProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    public DartModels.FinancialData fetch(DartModels.Company company) {
        if (!properties.isEnabled()) throw new DartUpstreamException("DISABLED");
        if (company == null || !company.corpCode().matches("[0-9]{8}")) throw new DartUpstreamException("BAD_RESPONSE");
        LocalDate today = LocalDate.now(clock);
        Instant deadline = clock.instant().plus(Duration.ofMinutes(15));
        Set<String> notes = new LinkedHashSet<>();
        var disclosures = filings(before(deadline, () -> client.filings(company.corpCode(), today.minusYears(3), today, false, 1, 5)), company.corpCode())
                .stream().limit(5).toList();
        JsonNode profile = before(deadline, () -> client.company(company.corpCode()));
        if (!"000".equals(profile.path("status").asText())
                || !company.corpCode().equals(profile.path("corp_code").asText())
                || !"12".equals(profile.path("acc_mt").asText())) {
            return result(null, null, List.of(), disclosures,
                    List.of("12월 결산 여부를 확인할 수 없거나 지원하지 않는 회계기간입니다."));
        }
        List<DartModels.Filing> regular = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            int pageNumber = page;
            JsonNode response = before(deadline, () -> client.filings(company.corpCode(), LocalDate.of(today.getYear() - 5, 1, 1), today,
                    true, pageNumber, 100));
            regular.addAll(filings(response, company.corpCode()));
            int totalPages = response.path("total_page").asInt(0);
            if (totalPages > 5) throw new DartUpstreamException("BAD_RESPONSE");
            if (page >= totalPages) break;
        }
        Map<String, DartModels.Filing> byReceipt = new HashMap<>();
        Map<Period, DartModels.Filing> latestFilings = new HashMap<>();
        for (var filing : regular) {
            byReceipt.put(filing.rceptNo(), filing);
            Period period = period(filing.reportName());
            if (period == null || LocalDate.of(period.year(), period.quarter() * 3, 1)
                    .plusMonths(1).minusDays(1).isAfter(today)) continue;
            // A company can file corrections for old years more recently than this quarter's report.
            latestFilings.merge(period, filing, (a, b) -> a.rceptNo().compareTo(b.rceptNo()) >= 0 ? a : b);
        }
        Period latest = latestFilings.keySet().stream().max(Comparator.naturalOrder()).orElse(null);
        if (latest == null || latest.year() < 2018) {
            return result(null, null, List.of(), disclosures, List.of("최근 정기보고서의 회계기간을 확인할 수 없습니다."));
        }
        // company.json describes today's closing month. Historical reports must not reveal a different calendar.
        Period earliest = latest.previous(11);
        LocalDate windowStart = LocalDate.of(earliest.year(), (earliest.quarter() - 1) * 3 + 1, 1);
        LocalDate windowEnd = LocalDate.of(latest.year(), latest.quarter() * 3, 1).plusMonths(1).minusDays(1);
        for (var filing : regular) {
            var match = REPORT_PERIOD.matcher(filing.reportName());
            if (!match.find()) continue;
            int month = Integer.parseInt(match.group(2));
            if (month < 1 || month > 12) continue;
            LocalDate ended = LocalDate.of(Integer.parseInt(match.group(1)), month, 1).plusMonths(1).minusDays(1);
            if (!ended.isBefore(windowStart) && !ended.isAfter(windowEnd) && period(filing.reportName()) == null) {
                return result(null, null, List.of(), disclosures,
                        List.of("최근 정기보고서에 비12월 결산 또는 회계기간 변경이 있어 분기 실적을 비교할 수 없습니다."));
            }
        }
        Map<Period, JsonNode> raw = collect(company.corpCode(), latest, "CFS", latestFilings, deadline);
        String fsDiv = "CFS";
        if (raw.values().stream().noneMatch(this::provided)) {
            fsDiv = "OFS";
            raw = collect(company.corpCode(), latest, "OFS", latestFilings, deadline);
            if (raw.values().stream().anyMatch(this::provided)) notes.add("연결재무제표가 제공되지 않아 전체 기간을 별도재무제표 기준으로 표시합니다.");
        }
        if (raw.values().stream().noneMatch(this::provided)) {
            notes.add("이 회사의 정기보고서 재무정보가 제공되지 않았습니다.");
            fsDiv = null;
        }
        Map<Period, Statement> statements = new HashMap<>();
        for (var entry : raw.entrySet()) {
            statements.put(entry.getKey(), normalizer.parse(entry.getKey(), company.corpCode(), entry.getValue(),
                    byReceipt, latestFilings.get(entry.getKey()), notes));
        }
        var series = normalizer.normalize(latest, statements, notes);
        return result(fsDiv, series.currency(), series.quarters(), disclosures, List.copyOf(notes));
    }

    private Map<Period, JsonNode> collect(String corpCode, Period latest, String fsDiv,
                                         Map<Period, DartModels.Filing> reports, Instant deadline) {
        Map<Period, JsonNode> values = new LinkedHashMap<>();
        for (int offset = 0; offset < 12; offset++) {
            Period period = latest.previous(offset);
            load(values, corpCode, period, fsDiv, reports, deadline);
            if (period.quarter() == 4) load(values, corpCode, period.previous(1), fsDiv, reports, deadline);
        }
        return values;
    }

    private void load(Map<Period, JsonNode> values, String corpCode, Period period, String fsDiv,
                      Map<Period, DartModels.Filing> reports, Instant deadline) {
        if (values.containsKey(period)) return;
        JsonNode response = reports.containsKey(period) ? before(deadline,
                () -> client.financial(corpCode, period.year(), period.reportCode(), fsDiv)) : null;
        if (response != null && "000".equals(response.path("status").asText()) && !response.path("list").isArray())
            throw new DartUpstreamException("BAD_RESPONSE");
        values.put(period, response);
    }

    private <T> T before(Instant deadline, Supplier<T> request) {
        if (!clock.instant().isBefore(deadline)) throw new DartUpstreamException("TIMEOUT");
        return request.get();
    }

    private boolean provided(JsonNode response) {
        return response != null && "000".equals(response.path("status").asText())
                && response.path("list").isArray() && !response.path("list").isEmpty();
    }

    private DartModels.FinancialData result(String fsDiv, String currency, List<DartModels.Quarter> quarters,
                                          List<DartModels.Filing> disclosures, List<String> notes) {
        return new DartModels.FinancialData(fsDiv, currency, OffsetDateTime.now(clock), quarters, disclosures, notes);
    }

    static Period period(String reportName) {
        var matcher = REPORT_PERIOD.matcher(reportName);
        if (!matcher.find()) return null;
        int year = Integer.parseInt(matcher.group(1)), month = Integer.parseInt(matcher.group(2));
        if (year < 2015 || month < 3 || month > 12 || month % 3 != 0) return null;
        if (reportName.contains("사업보고서") && month != 12
                || reportName.contains("반기보고서") && month != 6
                || reportName.contains("분기보고서") && month != 3 && month != 9) return null;
        return new Period(year, month / 3);
    }

    private List<DartModels.Filing> filings(JsonNode response, String corpCode) {
        if ("013".equals(response.path("status").asText())) return List.of();
        if (!response.path("list").isArray()) throw new DartUpstreamException("BAD_RESPONSE");
        List<DartModels.Filing> result = new ArrayList<>();
        for (JsonNode row : response.path("list")) {
            String receipt = row.path("rcept_no").asText("");
            String name = row.path("report_nm").asText("");
            String rawDate = row.path("rcept_dt").asText("");
            if (!corpCode.equals(row.path("corp_code").asText()) || !receipt.matches("[0-9]{14}")
                    || name.isBlank() || name.length() > 512) throw new DartUpstreamException("BAD_RESPONSE");
            String filedAt;
            try { filedAt = LocalDate.parse(rawDate, DateTimeFormatter.BASIC_ISO_DATE).toString(); }
            catch (RuntimeException ignored) { throw new DartUpstreamException("BAD_RESPONSE"); }
            result.add(new DartModels.Filing(receipt, name, filedAt,
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt,
                    name.contains("정정") || row.path("rm").asText("").contains("정")));
        }
        return List.copyOf(result);
    }
}
