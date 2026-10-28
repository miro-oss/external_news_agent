package com.example.be.domain.companyinterest;

import com.example.be.domain.analysis.agent.service.FeedbackAgentGateway;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.companyinterest.CompanyInterestModels.ActiveCriterion;
import com.example.be.domain.feedback.service.FeedbackResultValidator;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.service.WeeklyReportSelection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** One evidence-bound selection policy for every report scope, before ranking and fallback. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyInterestSelection {
    private final CompanyInterestStore store;
    private final FeedbackAgentGateway agent;
    private final ObjectMapper json;
    private final TopicRelevancePolicy relevance;
    private final FindingRepository findings;

    public Result select(List<Finding> candidates, Long topicId) {
        if (candidates.isEmpty()) return new Result(candidates, List.of());
        Map<Long, Set<Long>> topics = topicId == null ? relevance.relevantTopicIdsByFinding(candidates) : Map.of();
        List<Candidate> input = candidates.stream().map(finding -> new Candidate(finding.getId(),
                finding.getArticle().getId(), finding.getArticle().getTitle(), body(finding),
                Objects.toString(finding.getArticle().getCanonicalUrl(), ""),
                topicId == null ? topicIds(finding, topics)
                        : Set.of(topicId))).toList();
        Evaluation evaluation = evaluate(input);
        return new Result(candidates.stream().filter(f -> !evaluation.excluded().contains(f.getId())).toList(),
                evaluation.applications());
    }

    /** Read frozen analysis sentences and frozen topic/title identities, never generate new WEEKLY claims. */
    public WeeklyResult selectWeekly(WeeklyReportInput input) {
        return selectWeekly(input, Set.of());
    }

    /** Extra topics check the keyword universe's criterion scope; they never add AI evaluation items. */
    public WeeklyResult selectWeekly(WeeklyReportInput input, Set<Long> keywordTopicIds) {
        if (input.sourceFindingIds().isEmpty() && keywordTopicIds.isEmpty()) return new WeeklyResult(input, List.of());
        Map<Long, Finding> byId = (input.sourceFindingIds().isEmpty() ? List.<Finding>of()
                : findings.findForReportByIdIn(input.sourceFindingIds())).stream()
                .collect(Collectors.toMap(Finding::getId, f -> f));
        Map<Long, Set<Long>> assessedTopics = relevance.relevantTopicIdsByFinding(List.copyOf(byId.values()));
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        input.sources().forEach(source -> {
            if (source.evidenceSnapshot() == null) return;
            source.evidenceSnapshot().issues().forEach(issue -> {
                Finding finding = byId.get(issue.findingId());
                if (finding == null || issue.side() == null || !source.reflectedFindingIds().contains(issue.findingId())) return;
                // Legacy findings without their original analysis text remain included.
                if (finding.getSections() == null || finding.getSections().isEmpty()) return;
                Candidate existing = candidates.get(issue.findingId());
                Set<Long> topics = new LinkedHashSet<>(existing == null ? Set.of() : existing.topicIds());
                topics.add(issue.side().topicId());
                topics.addAll(assessedTopics.getOrDefault(issue.findingId(), Set.of()));
                candidates.put(issue.findingId(), new Candidate(issue.findingId(), finding.getArticle().getId(),
                        issue.side().title(), body(finding), "", Set.copyOf(topics)));
            });
        });
        Evaluation evaluation = evaluate(List.copyOf(candidates.values()), keywordTopicIds);
        return new WeeklyResult(WeeklyReportSelection.withoutFindings(input, evaluation.excluded()),
                evaluation.applications(), evaluation.criterionTopicIds());
    }

    private Evaluation evaluate(List<Candidate> candidates) {
        return evaluate(candidates, Set.of());
    }

    private Evaluation evaluate(List<Candidate> candidates, Set<Long> additionalTopicIds) {
        if (candidates.isEmpty() && additionalTopicIds.isEmpty()) return new Evaluation(Set.of(), List.of(), Set.of());
        Set<Long> topicIds = candidates.stream().flatMap(candidate -> candidate.topicIds().stream()).collect(Collectors.toSet());
        topicIds.addAll(additionalTopicIds);
        List<ActiveCriterion> active;
        try { active = List.copyOf(store.activeForTopics(topicIds)); }
        catch (RuntimeException unavailable) {
            log.warn("회사 관심 기준을 읽지 못해 보고서 후보를 유지합니다.");
            return new Evaluation(Set.of(), List.of(), Set.copyOf(topicIds));
        }
        Map<Long, List<ActiveCriterion>> byTopic = active.stream().collect(Collectors.groupingBy(ActiveCriterion::topicId));
        Map<Long, Set<Long>> suppressedTopics = new HashMap<>();
        Map<ActiveCriterion, Set<Long>> cited = new LinkedHashMap<>();
        Map<ActiveCriterion, Integer> evaluated = new LinkedHashMap<>();
        for (var entry : byTopic.entrySet()) {
            List<Candidate> scoped = candidates.stream().filter(candidate -> candidate.topicIds().contains(entry.getKey())).toList();
            if (scoped.isEmpty()) continue;
            List<ActiveCriterion> criteria = List.copyOf(entry.getValue());
            TopicEvaluation result = evaluateTopic(scoped, criteria);
            result.evaluated().forEach((criterion, ids) -> {
                if (!ids.isEmpty()) evaluated.put(criterion, ids.size());
            });
            cited.putAll(result.suppressed());
            result.suppressed().values().stream().flatMap(Set::stream).distinct().forEach(id ->
                    suppressedTopics.computeIfAbsent(id, ignored -> new HashSet<>()).add(entry.getKey()));
        }
        // A preference for one topic must not silently remove an article still wanted by another accepted topic.
        Set<Long> excluded = candidates.stream().filter(candidate -> !candidate.topicIds().isEmpty()
                && suppressedTopics.getOrDefault(candidate.findingId(), Set.of()).containsAll(candidate.topicIds()))
                .map(Candidate::findingId).collect(Collectors.toSet());
        List<Application> applications = evaluated.entrySet().stream().map(entry -> new Application(entry.getKey(),
                entry.getValue(), (int) cited.getOrDefault(entry.getKey(), Set.of()).stream()
                .filter(excluded::contains).count())).toList();
        return new Evaluation(Set.copyOf(excluded), applications,
                active.stream().map(ActiveCriterion::topicId).collect(Collectors.toSet()));
    }

    private TopicEvaluation evaluateTopic(List<Candidate> candidates, List<ActiveCriterion> criteria) {
        Map<ActiveCriterion, Set<Long>> suppressed = new LinkedHashMap<>();
        Map<ActiveCriterion, Set<Long>> evaluated = new LinkedHashMap<>();
        // Multiple findings of the same article are evaluated independently to preserve their frozen analysis text.
        List<List<Candidate>> batches = new ArrayList<>();
        List<Candidate> batch = new ArrayList<>();
        for (Candidate candidate : candidates) {
            // Do not clip evidence or let one oversized article block all other candidates.
            if (!candidate.bounded()) continue;
            if (batch.size() == 10 || batch.stream().anyMatch(value -> value.articleId() == candidate.articleId())) {
                batches.add(List.copyOf(batch)); batch.clear();
            }
            batch.add(candidate);
        }
        if (!batch.isEmpty()) batches.add(List.copyOf(batch));
        for (int start = 0; start < criteria.size(); start += 20) {
            List<ActiveCriterion> selected = criteria.subList(start, Math.min(start + 20, criteria.size()));
            Map<Long, ActiveCriterion> byId = selected.stream().collect(Collectors.toMap(ActiveCriterion::id, criterion -> criterion));
            for (List<Candidate> articles : batches) {
                try {
                ActiveCriterion first = selected.getFirst();
                JsonNode request = json.valueToTree(Map.of(
                        "idempotencyKey", "company-interest:" + UUID.randomUUID(), "plan", "FREE", "scope", "COMPANY_REPORT",
                        "topic", Map.of("id", first.topicId(), "name", first.topicName(), "keywords", List.of(), "negativeKeywords", List.of()),
                        "policies", selected.stream().map(criterion -> Map.of("id", criterion.id(), "instruction", criterion.instruction())).toList(),
                        "articles", articles.stream().map(candidate -> Map.of("id", candidate.articleId(), "title", candidate.title(),
                                "content", candidate.content(), "url", candidate.url())).toList()));
                if (request.toString().length() > 150_000) throw new IllegalArgumentException("Oversized company evaluation");
                JsonNode response = agent.evaluate(null, first.topicId(), request);
                FeedbackResultValidator.evaluate(request, response);
                if (response.path("meta").path("mock").asBoolean(true)) throw new IllegalArgumentException("Mock evaluation");
                for (JsonNode decision : response.path("decisions")) {
                    String status = decision.path("status").asString();
                    if ("UNCERTAIN".equals(status)) continue;
                    long articleId = decision.path("articleId").asLong();
                    long findingId = articles.stream().filter(candidate -> candidate.articleId() == articleId).findFirst().orElseThrow().findingId();
                    selected.forEach(criterion -> evaluated.computeIfAbsent(criterion, ignored -> new HashSet<>()).add(findingId));
                    if ("SUPPRESS".equals(status)) decision.path("policyIds").forEach(id ->
                            suppressed.computeIfAbsent(byId.get(id.asLong()), ignored -> new HashSet<>()).add(findingId));
                }
                } catch (RuntimeException unavailable) {
                    log.warn("회사 관심 기준 평가를 완료하지 못해 해당 기사 묶음을 유지합니다. topicId={} errorType={}",
                            selected.getFirst().topicId(), unavailable.getClass().getSimpleName());
                }
            }
        }
        return new TopicEvaluation(evaluated, suppressed);
    }

    public void record(long reportId, List<Application> applications, LocalDateTime selectedAt) {
        aggregate(applications).forEach(application -> store.recordApplication(reportId, application.criterion(),
                application.evaluatedCount(), application.suppressedCount(), selectedAt));
    }

    public static List<Application> aggregate(List<Application> applications) {
        Map<String, Application> merged = new LinkedHashMap<>();
        applications.forEach(value -> merged.merge(value.criterionId() + ":" + value.version(), value,
                (left, right) -> new Application(left.criterion(), left.evaluatedCount() + right.evaluatedCount(),
                        left.suppressedCount() + right.suppressedCount())));
        return List.copyOf(merged.values());
    }

    private static String body(Finding finding) {
        return finding.getSections() == null || finding.getSections().isEmpty() ? finding.getArticle().getBody()
                : finding.getSections().stream().sorted(Comparator.comparingInt(FindingSection::index))
                    .map(FindingSection::text).filter(Objects::nonNull).collect(Collectors.joining("\n"));
    }

    private static Set<Long> topicIds(Finding finding, Map<Long, Set<Long>> assessed) {
        if (assessed.containsKey(finding.getId())) return assessed.get(finding.getId());
        return finding.getArticle().getTopic() == null || finding.getArticle().getTopic().getId() == null
                ? Set.of() : Set.of(finding.getArticle().getTopic().getId());
    }

    public record Application(ActiveCriterion criterion, int evaluatedCount, int suppressedCount) {
        public long criterionId() { return criterion.id(); }
        public long version() { return criterion.version(); }
    }
    public record Result(List<Finding> findings, List<Application> applications) {
        public Result { findings = List.copyOf(findings); applications = List.copyOf(applications); }
    }
    public record WeeklyResult(WeeklyReportInput input, List<Application> applications, Set<Long> criterionTopicIds) {
        public WeeklyResult { applications = List.copyOf(applications); criterionTopicIds = Set.copyOf(criterionTopicIds); }
        public WeeklyResult(WeeklyReportInput input, List<Application> applications) {
            this(input, applications, applications.stream().map(application -> application.criterion().topicId()).collect(Collectors.toSet()));
        }
    }
    private record Evaluation(Set<Long> excluded, List<Application> applications, Set<Long> criterionTopicIds) { }
    private record TopicEvaluation(Map<ActiveCriterion, Set<Long>> evaluated, Map<ActiveCriterion, Set<Long>> suppressed) { }
    private record Candidate(long findingId, long articleId, String title, String content, String url, Set<Long> topicIds) {
        boolean bounded() { return title != null && !title.isBlank() && title.length() <= 1000
                && content != null && !content.isBlank() && content.length() <= 10_000 && url.length() <= 2000; }
    }
}
