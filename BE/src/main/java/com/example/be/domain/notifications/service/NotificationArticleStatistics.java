package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportArticleStatistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Collection counts are independent of selected issues and their keyword denominator. */
@Component
@RequiredArgsConstructor
public class NotificationArticleStatistics {
    private final CollectionRunArticleRepository observations;

    @Transactional(readOnly = true)
    public String summary(NewsReport report) {
        var counts = ReportArticleStatistics.forReport(report, observations);
        return "수집 기사 " + counts.getTotalCount() + "건 · 신규 " + counts.getNewCount()
                + "건 · 기존 " + counts.getExistingCount() + "건";
    }
}
