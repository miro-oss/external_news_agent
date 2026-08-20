package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.collection.entity.Article;

import java.util.Objects;

/** 수집 실행과 분석 대상을 묶어 오케스트레이션 계층에만 전달한다. */
public record AnalysisContext(Long runId, Article article, AgentPlan plan) {

    public AnalysisContext {
        Objects.requireNonNull(runId, "runId는 필수입니다.");
        Objects.requireNonNull(article, "article은 필수입니다.");
        Objects.requireNonNull(plan, "plan은 필수입니다.");
    }

    public AnalysisContext(Long runId, Article article) {
        this(runId, article, AgentPlan.FREE);
    }
}
