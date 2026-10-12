package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;

@Service
@RequiredArgsConstructor
public class CompanyInterestService {
    private final CompanyInterestStore store;

    @Transactional(readOnly = true)
    public Criteria list() {
        return new Criteria(store.all().stream().map(this::response).toList());
    }

    @Transactional
    public Criterion update(long id, Update request) {
        if (id < 1 || request == null || request.version() == null || request.version() < 1
                || request.instruction() == null || request.instruction().strip().isEmpty()
                || request.instruction().strip().length() > 500
                || request.topicId() != null && request.topicId() < 1) throw FeedbackErrors.bad();
        Status status;
        try { status = Status.valueOf(request.status()); }
        catch (RuntimeException invalid) { throw FeedbackErrors.bad(); }
        if (status == Status.ACTIVE && request.topicId() == null) throw FeedbackErrors.bad();
        var current = store.byId(id, true).orElseThrow(FeedbackErrors::missing);
        if (current.version() != request.version()) throw FeedbackErrors.conflict();
        if (request.topicId() != null && topics(current.input()).stream().noneMatch(t -> t.id() == request.topicId()))
            throw FeedbackErrors.bad();
        String instruction = request.instruction().strip();
        if (current.instruction().equals(instruction) && Objects.equals(current.topicId(), request.topicId())
                && current.status() == status) return response(current);
        if (status == Status.ACTIVE) {
            // Different criteria for the same topic serialize their limit check and activation together.
            if (!store.lockTopic(request.topicId())) throw FeedbackErrors.bad();
            if (store.activeCount(request.topicId(), id) >= 20) throw FeedbackErrors.conflict();
        }
        if (!store.update(id, request.version(), instruction, request.topicId(), status, LocalDateTime.now(ApiTimeZone.ZONE)))
            throw FeedbackErrors.conflict();
        return response(store.byId(id, false).orElseThrow(FeedbackErrors::missing));
    }

    private Criterion response(StoredCriterion criterion) {
        var event = criterion.input() == null ? null : criterion.input().event();
        return new Criterion(criterion.id(), criterion.feedbackId(), criterion.sourceReportId(),
                event == null ? "" : event.title(), criterion.comment(), criterion.diagnosis(), topics(criterion.input()),
                criterion.topicId(), criterion.instruction(), criterion.reason(), criterion.status(), criterion.version(),
                offset(criterion.createdAt()), offset(criterion.updatedAt()), offset(criterion.activatedAt()),
                store.lastApplication(criterion.id(), criterion.version()).orElse(null));
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }
}
