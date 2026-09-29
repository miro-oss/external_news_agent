package com.example.be.domain.feedback;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportReadingContent;
import com.example.be.domain.topics.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.example.be.domain.feedback.FeedbackModels.*;

@Component
@RequiredArgsConstructor
public class FeedbackSnapshotFactory {
    private final JdbcTemplate jdbc;
    private final TopicRepository topics;

    public Snapshot capture(NewsReport report,List<Finding> findings) {
        var scopes=report.getCollectionContexts().stream().flatMap(c->c.topics().stream()).toList();
        var items=findings.stream().filter(f->f.getArticle().hasFullText()).map(f->{
            var article=f.getArticle();
            // Shared articles can belong to a different accepted collection topic than article.topic.
            List<Long> accepted=jdbc.queryForList("SELECT topic_id FROM news_topic_relevance WHERE run_id=? AND article_id=? AND status='RELEVANT' ORDER BY topic_id",Long.class,f.getRun().getId(),article.getId());
            long topicId=report.getTopicId()!=null?report.getTopicId():
                    accepted.stream().filter(id->scopes.stream().anyMatch(t->t.topicId().equals(id))).findFirst().orElse(article.getTopic().getId());
            CollectionTopicSnapshot scope=scopes.stream().filter(t->t.topicId()==topicId).findFirst()
                    .orElseGet(()->CollectionTopicSnapshot.capture(topics.findById(topicId).orElseThrow(FeedbackErrors::missing)));
            Topic topic=new Topic(topicId,scope.topicName(),Stream.concat(scope.requiredKeywords().stream(),scope.optionalKeywords().stream()).distinct().toList(),scope.excludedKeywords());
            var event=report.getStructuredContent()==null?null:report.getStructuredContent().importantEvents().stream()
                    .filter(e->e.sourceFindingIds().contains(f.getId())).findFirst().orElse(null);
            var sources=new java.util.ArrayList<Article>();
            sources.add(new Article(article.getId(),article.getTitle(),article.getBody(),article.getCanonicalUrl()));
            if(event!=null)findings.stream().filter(other->!other.getArticle().getId().equals(article.getId()) && event.sourceFindingIds().contains(other.getId())
                    && other.getArticle().hasFullText()).map(other->{var a=other.getArticle();return new Article(a.getId(),a.getTitle(),a.getBody(),a.getCanonicalUrl());}).distinct().limit(9).forEach(sources::add);
            long issueId=jdbc.queryForList("SELECT i.id FROM news_issues i JOIN news_issue_articles a ON a.issue_id=i.id WHERE a.article_id=? AND i.topic_id=? ORDER BY i.id",Long.class,article.getId(),topicId).stream().findFirst().orElse(f.getId());
            return new Item(f.getId(),topic,new Issue(issueId,event==null?article.getTitle():event.title(),event==null?f.getSummary():event.summaryKo()),
                    List.copyOf(sources),f.getAnalysisInputHash(),f.getPromptVersion(),f.getLlmModel(),f.getRun().getStartedAt(),report.getPromptVersion(),report.getModelName(),f.getSummary());
        }).toList();
        return new Snapshot(report.getId(),report.getTitle(),report.getGeneratedAt(),items);
    }

    /** Rebind frozen evidence to the final rendered view without re-fetching mutable articles. */
    static Snapshot delivered(NewsReport report,ReportFindings.Visible visible,Snapshot frozen,List<Item> delivered) {
        var allowed=delivered.stream().map(Item::itemId).collect(Collectors.toSet());
        var reading=ReportReadingContent.from(report,visible).structuredContent();
        var events=reading==null?List.<com.example.be.domain.reports.entity.ReportContent.ImportantEvent>of():
                reading.importantEvents().stream().filter(e->!e.sourceFindingIds().isEmpty() && allowed.containsAll(e.sourceFindingIds())).limit(3).toList();
        Map<Long,Item> byFinding=frozen.items().stream().collect(Collectors.toMap(Item::itemId,Function.identity()));
        List<Item> rebound=delivered.stream().map(item->{
            var primary=item.articles().getFirst();
            var event=events.stream().filter(e->e.sourceFindingIds().contains(item.itemId())).findFirst().orElse(null);
            var sources=new java.util.ArrayList<Article>();sources.add(primary);
            if(event!=null)event.sourceFindingIds().stream().map(byFinding::get).filter(java.util.Objects::nonNull)
                    .map(source->source.articles().getFirst()).filter(article->article.id()!=primary.id()).distinct().limit(9).forEach(sources::add);
            // Fallback entries explicitly represent a report source analysis, not a removed combined event.
            var issue=new Issue(item.issue().id(),event==null?primary.title():event.title(),
                    event==null?item.originalAnalysisSummary():event.summaryKo());
            return new Item(item.itemId(),item.topic(),issue,List.copyOf(sources),item.analysisInputHash(),item.promptVersion(),
                    item.model(),item.runStartedAt(),item.reportPromptVersion(),item.reportModel(),item.originalAnalysisSummary());
        }).toList();
        return new Snapshot(frozen.reportId(),frozen.reportTitle(),frozen.generatedAt(),rebound,frozen.topicIds());
    }
}
