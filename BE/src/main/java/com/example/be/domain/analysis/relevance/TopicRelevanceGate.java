package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceRequest;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.DuplicateQuotaReservationException;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/** Separate from importance: only a grounded RELEVANT decision permits detailed analysis. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TopicRelevanceGate {
    public static final String PROMPT_VERSION = "topic-relevance.ko.v8";
    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentQuotaService quota;
    private final TopicRelevanceStore store;
    private final TopicRelevanceFinalizer finalizer;
    private final CollectionResultWriter resultWriter;
    private final ObjectMapper mapper;

    public Set<Key> assess(Long runId, AgentPlan plan, List<Candidate> candidates) {
        if (candidates.isEmpty()) return Set.of();
        Map<Key, TopicRelevanceStore.Assessment> previous = new HashMap<>();
        store.findByRun(runId).forEach(a -> previous.put(new Key(a.articleId(), a.topicId()), a));
        Map<Long, List<Prepared>> pending = new LinkedHashMap<>();
        Set<Key> accepted = new HashSet<>();
        Set<Key> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            Key key = new Key(candidate.article().getId(), candidate.topic().getId());
            if (!seen.add(key)) continue;
            var topic = topicInput(candidate.topic());
            var article = new AgentTopicRelevanceRequest.ArticleInput(candidate.article().getId(),
                    clip(candidate.article().getTitle(), 1000), clip(candidate.article().getSummary(), 1000),
                    clip(candidate.article().getBody(), 5000));
            // Full input participates even when the provider receives a bounded excerpt.
            String inputHash = hash(mapper.writeValueAsString(topic) + "\n" + PROMPT_VERSION + "\n" + plan
                    + "\n" + properties.getFreeModel() + "\n" + properties.getPaidModel()
                    + "\n" + candidate.article().getTitle() + "\n" + candidate.article().getSummary()
                    + "\n" + candidate.article().getBody());
            var cached = previous.get(key);
            if (cached != null && cached.inputHash().equals(inputHash)) {
                if (cached.status() == TopicRelevanceStatus.RELEVANT) accepted.add(key);
                continue;
            }
            pending.computeIfAbsent(topic.id(), ignored -> new ArrayList<>())
                    .add(new Prepared(topic, article, inputHash, candidate.article().hasFullText()));
        }
        for (List<Prepared> topicCandidates : pending.values()) {
            for (Prepared candidate : topicCandidates) {
                accepted.addAll(assessArticle(runId, plan, candidate));
            }
        }
        return Set.copyOf(accepted);
    }

    private Set<Key> assessArticle(Long runId, AgentPlan plan, Prepared candidate) {
        // One article per HTTP request isolates context, quota settlement, and failures.
        List<Prepared> batch = List.of(candidate);
        // Commit a hold before external I/O. A crash or stale finding cannot imply acceptance.
        store.saveAll(held(runId, batch, "주제 적합성 판정 대기 또는 미완료입니다."));
        if (!properties.isEnabled()) {
            hold(runId, batch, "Agent가 비활성화되어 주제 적합성 판정을 보류했습니다.");
            return Set.of();
        }
        List<Prepared> ready = batch.stream().filter(p -> p.fullText()
                && text(p.article().title(), 1000) && text(p.article().bodyText(), 5000)).toList();
        if (ready.isEmpty()) return Set.of();
        String batchHash = hash(mapper.writeValueAsString(ready));
        String key = "topic-relevance:" + runId + ":" + ready.getFirst().topic().id() + ":" + batchHash;
        var request = new AgentTopicRelevanceRequest(key, plan, ready.getFirst().topic(),
                ready.stream().map(Prepared::article).toList());
        // The Agent escapes angle brackets before submitting JSON to the provider.
        String serialized = mapper.writeValueAsString(request);
        long providerInputLength = serialized.length()
                + 5L * serialized.chars().filter(c -> c == '<' || c == '>').count();
        if (providerInputLength > 85_000) {
            hold(runId, ready, "주제 적합성 입력 상한을 초과해 판정을 보류했습니다.");
            return Set.of();
        }
        QuotaReservation reservation;
        try {
            reservation = quota.reserve(runId, key, AgentTask.TOPIC_RELEVANCE, plan);
        } catch (QuotaExceededException | DuplicateQuotaReservationException unavailable) {
            hold(runId, ready, "예산 부족 또는 진행 중인 동일 요청으로 주제 적합성 판정을 보류했습니다.");
            return Set.of();
        }
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentTopicRelevanceResponse response = null;
        try {
            response = client.topicRelevance(request);
            validate(response, request);
            var byId = new HashMap<Long, Prepared>();
            ready.forEach(p -> byId.put(p.article().articleId(), p));
            String modelName = response.meta().model();
            List<TopicRelevanceStore.Assessment> assessments = response.decisions().stream().map(d -> {
                Prepared prepared = byId.get(d.articleId());
                return new TopicRelevanceStore.Assessment(runId, prepared.topic().id(), d.articleId(),
                        TopicRelevanceStatus.valueOf(d.status()), d.reason(), prepared.inputHash(), PROMPT_VERSION,
                        modelName, mapper.writeValueAsString(d.evidenceQuotes()));
            }).toList();
            finalizer.success(runId, request, response, assessments, hash(mapper.writeValueAsString(request)), reservation, startedAt);
            if (assessments.stream().anyMatch(a -> a.status() == TopicRelevanceStatus.UNCERTAIN)) {
                warn(runId, "주제와의 관련성을 확정하지 못한 기사 분석을 보류했습니다.");
            }
            return assessments.stream().filter(a -> a.status() == TopicRelevanceStatus.RELEVANT)
                    .map(a -> new Key(a.articleId(), a.topicId())).collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException error) {
            AgentClientException failure = failure(error, response);
            finalizer.failure(runId, request, held(runId, ready, "주제 적합성 판정 실패로 분석을 보류했습니다."),
                    hash(mapper.writeValueAsString(request)), reservation, startedAt, failure);
            warn(runId, "주제 적합성 판정 실패로 해당 기사 분석을 보류했습니다.");
            return Set.of();
        }
    }

    static void validate(AgentTopicRelevanceResponse response, AgentTopicRelevanceRequest request) {
        if (response == null || response.meta() == null || response.decisions() == null
                || response.decisions().size() != request.articles().size()) throw invalid();
        var meta = response.meta();
        if (!Set.of("mock", "openai", "mindlogic-claude").contains(Objects.toString(meta.provider(), ""))
                || !text(meta.model(), 100) || meta.model().getBytes(StandardCharsets.UTF_8).length > 100
                || !PROMPT_VERSION.equals(meta.promptVersion()) || meta.mock() == null
                || meta.mock() != "mock".equals(meta.provider()) || !Boolean.FALSE.equals(meta.truncated())
                || meta.inputTokens() == null || meta.inputTokens() < 0 || meta.outputTokens() == null || meta.outputTokens() < 0
                || !amount(meta.costUsd(), 6) || !amount(meta.credits(), 7)) throw invalid();
        Map<Long, AgentTopicRelevanceRequest.ArticleInput> articles = new HashMap<>();
        request.articles().forEach(a -> articles.put(a.articleId(), a));
        Set<Long> seen = new HashSet<>();
        for (var decision : response.decisions()) {
            if (decision == null || !seen.add(decision.articleId()) || !articles.containsKey(decision.articleId())
                    || !Set.of("RELEVANT", "IRRELEVANT", "UNCERTAIN").contains(Objects.toString(decision.status(), ""))
                    || !text(decision.reason(), 500) || decision.evidenceQuotes() == null || decision.evidenceQuotes().size() > 3
                    || (!"UNCERTAIN".equals(decision.status()) && decision.evidenceQuotes().isEmpty())
                    || (meta.mock() && !"UNCERTAIN".equals(decision.status()))) throw invalid();
            var article = articles.get(decision.articleId());
            for (String quote : decision.evidenceQuotes()) {
                if (!text(quote, 300) || !(contains(article.title(), quote) || contains(article.summary(), quote)
                        || contains(article.bodyText(), quote))) throw invalid();
            }
        }
    }

    private static boolean contains(String source, String quote) { return source != null && source.contains(quote); }
    private static boolean text(String value, int max) { return StringUtils.hasText(value) && value.length() <= max; }
    private static boolean amount(BigDecimal value, int integerDigits) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.TEN.pow(integerDigits)) < 0;
    }
    private static AgentClientException invalid() {
        return new AgentClientException("SCHEMA_VIOLATION", "주제 적합성 응답이 요청 또는 근거 계약과 일치하지 않습니다.");
    }
    private AgentClientException failure(RuntimeException error, AgentTopicRelevanceResponse response) {
        if (error instanceof AgentClientException failure && failure.getUsage() != null) {
            return new AgentClientException(failure.getCode(), failure.getMessage(), failure,
                    safeUsage(failure.getUsage()), failure.getTimeoutPhase(), failure.getExecutionMetadata());
        }
        if (response != null && response.meta() != null) {
            var meta = response.meta();
            return new AgentClientException("SCHEMA_VIOLATION", "주제 적합성 응답 처리에 실패했습니다.", error,
                    safeUsage(new AgentClientException.Usage(meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits())));
        }
        return error instanceof AgentClientException failure ? failure
                : new AgentClientException("INTERNAL_ERROR", "주제 적합성 판정에 실패했습니다.", error);
    }
    private AgentClientException.Usage safeUsage(AgentClientException.Usage usage) {
        return new AgentClientException.Usage(
                usage.inputTokens() != null && usage.inputTokens() >= 0 ? usage.inputTokens() : null,
                usage.outputTokens() != null && usage.outputTokens() >= 0 ? usage.outputTokens() : null,
                amount(usage.costUsd(), 6) ? usage.costUsd() : null,
                amount(usage.credits(), 7) ? usage.credits() : null);
    }
    private void hold(Long runId, List<Prepared> batch, String reason) {
        store.saveAll(held(runId, batch, reason));
        warn(runId, reason);
    }
    private void warn(Long runId, String reason) {
        try {
            resultWriter.addAgentWarning(runId, "TOPIC_RELEVANCE_UNCERTAIN", reason);
        } catch (RuntimeException error) {
            // A diagnostic write must not undo a settled decision or settle the reservation twice.
            log.warn("주제 적합성 경고를 저장하지 못했습니다. runId={}", runId, error);
        }
    }
    private List<TopicRelevanceStore.Assessment> held(Long runId, List<Prepared> batch, String reason) {
        return batch.stream().map(p -> new TopicRelevanceStore.Assessment(runId, p.topic().id(), p.article().articleId(),
                TopicRelevanceStatus.UNCERTAIN, reason, p.inputHash(), PROMPT_VERSION, null, "[]")).toList();
    }
    private AgentTopicRelevanceRequest.TopicInput topicInput(Topic topic) {
        return new AgentTopicRelevanceRequest.TopicInput(topic.getId(), topic.getName(), topic.getQueryText(),
                keywords(topic.getRequiredKeywords()), keywords(topic.getOptionalKeywords()), keywords(topic.getExcludedKeywords()));
    }
    private List<String> keywords(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private static String clip(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Candidate(Article article, Topic topic) {}
    public record Key(Long articleId, Long topicId) {}
    private record Prepared(AgentTopicRelevanceRequest.TopicInput topic,
                            AgentTopicRelevanceRequest.ArticleInput article, String inputHash, boolean fullText) {}
}
