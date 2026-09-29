package com.example.be.domain.feedback.service;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;
import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Service
@RequiredArgsConstructor
public class FeedbackService {
    private final FeedbackStore store;
    private final ObjectMapper json;

    @Transactional(readOnly=true)
    public Context context(TokenRequest request) {
        var capability=authorize(request==null?null:request.token());
        var snapshot=capability.snapshot();
        Set<Long> topics=new HashSet<>(snapshot.topicIds());
        return new Context(snapshot.reportId(),snapshot.reportTitle(),capability.expiresAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime(),
                snapshot.items().stream().map(i->new PublicItem(i.itemId(),i.topic().id(),i.topic().name(),i.issue().title(),i.issue().summary(),
                        i.articles().stream().map(a->new Source(a.id(),a.title(),a.url())).toList())).toList(),
                store.feedback(capability.recipientId(),snapshot.reportId()).stream().map(FeedbackService::publicFeedback).toList(),
                store.policies(capability.recipientId()).stream().filter(p->topics.contains(p.topicId())).map(FeedbackService::publicPolicy).toList());
    }

    @Transactional
    public PublicFeedback submit(SubmitRequest request) {
        if(request==null || request.itemId()==null || request.itemId()<1 || !text(request.comment(),2000)
                || !text(request.idempotencyKey(),100) || request.allowPersonalization()==null)throw FeedbackErrors.bad();
        Category category;
        try { category=Category.valueOf(request.category()); }catch(RuntimeException bad){throw FeedbackErrors.bad();}
        if(request.allowPersonalization() && category!=Category.PREFERENCE)throw FeedbackErrors.bad();
        var capability=authorize(request.token());
        var item=capability.snapshot().items().stream().filter(i->Objects.equals(i.itemId(),request.itemId())).findFirst().orElseThrow(FeedbackErrors::missing);
        String comment=request.comment().strip();
        String hash=FeedbackTokens.hash(json.writeValueAsString(List.of(item.itemId(),category.name(),comment,request.allowPersonalization())));
        store.lockRecipient(capability.recipientId());
        var existing=store.byRequest(capability.id(),request.idempotencyKey());
        if(existing.isPresent()) {
            if(!existing.get().requestHash().equals(hash))throw FeedbackErrors.conflict();
            return publicFeedback(existing.get());
        }
        var priorItem=store.byItem(capability.recipientId(),capability.snapshot().reportId(),item.itemId());
        if(priorItem.isPresent()) {
            if(!priorItem.get().requestHash().equals(hash))throw FeedbackErrors.conflict();
            return publicFeedback(priorItem.get());
        }
        var now=LocalDateTime.now(ApiTimeZone.ZONE);
        long id=store.submit(capability,item,category,comment,request.allowPersonalization(),request.idempotencyKey(),hash,now);
        store.enqueue("review:"+id,"REVIEW",id,capability.recipientId(),capability.snapshot().reportId(),item.topic().id(),item,now);
        return publicFeedback(store.byId(id).orElseThrow());
    }

    @Transactional
    public PublicPolicy revoke(long id,RevokeRequest request) {
        if(id<1 || request==null || request.version()==null || request.version()<1)throw FeedbackErrors.bad();
        var capability=authorize(request.token());
        store.lockRecipient(capability.recipientId());
        var policy=store.policies(capability.recipientId()).stream().filter(p->p.id()==id
                && capability.snapshot().topicIds().contains(p.topicId())).findFirst().orElseThrow(FeedbackErrors::missing);
        if(policy.status().equals("REVOKED")) {
            if(request.version()!=policy.version() && request.version()!=policy.version()-1)throw FeedbackErrors.conflict();
            return publicPolicy(policy);
        }
        store.revoke(id,capability.recipientId(),request.version(),LocalDateTime.now(ApiTimeZone.ZONE));
        return publicPolicy(store.policies(capability.recipientId()).stream().filter(p->p.id()==id).findFirst().orElseThrow());
    }

