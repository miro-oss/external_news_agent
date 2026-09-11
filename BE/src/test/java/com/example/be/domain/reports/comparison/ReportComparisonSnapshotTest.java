package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReportComparisonSnapshotTest {
    @Test void laterArticleAndFindingEditsCannotRewriteSavedClaimsQuotesOrSourceMetadata() {
        var topic = Topic.builder().id(1L).build();
        var article = Article.builder().id(11L).topic(topic).title("당시 기사 제목")
                .canonicalUrl("https://example.com/original").build();
        var run = CollectionRun.builder().id(10L).build();
        var finding = Finding.builder().id(12L).article(article).run(run).summary("당시 요약")
                .analysisInputHash("original-analysis").promptVersion("a1.ko.v1")
                .keyPoints(List.of(new FindingKeyPoint("하반기 양산", List.of(0), "grounded")))
                .sections(List.of(new FindingSection(0, "하반기에 양산할 계획이라고 발표했다."))).build();
        var issue = NewsIssue.builder().id(88L).topic(topic).title("당시 이슈 제목").build();
        var snapshot = ReportComparisonSnapshot.capture(List.of(finding), Map.of(11L, issue));
        article.applyUpdate("현재 기사 제목", "현재 요약", "새 본문", "new", FetchStatus.FULLTEXT, run, LocalDateTime.now());
        finding.replaceAnalysis(Finding.builder().summary("교체된 요약")
                .keyPoints(List.of(new FindingKeyPoint("10월 양산", List.of(0), "grounded")))
                .sections(List.of(new FindingSection(0, "10월에 양산한다."))).build());
        assertEquals("하반기 양산", snapshot.issues().getFirst().side().claims().getFirst().text());
        assertEquals("당시 기사 제목", snapshot.issues().getFirst().side().title());
        assertEquals("하반기에 양산할 계획이라고 발표했다.", snapshot.issues().getFirst().side().claims().getFirst().evidence().getFirst().text());
        assertEquals("original-analysis", snapshot.issues().getFirst().analysisInputHash());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.issues().clear());
    }

    @Test void finalCoverageDropsUnreflectedInputsAndRejectsMissingSnapshotIds() {
        var original = ComparisonFixtures.snapshot(ComparisonFixtures.side(1, 10, "이전 주장"), ComparisonFixtures.side(2, 20, "다른 주장"));
        assertEquals(1, original.reflected(List.of(20L)).issues().size());
        assertEquals(20, original.reflected(List.of(20L)).issues().getFirst().findingId());
        assertThrows(IllegalStateException.class, () -> original.reflected(List.of(99L)));
        assertTrue(original.hasKnownScopes());
        assertFalse(original.withScopes(List.of()).hasKnownScopes());
        assertEquals(2, original.issues().size());
    }
}
