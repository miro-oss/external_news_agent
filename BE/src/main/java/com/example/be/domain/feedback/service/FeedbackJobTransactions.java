package com.example.be.domain.feedback.service;

import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Service
@RequiredArgsConstructor
public class FeedbackJobTransactions {
    private final FeedbackStore store;
    @Transactional
    public Optional<Job> claim(long id) { return store.claim(id,UUID.randomUUID().toString(),LocalDateTime.now(ApiTimeZone.ZONE)); }
    @Transactional
    public void review(Job job,Feedback feedback,List<Policy> baseline,JsonNode result) {
        var now=LocalDateTime.now(ApiTimeZone.ZONE);
        if(feedback.recipientId()!=null)store.lockRecipient(feedback.recipientId());
        if(!store.finish(job,result,now))return;
        String diagnosis=result.path("diagnosis").asString();
        if(feedback.recipientId()==null) {
            if(feedback.category()==Category.PREFERENCE)diagnosis+="\n이 의견은 보고서 이벤트 검토로 저장되며 개인 전달 기준은 변경하지 않았습니다.";
            store.reviewed(feedback,result.path("verdict").asString(),diagnosis,result,now);
            return;
        }
        var current=store.policies(feedback.recipientId());
        // An in-flight reviewer cannot resurrect a just-revoked policy or overwrite a newer decision.
        boolean unchanged=fingerprint(current,feedback.input().topic().id()).equals(fingerprint(baseline,feedback.input().topic().id()));
        var candidate=result.path("proposedPolicy");
        if(feedback.category()==Category.PREFERENCE && feedback.allowPersonalization()
                && "PREFERENCE".equals(result.path("verdict").asString()) && candidate.isObject()) {
            if(!unchanged)diagnosis+="\n검토 중 개인 기준이 변경되어 이 제안은 적용하지 않았습니다.";
            else if(result.path("meta").path("mock").asBoolean())diagnosis+="\n모의 검토 결과이므로 개인 기준은 변경하지 않았습니다.";
            else if(!store.activate(feedback,candidate.path("instruction").asString().strip(),now))
                diagnosis+="\n이 주제의 활성 개인 기준이 20개여서 새 기준은 적용하지 않았습니다. 기존 기준을 철회한 뒤 다시 설정해 주세요.";
        }
        store.reviewed(feedback,result.path("verdict").asString(),diagnosis,result,now);
    }
    @Transactional
    public void evaluated(Job job,JsonNode result) { store.finish(job,result,LocalDateTime.now(ApiTimeZone.ZONE)); }
    @Transactional
    public void fail(Job job,boolean retry) { store.fail(job,retry,LocalDateTime.now(ApiTimeZone.ZONE)); }
    @Transactional
    public void expire() { store.expire(LocalDateTime.now(ApiTimeZone.ZONE)); }
    static Set<String> fingerprint(List<Policy> policies,long topicId) {
        Set<String> result=new HashSet<>();policies.stream().filter(p->p.topicId()==topicId && p.status().equals("ACTIVE"))
                .forEach(p->result.add(p.id()+":"+p.version()+":"+p.instruction()));return result;
    }
}
