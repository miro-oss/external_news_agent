package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportReadingContent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

/** Captures one displayed event, including every referenced source and its original collection context. */
@Component
@RequiredArgsConstructor
public class ReportEventSnapshotFactory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public List<Item> capture(NewsReport report,ReportFindings.Visible visible) {
        var reading=ReportReadingContent.from(report,visible);
        if(reading.structuredContent()==null)return List.of();
        Map<Long,Finding> findings=visible.findings().stream().collect(Collectors.toMap(Finding::getId,Function.identity()));
        Map<Long,EventSource> sources=new LinkedHashMap<>();
        for(Finding finding:visible.findings()) sources.put(finding.getId(),source(report,finding));
        // Bind every card to the displayed report revision as well as its own complete evidence.
        var revision=new LinkedHashMap<String,Object>();
        revision.put("reportId",report.getId());revision.put("title",report.getTitle());
        revision.put("generatedAt",report.getGeneratedAt());revision.put("promptVersion",report.getPromptVersion());
        revision.put("model",report.getModelName());revision.put("reading",reading);
        revision.put("sources",List.copyOf(sources.values()));revision.put("collectionContexts",report.getCollectionContexts());
        String reportHash=FeedbackTokens.hash(json.writeValueAsString(revision));
        List<Item> result=new ArrayList<>();
        var events=reading.structuredContent().importantEvents();
        for(int index=0;index<events.size();index++) {
            var event=events.get(index);
            var ids=event.sourceFindingIds();
            var eventSources=ids.stream().map(sources::get).filter(Objects::nonNull).toList();
            var topics=eventSources.stream().flatMap(s->s.topics().stream()).distinct().toList();
            var articles=eventSources.stream().map(EventSource::article).filter(Objects::nonNull)
                    .collect(Collectors.toMap(Article::id,Function.identity(),(a,b)->a,LinkedHashMap::new));
            String reason=null;
            if(ids.isEmpty() || new HashSet<>(ids).size()!=ids.size() || ids.stream().anyMatch(id->!findings.containsKey(id)))
                reason="이벤트가 참조한 분석 근거를 모두 확인할 수 없어 검토를 보류했습니다.";
            else if(eventSources.stream().anyMatch(s->s.article()==null || s.article().content()==null || s.article().content().isBlank()))
                reason="이벤트의 근거 기사 원문이 누락되어 검토를 보류했습니다.";
            else if(eventSources.stream().anyMatch(s->!s.contextComplete() || s.collectionTopics().isEmpty()))
                reason="일부 근거의 당시 수집 주제 조건이 없어 검토를 보류했습니다.";
            else if(conflictingContexts(eventSources))
                reason="같은 주제의 수집 조건이 실행마다 달라 하나의 기준으로 검토할 수 없습니다.";
            else if(articles.size()>10)
                reason="이벤트의 근거 기사가 10건을 초과하여 일부 원문만으로 판단하지 않고 검토를 보류했습니다.";
            String key=FeedbackTokens.hash(json.writeValueAsString(List.of(reportHash,index,event,eventSources)));
            var context=new EventContext(key,index,event.title(),event.summaryKo(),event.significance(),List.copyOf(ids),
                    topics,eventSources,reason);
            result.add(new Item(null,null,null,List.copyOf(articles.values()),null,null,null,null,
                    report.getPromptVersion(),report.getModelName(),null,context));
        }
        return List.copyOf(result);
    }

    private static boolean conflictingContexts(List<EventSource> sources) {
        Map<Long,Set<List<Object>>> versions=new HashMap<>();
        sources.stream().flatMap(s->s.collectionTopics().stream()).forEach(t->versions.computeIfAbsent(t.topicId(),id->new HashSet<>())
                .add(Arrays.asList(t.topicName(),t.queryText(),t.requiredKeywords(),t.optionalKeywords(),t.excludedKeywords())));
        return versions.values().stream().anyMatch(v->v.size()>1);
    }

    private EventSource source(NewsReport report,Finding finding) {
        var article=finding.getArticle();var run=finding.getRun();
        Long runId=run==null?null:run.getId();
        var accepted=runId==null?List.<Long>of():jdbc.queryForList(
                "SELECT topic_id FROM news_topic_relevance WHERE run_id=? AND article_id=? AND status='RELEVANT' ORDER BY topic_id",
                Long.class,runId,article.getId());
        Set<Long> topicIds=new LinkedHashSet<>(report.getTopicId()==null?accepted:List.of(report.getTopicId()));
        if(topicIds.isEmpty() && article.getTopic()!=null)topicIds.add(article.getTopic().getId());
        var contexts=report.getCollectionContexts().stream().filter(c->Objects.equals(c.runId(),runId))
                .flatMap(c->c.topics().stream()).filter(t->topicIds.contains(t.topicId())).distinct().toList();
        // Preserve every known condition, while recording that it is not the entire accepted scope.
        boolean complete=!contexts.isEmpty() && !topicIds.isEmpty()
                && contexts.stream().map(CollectionTopicSnapshot::topicId).collect(Collectors.toSet()).containsAll(topicIds);
        var topics=contexts.stream().map(t->new Topic(t.topicId(),t.topicName(),
                Stream.concat(t.requiredKeywords().stream(),t.optionalKeywords().stream()).distinct().toList(),t.excludedKeywords())).toList();
        return new EventSource(finding.getId(),runId,new Article(article.getId(),article.getTitle(),article.getBody(),article.getCanonicalUrl()),
                topics,contexts,finding.getAnalysisInputHash(),finding.getPromptVersion(),finding.getLlmModel(),
                run==null?null:run.getStartedAt(),finding.getSummary(),complete);
    }
}
