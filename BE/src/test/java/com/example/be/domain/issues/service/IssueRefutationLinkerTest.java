package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueRelation;
import com.example.be.domain.issues.entity.IssueRelationType;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueRefutationLinkerTest {

    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueRelationRepository relationRepository = mock(IssueRelationRepository.class);
    private final IssueRefutationLinker linker = new IssueRefutationLinker(
            issueArticleRepository, relationRepository, new IssueStanceClassifier());

    @Test
    void linksNewCorrectionIssueBackToLatestSharedEntityIssue() {
        Topic topic = Topic.builder().id(7L).name("HBM").build();
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-02T12:00:00+09:00");
        NewsIssue original = NewsIssue.builder()
                .id(10L)
                .topic(topic)
                .entities(List.of("삼성전자", "HBM4"))
                .lastSeenAt(observedAt.minusHours(2))
                .build();
        NewsIssue correctionIssue = NewsIssue.builder()
                .id(20L)
                .topic(topic)
                .entities(List.of("삼성전자", "HBM4"))
                .firstSeenAt(observedAt)
                .lastSeenAt(observedAt)
                .build();
        Article originalArticle = Article.builder()
                .id(1L)
                .title("삼성전자 HBM4 양산 확정")
                .summary("연내 양산한다.")
                .build();
        Article correctionArticle = Article.builder()
                .id(2L)
                .title("삼성전자 HBM4 양산 보도 정정")
                .summary("기존 보도가 오보라고 밝혔다.")
                .build();
        IssueArticle originalMembership = IssueArticle.builder()
                .issue(original)
                .article(originalArticle)
                .role(IssueArticleRole.REPRESENTATIVE)
                .build();
        when(issueArticleRepository.findRecentRepresentativesByTopicIdExcludingIssueId(
                eq(7L), eq(20L), any())).thenReturn(List.of(originalMembership));
        when(relationRepository.existsByFromIssueIdAndToIssueIdAndRelationType(
                20L, 10L, IssueRelationType.REFUTES)).thenReturn(false);

        assertEquals(10L, linker.linkNewIssue(correctionIssue, correctionArticle).orElseThrow());
        ArgumentCaptor<IssueRelation> relation = ArgumentCaptor.forClass(IssueRelation.class);
        verify(relationRepository).save(relation.capture());
        assertEquals(IssueRelationType.REFUTES, relation.getValue().getRelationType());
        assertEquals(correctionIssue, relation.getValue().getFromIssue());
        assertEquals(original, relation.getValue().getToIssue());
    }
}
