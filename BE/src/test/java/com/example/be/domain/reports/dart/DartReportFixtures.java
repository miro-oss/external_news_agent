package com.example.be.domain.reports.dart;

import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.dart.DartModels.Company;
import com.example.be.domain.dart.DartModels.FinancialData;
import com.example.be.domain.dart.DartModels.Quarter;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

final class DartReportFixtures {
    static final Company SAMSUNG = new Company("00126380", "삼성전자", "005930");
    static final Company HYNIX = new Company("00164779", "SK하이닉스", "000660");
    static CollectionTopicSnapshot topic(long id, Company company) {
        return new CollectionTopicSnapshot(id, "당시 주제", "반도체", List.of("HBM"), List.of(), List.of(), 100, 1440, company);
    }
    static NewsReport report(ReportScope scope, Company... companies) {
        var topics = IntStream.range(0, companies.length).mapToObj(i -> topic(i + 1L, companies[i])).toList();
        return NewsReport.builder().id(17L).reportScope(scope).reportStatus(ReportStatus.GENERATED)
                .title("공시 첨부 검증").markdownBody("보고서").modelName("test").generatedAt(LocalDateTime.of(2026, 10, 14, 10, 0))
                .collectionContexts(List.of(new ReportCollectionContext(42L, topics))).build();
    }
    static FinancialData data() {
        var quarters = IntStream.range(0, 12).mapToObj(i -> new Quarter(2023 + i / 4, i % 4 + 1,
                "10000000000000001", "1230000000000000", new BigDecimal("12.30"), i % 4 == 3, List.of())).toList();
        return new FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-14T10:00:00+09:00"), quarters, List.of(), List.of());
    }
}