    @Transactional(readOnly=true)
    public Map<String,Object> export(long afterId,int size) {
        if(afterId<0 || size<1 || size>100)throw FeedbackErrors.bad();
        var rows=store.export(afterId,size+1);boolean hasNext=rows.size()>size;
        var selected=rows.stream().limit(size).toList();
        var cases=new ArrayList<Map<String,Object>>();
        for(var f:selected) {
            if(f.input().event()!=null) {
                for(var source:f.input().event().sources()) {
                    if(!source.contextComplete())continue;
                    for(var scope:source.collectionTopics()) {
                    var a=source.article();
                    if(a==null || a.url()==null || a.url().isBlank())continue;
                    var topic=new LinkedHashMap<String,Object>();topic.put("id",scope.topicId());topic.put("name",scope.topicName());
                    topic.put("queryText",scope.queryText());topic.put("requiredKeywords",scope.requiredKeywords());
                    topic.put("optionalKeywords",scope.optionalKeywords());topic.put("excludedKeywords",scope.excludedKeywords());
                    String caseId="feedback-"+f.id()+"-finding-"+source.findingId()+"-topic-"+scope.topicId();
                    var value=exportCase(f,a,topic,caseId);
                    value.put("sourceIssueId",null);
                    value.put("sourceReportId",f.reportId());value.put("sourceEventKey",f.eventKey());value.put("sourceFindingId",source.findingId());
                    if(source.runId()!=null)value.put("sourceRunId",source.runId());
                    cases.add(value);
                    }
                }
                continue;
            }
            for(var a:f.input().articles()) {
            var topic=new LinkedHashMap<String,Object>();topic.put("id",f.input().topic().id());topic.put("name",f.input().topic().name());
            topic.put("queryText",null);topic.put("requiredKeywords",List.of());topic.put("optionalKeywords",f.input().topic().keywords());topic.put("excludedKeywords",f.input().topic().negativeKeywords());
            var value=exportCase(f,a,topic,"feedback-"+f.id()+"-article-"+a.id());
            value.put("sourceIssueId",f.input().issue().id());cases.add(value);
            }
        }
        return Map.of("schemaVersion",1,"cases",cases,"nextAfterId",selected.isEmpty()?afterId:selected.getLast().id(),"hasNext",hasNext);
    }
    private Map<String,Object> exportCase(Feedback f,Article a,Map<String,Object> topic,String caseId) {
        var article=new LinkedHashMap<String,Object>();article.put("articleId",a.id());article.put("title",a.title());article.put("summary",null);article.put("bodyText",FeedbackAgentRequests.clip(a.content(),5000));
        var value=new LinkedHashMap<String,Object>();value.put("caseId",caseId);
        value.put("groupId","article-"+FeedbackTokens.hash(a.url()));value.put("sourceFeedbackId",f.id());
        value.put("split",null);value.put("labelSource","USER_FEEDBACK");value.put("goldLabel",null);value.put("humanExplanation","");
        value.put("request",Map.of("idempotencyKey",caseId,"plan","FREE","topic",topic,"articles",List.of(article)));
        return value;
    }
    Capability authorize(String token) {
        if(token==null || token.isBlank())throw FeedbackErrors.bad();
        if(!FeedbackTokens.valid(token))throw FeedbackErrors.missing();
        return store.capability(FeedbackTokens.hash(token),LocalDateTime.now(ApiTimeZone.ZONE)).orElseThrow(FeedbackErrors::missing);
    }
    static PublicFeedback publicFeedback(Feedback f) { return new PublicFeedback(f.id(),f.itemId(),f.category().name(),f.comment(),f.status().name(),f.verdict(),f.diagnosis(),f.createdAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime()); }
    static PublicPolicy publicPolicy(Policy p) { return new PublicPolicy(p.id(),p.topicId(),p.topicName(),p.instruction(),p.version(),p.status(),p.createdAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime()); }
    private static boolean text(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
}
