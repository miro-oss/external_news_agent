package com.example.be.domain.feedback.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

final class FeedbackAgentRequests {
    private FeedbackAgentRequests() { }
    static JsonNode review(ObjectMapper json,Job job,Feedback feedback,List<Policy> policies) {
        var item=feedback.input();
        if(item.event()!=null) {
            var event=item.event();
            return json.valueToTree(Map.of("idempotencyKey",key(job),"plan","FREE",
                    "event",Map.of("key",event.key(),"title",event.title(),"summary",event.summary(),
                            "significance",event.significance()==null?"":event.significance(),"sourceFindingIds",event.sourceFindingIds()),
                    "topics",event.topics().stream().map(FeedbackAgentRequests::fullTopic).toList(),
                    "articles",item.articles().stream().map(FeedbackAgentRequests::fullArticle).toList(),
                    "feedback",Map.of("category",feedback.category().name(),"comment",feedback.comment(),"allowPersonalization",false),
                    "activePolicies",List.of()));
        }
        return json.valueToTree(Map.of("idempotencyKey",key(job),"plan","FREE","topic",topic(item.topic()),
                "issue",Map.of("id",item.issue().id(),"title",clip(item.issue().title(),1000),"summary",clip(item.issue().summary(),5000)),
                "articles",item.articles().stream().limit(10).map(FeedbackAgentRequests::article).toList(),
                "feedback",Map.of("category",feedback.category().name(),"comment",feedback.comment(),"allowPersonalization",feedback.allowPersonalization()),
                "activePolicies",policies.stream().filter(p->p.topicId()==item.topic().id() && p.status().equals("ACTIVE")).limit(20).map(FeedbackAgentRequests::policy).toList()));
    }
    /** Event evidence is never silently clipped to fit the provider's bounded contract. */
    static String eventUnavailableReason(ObjectMapper json,Job job,Feedback feedback) {
        var item=feedback.input();var event=item.event();
        if(event==null)return null;
        if(event.unavailableReason()!=null)return event.unavailableReason();
        if(event.sources().stream().anyMatch(s->!s.contextComplete() || s.collectionTopics().isEmpty()))
            return "일부 근거의 당시 수집 주제 조건이 없어 검토를 보류했습니다.";
        if(item.articles().isEmpty() || item.articles().size()>10 || event.topics().isEmpty()
                || !text(event.title(),1000) || !text(event.summary(),5000)
                || (event.significance()!=null && event.significance().length()>5000))return oversized();
        for(var a:item.articles())if(a.id()<1 || !text(a.title(),1000) || !text(a.content(),10000)
                || (a.url()!=null && a.url().length()>2000))return oversized();
        for(var topic:event.topics())if(topic.id()<1 || !text(topic.name(),200)
                || topic.keywords().size()>100 || topic.negativeKeywords().size()>100
                || topic.keywords().stream().anyMatch(k->!text(k,100)) || topic.negativeKeywords().stream().anyMatch(k->!text(k,100)))return oversized();
        if(review(json,job,feedback,List.of()).toString().length()>150000)return oversized();
        return null;
    }
    private static String oversized() { return "전체 이벤트 근거가 검토 입력 한도에 맞지 않아 일부 내용을 잘라 판단하지 않고 검토를 보류했습니다."; }
    private static boolean text(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
    private static Map<String,Object> fullTopic(Topic t) {
        return Map.of("id",t.id(),"name",t.name(),"keywords",t.keywords(),"negativeKeywords",t.negativeKeywords());
    }
    private static Map<String,Object> fullArticle(Article a) {
        return Map.of("id",a.id(),"title",a.title(),"url",a.url()==null?"":a.url(),"content",a.content());
    }
    static JsonNode evaluate(ObjectMapper json,Job job,EvaluationInput input) {
        return json.valueToTree(Map.of("idempotencyKey",key(job),"plan","FREE","topic",topic(input.topic()),
                "articles",input.items().stream().map(i->i.articles().getFirst()).distinct().map(FeedbackAgentRequests::article).toList(),
                "policies",input.policies().stream().map(FeedbackAgentRequests::policy).toList()));
    }
    private static Map<String,Object> topic(Topic topic) {
        return Map.of("id",topic.id(),"name",clip(topic.name(),200),"keywords",topic.keywords().stream().limit(100).map(k->clip(k,100)).toList(),
                "negativeKeywords",topic.negativeKeywords().stream().limit(100).map(k->clip(k,100)).toList());
    }
    private static Map<String,Object> article(Article a) { return Map.of("id",a.id(),"title",clip(a.title(),1000),"url",clip(a.url(),2000),"content",clip(a.content(),10000)); }
    private static Map<String,Object> policy(Policy p) { return Map.of("id",p.id(),"instruction",p.instruction()); }
    static String key(Job job) { return "feedback:"+job.id()+":attempt:"+job.attempts(); }
    static String clip(String text,int max) { return text==null?"":text.substring(0,Math.min(max,text.length())); }
}
