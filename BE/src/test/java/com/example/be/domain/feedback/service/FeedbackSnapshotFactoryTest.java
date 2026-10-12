package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportReadingContent;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackSnapshotFactoryTest {
    @Test void captureUsesProjectedEventInsteadOfRejectedStoredEventSharingItsSource() {
        var jdbc=mock(JdbcTemplate.class);var topics=mock(TopicRepository.class);
        var factory=new FeedbackSnapshotFactory(jdbc,topics);
        var bad=new ReportContent.ImportantEvent("잘못된 결합 사건","잘못된 결합 요약","",List.of(11L,12L));
        var good=new ReportContent.ImportantEvent("정상 사건","정상 요약","",List.of(11L));
        var stored=new ReportContent(List.of("잘못된 결합 요약"),List.of(bad,good),List.of(),List.of());
        var scope=new CollectionTopicSnapshot(2L,"주제","검색",List.of("핵심"),List.of(),List.of(),100,1440);
        var report=NewsReport.builder().id(5L).topicId(2L).title("보고서").structuredContent(stored)
                .collectionContexts(List.of(new ReportCollectionContext(31L,List.of(scope)))).build();
        var article=Article.builder().id(21L).title("기사").body("검증할 원문").fetchStatus(FetchStatus.FULLTEXT)
                .canonicalUrl("https://example.test/21").build();
        var finding=Finding.builder().id(11L).article(article).run(CollectionRun.builder().id(31L).build()).summary("원래 분석").build();
        var reading=new ReportReadingContent("정상 요약",new ReportContent(List.of("정상 요약"),List.of(good),List.of(),List.of()));

        var snapshot=factory.capture(report,List.of(finding),reading);

        assertEquals(1,snapshot.items().size());
        var item=snapshot.items().getFirst();
        assertEquals("정상 사건",item.issue().title());
        assertEquals("정상 요약",item.issue().summary());
        assertEquals(List.of(21L),item.articles().stream().map(com.example.be.domain.feedback.model.FeedbackModels.Article::id).toList());
        assertEquals("원래 분석",item.originalAnalysisSummary());
        assertSame(stored,report.getStructuredContent());
        verifyNoInteractions(topics);
    }
}
