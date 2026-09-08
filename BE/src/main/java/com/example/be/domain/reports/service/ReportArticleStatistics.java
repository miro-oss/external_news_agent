package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository.ReportArticleObservation;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReportArticleStatistics {
    private ReportArticleStatistics() {}

    static ReportResDTO.ArticleStats count(List<ReportArticleObservation> observations) {
        Set<Long> articles = new HashSet<>();
        Set<Long> newArticles = new HashSet<>();
        for (ReportArticleObservation observation : observations) {
            articles.add(observation.getArticleId());
            if (observation.getChangeType() == ChangeType.NEW) newArticles.add(observation.getArticleId());
        }
        return ReportResDTO.ArticleStats.builder().totalCount(articles.size()).newCount(newArticles.size())
                .existingCount(articles.size() - newArticles.size()).build();
    }
}
