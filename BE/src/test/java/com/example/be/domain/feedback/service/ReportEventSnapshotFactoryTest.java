package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.feedback.repository.FeedbackStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.example.be.domain.feedback.model.FeedbackModels.*;

class ReportEventSnapshotFactoryTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReportEventSnapshotFactory factory=new ReportEventSnapshotFactory(jdbc,new ObjectMapper());

    @Test void repeatedErrorProjectionKeepsSurvivingRealSnapshotHashAndSavedOriginalIndex() {
        var a=finding(11,21,31,41,"오류 사건 원문");var b=finding(12,22,31,41,"정상 사건 원문");
        var content=new ReportContent(List.of("오류를 포함한 요약"),List.of(
                new ReportContent.ImportantEvent("오류 사건","오류 요약","이유",List.of(11L)),
                new ReportContent.ImportantEvent("정상 사건","정상 요약","정상 이유",List.of(12L))),List.of(),List.of());
        var report=NewsReport.builder().id(1L).title("보고서").markdownBody("오류 원본")
                .structuredContent(content).collectionContexts(List.of(context(31,scope(41,"검색",List.of("HBM"),List.of())))).build();
        var visible=new ReportFindings.Visible(List.of(a,b),false);
        var original=factory.capture(report,visible);
        var review=new Feedback(5L,null,null,1L,null,Category.SUMMARY_ERROR,"의견",false,"hash",Status.COMPLETED,
                "CONFIRMED_ERROR","설명",java.time.LocalDateTime.of(2026,10,1,10,0),original.getFirst(),original.getFirst().event().key());
        var projection=new ReportEventFeedbackProjection(mock(FeedbackStore.class),factory);
        for(int refresh=0;refresh<3;refresh++) {
            var view=projection.project(report,visible,List.of(review));
            assertEquals(List.of(12L),view.findings().stream().map(Finding::getId).toList());
            assertEquals(List.of(content.importantEvents().get(1)),view.readingContent().structuredContent().importantEvents());
            var surviving=projection.events(report,visible,List.of(review)).getFirst();
            assertEquals(original.get(1).event().key(),surviving.event().key());
            assertEquals(1,surviving.event().index());
            assertEquals(List.of(12L),surviving.event().sourceFindingIds());
            assertSame(content,report.getStructuredContent());
            assertEquals("오류 원본",report.getMarkdownBody());
        }
    }

    @Test void eventPreservesEverySourceAndTopicAndKeyBindsDisplayedTextAndOriginalBody() {
        var a=finding(11,21,31,41,"첫째 원문");var b=finding(12,22,32,42,"둘째 원문");
        var report=report(List.of(11L,12L),List.of(context(31,scope(41,"검색1",List.of("필수1"),List.of())),context(32,scope(42,"검색2",List.of(),List.of("선택2")))));
        var item=factory.capture(report,new ReportFindings.Visible(List.of(a,b),false)).getFirst();
        assertNull(item.itemId());assertNull(item.topic());assertNull(item.issue());
        assertEquals(List.of(11L,12L),item.event().sourceFindingIds());
        assertEquals(List.of(21L,22L),item.articles().stream().map(article->article.id()).toList());
        assertEquals(List.of(41L,42L),item.event().topics().stream().map(topic->topic.id()).toList());
        assertEquals("중요한 이유",item.event().significance());assertNull(item.event().unavailableReason());
        var changed=factory.capture(report,new ReportFindings.Visible(List.of(a,finding(12,22,32,42,"수정된 원문")),false)).getFirst();
        assertNotEquals(item.event().key(),changed.event().key());
        var reordered=report(List.of(12L,11L),report.getCollectionContexts());
        assertNotEquals(item.event().key(),factory.capture(reordered,new ReportFindings.Visible(List.of(a,b),false)).getFirst().event().key());
    }

    @Test void sameTopicWithDifferentQueryOrRequiredOptionalRolesIsNotCollapsed() {
        var a=finding(11,21,31,41,"원문1");var b=finding(12,22,32,41,"원문2");
        for(var second:List.of(scope(41,"다른 검색",List.of("HBM"),List.of()),scope(41,"검색",List.of(),List.of("HBM")))) {
            var report=report(List.of(11L,12L),List.of(context(31,scope(41,"검색",List.of("HBM"),List.of())),context(32,second)));
            var event=factory.capture(report,new ReportFindings.Visible(List.of(a,b),false)).getFirst().event();
            assertTrue(event.unavailableReason().contains("실행마다"));
            assertEquals(second,event.sources().get(1).collectionTopics().getFirst());
        }
    }

    @Test void topicReportUsesItsExplicitScopeEvenWhenSharedArticleWasAcceptedElsewhere() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(31L),eq(21L))).thenReturn(List.of(41L,42L));
        var report=NewsReport.builder().id(1L).title("주제 보고서").topicId(41L)
                .structuredContent(content(List.of(11L))).collectionContexts(List.of(context(31,scope(41,"검색",List.of("HBM"),List.of())))).build();
        var event=factory.capture(report,new ReportFindings.Visible(List.of(finding(11,21,31,42,"원문")),false)).getFirst().event();
        assertNull(event.unavailableReason());assertEquals(List.of(41L),event.topics().stream().map(t->t.id()).toList());
    }

    @Test void moreThanTenArticlesRemainFrozenAndMissingContextIsExplicit() {
        var findings=IntStream.rangeClosed(1,11).mapToObj(i->finding(i,i+20,31,41,"원문 "+i)).toList();
        var ids=findings.stream().map(Finding::getId).toList();
        var item=factory.capture(report(ids,List.of(context(31,scope(41,"검색",List.of(),List.of())))),new ReportFindings.Visible(findings,false)).getFirst();
        assertEquals(11,item.articles().size());assertEquals(11,item.event().sources().size());
        assertTrue(item.event().unavailableReason().contains("10건"));
        var noContext=factory.capture(report(List.of(1L),List.of()),new ReportFindings.Visible(List.of(findings.getFirst()),false)).getFirst();
        assertTrue(noContext.event().unavailableReason().contains("당시 수집"));
    }

    @Test void partialTopicContextPreservesKnownConditionsButMarksTheEntireSourceIncomplete() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(31L),eq(21L))).thenReturn(List.of(41L,42L));
        var saved=scope(41,"원래 검색",List.of("HBM"),List.of());
        var report=report(List.of(11L),List.of(context(31,saved)));
        var event=factory.capture(report,new ReportFindings.Visible(List.of(finding(11,21,31,41,"원문")),false)).getFirst().event();
        assertFalse(event.sources().getFirst().contextComplete());
        assertEquals(List.of(saved),event.sources().getFirst().collectionTopics());
        assertEquals(List.of(41L),event.topics().stream().map(t->t.id()).toList());
        assertTrue(event.unavailableReason().contains("당시 수집"));
    }

    private static Finding finding(long id,long articleId,long runId,long topicId,String body) {
        var topic=com.example.be.domain.topics.entity.Topic.builder().id(topicId).name("주제"+topicId).build();
        var article=com.example.be.domain.collection.entity.Article.builder().id(articleId).topic(topic).title("기사"+articleId)
                .body(body).canonicalUrl("https://example.test/"+articleId).fetchStatus(FetchStatus.FULLTEXT).build();
        return Finding.builder().id(id).article(article).run(CollectionRun.builder().id(runId).build())
                .summary("분석"+id).analysisInputHash("hash"+id).promptVersion("analysis-v1").llmModel("fixture").build();
    }
    private static CollectionTopicSnapshot scope(long id,String query,List<String> required,List<String> optional) {
        return new CollectionTopicSnapshot(id,"주제"+id,query,required,optional,List.of("제외"),100,1440);
    }
    private static ReportCollectionContext context(long run,CollectionTopicSnapshot scope) { return new ReportCollectionContext(run,List.of(scope)); }
    private static ReportContent content(List<Long> ids) { return new ReportContent(List.of(),List.of(new ReportContent.ImportantEvent("이벤트","이벤트 전체 요약","중요한 이유",ids)),List.of(),List.of()); }
    private static NewsReport report(List<Long> ids,List<ReportCollectionContext> contexts) {
        return NewsReport.builder().id(1L).title("보고서").structuredContent(content(ids)).collectionContexts(contexts).build();
    }
}
