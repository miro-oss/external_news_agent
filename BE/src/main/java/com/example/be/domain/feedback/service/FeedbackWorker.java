package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.service.FeedbackAgentGateway;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedbackWorker {
    private final FeedbackStore store;
    private final FeedbackWorkService work;
    private final FeedbackJobTransactions transactions;
    private final FeedbackAgentGateway agent;
    private final ObjectMapper json;
    @Value("${news.feedback.enabled:true}")private boolean enabled=true;
    @Value("${news.scheduling.enabled:true}")private boolean schedulingEnabled=true;

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void completed(ReportCompleted event) { if(enabled)prepare(event.reportId()); }

    @Scheduled(fixedDelayString="${news.feedback.poll-interval-ms:15000}",scheduler="feedbackScheduler")
    public void poll() {
        if(!enabled || !schedulingEnabled)return;
        transactions.expire();
        store.reportsToPrepare().forEach(this::prepare);
        store.pending(LocalDateTime.now(ApiTimeZone.ZONE)).forEach(this::process);
    }
    private void prepare(long reportId) {
        try { work.prepareReport(reportId); }
        catch(RuntimeException error) { log.warn("개인 보고서 준비 등록 실패. reportId={}, errorType={}",reportId,error.getClass().getSimpleName()); }
    }
    void process(long id) {
        var claim=transactions.claim(id);if(claim.isEmpty())return;
        var job=claim.get();
        try {
            if(job.kind().equals("REVIEW")) {
                var feedback=store.byId(job.feedbackId()).orElseThrow();
                var baseline=store.policies(job.recipientId());
                var request=FeedbackAgentRequests.review(json,job,feedback,baseline);
                var result=agent.review(null,feedback.itemId(),request);
                FeedbackResultValidator.review(request,result);
                transactions.review(job,feedback,baseline,result);
            }else {
                var input=json.readValue(job.inputJson(),EvaluationInput.class);
                if(input.items().isEmpty()) { transactions.evaluated(job,json.valueToTree(Map.of("decisions",java.util.List.of())));return; }
                var request=FeedbackAgentRequests.evaluate(json,job,input);
                var result=agent.evaluate(null,job.topicId(),request);
                FeedbackResultValidator.evaluate(request,result);
                if(result.path("meta").path("mock").asBoolean()) { transactions.fail(job,false);return; }
                transactions.evaluated(job,result);
            }
        }catch(RuntimeException error) {
            // Retry only when a call was not sent or no quota was reserved. Unknown outcomes are terminal.
            boolean retry=error instanceof QuotaExceededException || (error instanceof AgentClientException a && a.isConnectTimeout());
            transactions.fail(job,retry);
            log.warn("피드백 작업 실패. jobId={}, attempt={}, errorType={}",id,job.attempts(),error.getClass().getSimpleName());
        }
    }
}
