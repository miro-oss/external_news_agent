package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.*;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TopicWeeklyReportPersistenceService {
    private final DailyReportJdbcRepository creationLock;
    private final WeeklyReportJdbcRepository weekly;
    private final NewsReportRepository reports;
    private final TopicRepository topics;
    private final TopicWeeklyReportSelector selector;

    @Transactional
    public Reservation reserve(Long topicId, LocalDate monday, LocalDateTime now) {
        if (topicId == null || topicId <= 0 || monday == null) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "topicId와 weekStartDate를 올바르게 입력해 주세요.");
        }
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY || !monday.plusDays(6).isBefore(now.toLocalDate())) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "주간 보고서는 종료된 월요일~일요일 기간만 집계할 수 있습니다.");
        }
        var topic = topics.findById(topicId).orElseThrow(() -> new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));
        creationLock.lockCreation();
        NewsReport existing = reports.findByReportScopeAndReportDateAndTopicId(ReportScope.WEEKLY, monday, topicId).orElse(null);
        if (existing != null) {
            existing.restore();
            return new Reservation(existing.getId(), false, existing.getReportStatus() != ReportStatus.PENDING,
                    existing.getWeeklyInput());
        }
        if (weekly.hasUnfinishedTopicInputs(topicId, monday)) throw new ReportException(ReportErrorCode.TOPIC_INPUT_PENDING);
        var selections = monday.datesUntil(monday.plusWeeks(1))
                .map(date -> selector.select(topicId, topic.getName(), date))
                .filter(selection -> !selection.findings().isEmpty()).toList();
        if (selections.isEmpty()) throw new ReportException(ReportErrorCode.TOPIC_INPUT_EMPTY);
        var sourceDates = selections.stream().map(selection -> selection.source().reportDate()).toList();
        List<LocalDate> missingDates = monday.datesUntil(monday.plusWeeks(1)).filter(date -> !sourceDates.contains(date)).toList();
        var input = new WeeklyReportInput(monday, monday.plusDays(6), selections.stream()
                .map(TopicWeeklyReportSelector.Selection::source).toList(), missingDates, topicId, topic.getName());
        var runs = selections.stream().flatMap(selection -> selection.findings().stream()).map(Finding::getRun)
                .distinct().sorted(Comparator.comparing(run -> run.getStartedAt())).toList();
        var contexts = runs.stream().map(ReportCollectionContext::from)
                .map(context -> new ReportCollectionContext(context.runId(), context.topics().stream()
                        .filter(snapshot -> snapshot.topicId().equals(topicId)).toList())).toList();
        NewsReport report = reports.saveAndFlush(NewsReport.builder().reportScope(ReportScope.WEEKLY)
                .reportDate(monday).reportEndDate(monday.plusDays(6)).topicId(topicId).topicName(topic.getName())
                .sourceReportCount(0L).sourceRunIds(runs.stream().map(run -> run.getId()).toList())
                .collectionContexts(contexts).missingReportDates(missingDates).weeklyInput(input)
                .reflectedFindingIds(input.sourceFindingIds()).coverageRecorded(true).comparisonInputUsable(false)
                .title(input.title()).markdownBody("보고서 생성이 진행 중입니다.").modelName("pending-report-v1")
                .reportStatus(ReportStatus.PENDING).generatedAt(now).build());
        return new Reservation(report.getId(), true, false, input);
    }

    public record Reservation(Long reportId, boolean owner, boolean ready, WeeklyReportInput input) { }
}
