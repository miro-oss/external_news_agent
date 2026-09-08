package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.topics.entity.Topic;

import java.util.Objects;

/** 수집 실행과 분석 대상을 묶어 오케스트레이션 계층에만 전달한다. */
public record AnalysisContext(
        Long runId,
        Article article,
        AgentPlan plan,
        IssueAnalysisContext issue,
        boolean selfCritiqueEligible,
        Topic topicOverride
) {

    public AnalysisContext {
        Objects.requireNonNull(runId, "runId는 필수입니다.");
        Objects.requireNonNull(article, "article은 필수입니다.");
        Objects.requireNonNull(plan, "plan은 필수입니다.");
        issue = issue == null ? IssueAnalysisContext.empty() : issue;
    }

    public AnalysisContext(Long runId, Article article, AgentPlan plan) {
        this(runId, article, plan, IssueAnalysisContext.empty(), false, null);
    }

    public AnalysisContext(Long runId,
                           Article article,
                           AgentPlan plan,
                           IssueAnalysisContext issue) {
        this(runId, article, plan, issue, false, null);
    }

    public AnalysisContext(Long runId, Article article, AgentPlan plan,
                           IssueAnalysisContext issue, boolean selfCritiqueEligible) {
        this(runId, article, plan, issue, selfCritiqueEligible, null);
    }

    /** 실행 접수 시의 조건이 있으면 현재 관리 화면의 주제 설정보다 우선한다. */
    public Topic topic() {
        return topicOverride == null ? article.getTopic() : topicOverride;
    }

    public AnalysisContext withArticle(Article target) {
        return new AnalysisContext(runId, target, plan, issue, selfCritiqueEligible, topicOverride);
    }
}
