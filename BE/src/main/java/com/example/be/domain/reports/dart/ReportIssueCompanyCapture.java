package com.example.be.domain.reports.dart;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.dart.company.DartCompanyRepository;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Runs only for the owner of a new generation. A failure leaves the historical evidence unavailable. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportIssueCompanyCapture {
    private static final Set<String> GROUP_NAMES = Set.of("삼성", "현대", "롯데", "한화", "SK", "LG", "GS", "CJ", "LS", "DB");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"'()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIPT = Pattern.compile("접수\\s*번호\\s*[:：=]?\\s*([0-9]{14})(?![0-9])");
    private final DartCompanyRepository companies;

    public ReportIssueCompanySnapshot captureSelected(NewsReport report, List<Long> findingIds,
                                                      WeeklyReportInput weeklyInput, LocalDateTime now) {
        return captureSelected(report, findingIds, weeklyInput, null, now);
    }

    public ReportIssueCompanySnapshot captureSelected(NewsReport report, List<Long> findingIds,
                                                      WeeklyReportInput weeklyInput, List<Finding> generationInput,
                                                      LocalDateTime now) {
        try {
            if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() == null)
                return mergeWeekly(weeklyInput, findingIds, now);
            // The same detached input must reach the generator and this capture. A later DB read can be reanalysis B.
            if (generationInput == null) return null;
            var byId = generationInput.stream().collect(Collectors.toMap(Finding::getId, value -> value));
            if (!byId.keySet().containsAll(findingIds)) return null;
            return capture(report, findingIds.stream().distinct().map(byId::get).toList(), now);
        } catch (RuntimeException exception) {
            log.warn("보고서 기업 근거를 고정하지 못했습니다. reportId={}", report.getId());
            return null;
        }
    }

    public ReportIssueCompanySnapshot captureFindings(NewsReport report, List<Finding> selected, LocalDateTime now) {
        try { return capture(report, selected, now); }
        catch (RuntimeException exception) {
            log.warn("보고서 기업 근거를 고정하지 못했습니다. reportId={}", report.getId());
            return null;
        }
    }

    private ReportIssueCompanySnapshot capture(NewsReport report, List<Finding> selected, LocalDateTime now) {
        List<String> names = selected.stream().flatMap(finding -> finding.getEntities().companies().stream())
                .filter(value -> value != null && !value.isBlank() && value.length() <= 512).distinct().toList();
        var catalog = companies.findExactCandidates(names).stream().collect(Collectors.groupingBy(
                company -> DartCompanyRepository.identityKey(company.corpName()), LinkedHashMap::new, Collectors.toList()));
        Map<Long, Long> issueIds = new LinkedHashMap<>();
        if (report.getReportCoverageSnapshot() != null) report.getReportCoverageSnapshot().bindings()
                .forEach(binding -> issueIds.putIfAbsent(binding.findingId(), binding.issueId()));
        List<ReportIssueCompanySnapshot.Link> links = new ArrayList<>();
        for (Finding finding : selected) {
            var article = finding.getArticle();
            if (article == null || !article.hasFullText()) continue;
            String body = Normalizer.normalize(article.getBody(), Normalizer.Form.NFKC);
            Set<String> seen = new LinkedHashSet<>();
            for (String name : finding.getEntities().companies()) {
                if (name == null || name.isBlank() || name.length() > 512) continue;
                String key = DartCompanyRepository.identityKey(name);
                var matching = catalog.getOrDefault(key, List.of());
                // A company name plus its adjacent stock code may disambiguate duplicate or short names.
                var stockCodes = adjacentStocks(body, name);
                if (stockCodes.size() > 1) continue;
                if (!stockCodes.isEmpty()) matching = matching.stream().filter(company -> stockCodes.contains(company.stockCode())).toList();
                if (matching.size() != 1) continue;
                Company company = matching.getFirst();
                if (ambiguousName(key) && stockCodes.isEmpty()) continue;
                var mention = mentionPattern(name).matcher(body);
                if (!mention.find() || !seen.add(company.corpCode())) continue;
                int start = Math.max(0, mention.start() - 100);
                int end = Math.min(body.length(), mention.end() + 350);
                links.add(new ReportIssueCompanySnapshot.Link(company, finding.getId(), article.getId(),
                        issueIds.get(finding.getId()), name.trim(), body.substring(start, end).strip(),
                        article.getCanonicalUrl(), receipts(body)));
            }
        }
        return new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION,
                now.atZone(ApiTimeZone.ZONE).toOffsetDateTime(), links);
    }

    static ReportIssueCompanySnapshot mergeWeekly(WeeklyReportInput input, List<Long> selected, LocalDateTime now) {
        if (input == null) return null;
        Set<Long> ids = Set.copyOf(selected);
        if (!Set.copyOf(input.sourceFindingIds()).containsAll(ids)) return null;
        var sources = input.sources().stream().filter(source -> source.reflectedFindingIds().stream().anyMatch(ids::contains)).toList();
        if (sources.stream().anyMatch(source -> source.companySnapshot() == null || !source.companySnapshot().valid())) return null;
        var links = sources.stream().flatMap(source -> source.companySnapshot().reflected(source.reflectedFindingIds())
                        .reflected(selected).links().stream()).distinct().toList();
        return new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION,
                now.atZone(ApiTimeZone.ZONE).toOffsetDateTime(), links);
    }

    private static boolean ambiguousName(String key) {
        return key.codePointCount(0, key.length()) <= 2 || key.matches("[A-Z]{1,3}")
                || key.endsWith("그룹") || GROUP_NAMES.contains(key);
    }

    private static String spelling(String name) {
        return DartCompanyRepository.identityKey(name).codePoints().mapToObj(value -> Pattern.quote(new String(Character.toChars(value))))
                .collect(Collectors.joining("\\s*"));
    }

    private static Pattern mentionPattern(String name) {
        // Korean particles are allowed only when the particle itself ends at a word boundary.
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + spelling(name)
                + "(?=$|[^\\p{L}\\p{N}]|(?:에서는|에서|으로|에게|보다|처럼|까지|부터|만의|와의|과의|의|은|는|이|가|을|를|와|과|도|만|로)(?=$|[^\\p{L}\\p{N}]))",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static Set<String> adjacentStocks(String body, String name) {
        var matcher = Pattern.compile("(?<![\\p{L}\\p{N}])" + spelling(name)
                        + "\\s*[（(]\\s*(?:종목\\s*코드\\s*[:：]?\\s*)?([0-9A-Z]{6})\\s*[)）]",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS).matcher(body);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) result.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return result;
    }

    static List<String> receipts(String body) {
        Set<String> result = new LinkedHashSet<>();
        var urls = URL.matcher(body);
        while (urls.find()) {
            try {
                var uri = URI.create(urls.group().replaceAll("[.,;:!?。！？、，\\]\\}]+$", ""));
                if (!"dart.fss.or.kr".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
                        || uri.getPort() != -1 || !"/dsaf001/main.do".equals(uri.getPath()) || uri.getRawQuery() == null) continue;
                var numbers = java.util.Arrays.stream(uri.getRawQuery().split("&", -1))
                        .filter(parameter -> parameter.startsWith("rcpNo=")).toList();
                if (numbers.size() == 1 && numbers.getFirst().matches("rcpNo=[0-9]{14}"))
                    result.add(numbers.getFirst().substring("rcpNo=".length()));
            } catch (IllegalArgumentException ignored) { /* Non-URLs never become filing references. */ }
        }
        var explicit = RECEIPT.matcher(body);
        while (explicit.find()) result.add(explicit.group(1));
        return List.copyOf(result);
    }
}
