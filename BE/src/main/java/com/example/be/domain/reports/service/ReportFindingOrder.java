package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;

import java.util.Comparator;
import java.util.List;

/** 보고서 본문과 상세 finding 카드가 공유하는 우선순위 정렬이다. */
public final class ReportFindingOrder {

    private static final Comparator<Finding> PRIORITY = Comparator
            .comparing((Finding finding) -> finding.getSensitivity().getScore()).reversed()
            .thenComparing(Finding::getRelevance)
            .thenComparing(Finding::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private ReportFindingOrder() {
    }

    public static List<Finding> sort(List<Finding> findings) {
        return findings.stream().sorted(PRIORITY).toList();
    }
}
