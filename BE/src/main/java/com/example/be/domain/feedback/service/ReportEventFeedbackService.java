package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;
import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Service
@RequiredArgsConstructor
public class ReportEventFeedbackService {
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final ReportEventFeedbackProjection projection;
    private final FeedbackStore store;
    private final ObjectMapper json;

    @Transactional(readOnly=true)
    public EventFeedbackContext context(long reportId) {
        var report=report(reportId,false);
        var reviews=projection.reviews(reportId);
        var items=projection.events(report,ReportFindings.loadVisible(report,findings,relevance),reviews);
        var events=java.util.stream.IntStream.range(0,items.size()).mapToObj(index->{
            var event=items.get(index).event();
            return new PublicEvent(event.key(),index,event.title(),event.summary(),event.significance(),event.sourceFindingIds());
        }).toList();
        return new EventFeedbackContext(reportId,events,reviews.stream().map(ReportEventFeedbackService::response).toList());
    }

    @Transactional
    public EventFeedback submit(long reportId,EventSubmitRequest request) {
        if(request==null || request.eventKey()==null || !request.eventKey().matches("[a-f0-9]{64}")
                || request.comment()==null || !text(request.comment().strip(),2000) || !text(request.idempotencyKey(),100))throw FeedbackErrors.bad();
        Category category;
        try { category=Category.valueOf(request.category()); }catch(RuntimeException invalid){throw FeedbackErrors.bad();}
        var report=report(reportId,true);
        String comment=request.comment().strip();
        String hash=FeedbackTokens.hash(json.writeValueAsString(List.of(request.eventKey(),category.name(),comment)));
        // A valid retry returns its immutable saved result even if the displayed report has since changed.
        var byRequest=store.eventByRequest(reportId,request.idempotencyKey());
        if(byRequest.isPresent())return existing(byRequest.get(),hash);
        var byEvent=store.eventByKey(reportId,request.eventKey());
        if(byEvent.isPresent()) {
            var response=existing(byEvent.get(),hash);
            store.reserveEventRequest(reportId,request.idempotencyKey(),byEvent.get().id());
            return response;
        }
        var item=capture(report).stream().filter(i->i.event().key().equals(request.eventKey())).findFirst().orElseThrow(FeedbackErrors::conflict);
        var now=LocalDateTime.now(ApiTimeZone.ZONE);
        long id=store.submitEvent(reportId,item,category,comment,request.idempotencyKey(),hash,now);
        store.reserveEventRequest(reportId,request.idempotencyKey(),id);
        store.enqueue("review:"+id,"REVIEW",id,null,reportId,null,item,now);
        return response(store.byId(id).orElseThrow());
    }

    private List<Item> capture(NewsReport report) {
        return projection.events(report,ReportFindings.loadVisible(report,findings,relevance),projection.reviews(report.getId()));
    }
    private NewsReport report(long id,boolean lock) {
        if(id<1)throw FeedbackErrors.bad();
        return (lock?reports.findByIdForUpdate(id):reports.findById(id)).filter(r->r.getDeletedAt()==null
                        && r.getReportStatus()!=com.example.be.domain.reports.entity.ReportStatus.PENDING)
                .orElseThrow(()->new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
    }
    private static EventFeedback existing(Feedback feedback,String hash) {
        if(!feedback.requestHash().equals(hash))throw FeedbackErrors.conflict();
        return response(feedback);
    }
    static EventFeedback response(Feedback f) {
        return new EventFeedback(f.id(),f.eventKey(),f.category().name(),f.comment(),f.status().name(),f.verdict(),f.diagnosis(),
                f.createdAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }
    private static boolean text(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
}
