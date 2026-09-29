package com.example.be.domain.feedback;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import static com.example.be.domain.feedback.FeedbackModels.*;

@Service
@RequiredArgsConstructor
public class FeedbackDeliveryService {
    private final FeedbackStore store;
    private final FeedbackSnapshotFactory snapshots;
    private final FeedbackWorkService work;
    private final ObjectMapper json;
    @Value("${news.feedback.enabled:true}") private boolean enabled=true;
    @Value("${news.scheduling.enabled:true}") private boolean schedulingEnabled=true;

    /** No provider calls: prepares a capability over exactly the remaining delivered source units. */
    @Transactional
    public DeliveryFeedback prepare(NewsReport report,long recipientId,List<Finding> visibleFindings) {
        return prepare(report,recipientId,visibleFindings,false);
    }

    @Transactional
    public DeliveryFeedback prepare(NewsReport report,long recipientId,List<Finding> visibleFindings,boolean originallyFiltered) {
        if(!enabled)return new DeliveryFeedback(Set.of(),null);
        Snapshot snapshot=snapshots.capture(report,visibleFindings);
        if(snapshot.items().isEmpty())return new DeliveryFeedback(Set.of(),null);
        if(schedulingEnabled)work.ensure(snapshot,recipientId);
        Set<Long> active=new HashSet<>();
        store.policies(recipientId).stream().filter(p->p.status().equals("ACTIVE")).forEach(p->active.add(p.id()));
        Set<Long> suppressed=new HashSet<>();
        for(Job job:store.evaluations(report.getId(),recipientId)) {
            var input=json.readValue(job.inputJson(),FeedbackWorkService.EvaluationInput.class);
            if(input.items().isEmpty() || input.policies().stream().noneMatch(p->active.contains(p.id())))continue;
            if(job.status().equals("PENDING") || job.status().equals("PROCESSING")) {
                if(schedulingEnabled)throw FeedbackErrors.conflict();
                continue;
            }
            if(!job.status().equals("COMPLETED") || job.resultJson()==null)continue;
            for(var decision:json.readTree(job.resultJson()).path("decisions")) {
                if(!"SUPPRESS".equals(decision.path("status").asString()))continue;
                Set<Long> required=new HashSet<>(); decision.path("policyIds").forEach(id->required.add(id.asLong()));
                if(required.isEmpty() || !active.containsAll(required))continue;
                long articleId=decision.path("articleId").asLong();
                input.items().stream().filter(i->i.articles().getFirst().id()==articleId)
                        .filter(i->snapshot.items().stream().anyMatch(current->current.itemId()==i.itemId()
                                && current.articles().getFirst().equals(i.articles().getFirst())
                                && Objects.equals(current.analysisInputHash(),i.analysisInputHash())))
                        .forEach(i->suppressed.add(i.itemId()));
            }
        }
        var delivered=snapshot.items().stream().filter(i->!suppressed.contains(i.itemId())).toList();
        // Even an empty delivery keeps access to the user's policy revocation controls.
        String token=FeedbackTokens.create();
        var kept=visibleFindings.stream().filter(f->!suppressed.contains(f.getId())).toList();
        var finalSnapshot=FeedbackSnapshotFactory.delivered(report,new ReportFindings.Visible(kept,originallyFiltered || !suppressed.isEmpty()),snapshot,delivered);
        store.capability(FeedbackTokens.hash(token),recipientId,finalSnapshot,LocalDateTime.now(ApiTimeZone.ZONE));
        return new DeliveryFeedback(Set.copyOf(suppressed),token);
    }
}
