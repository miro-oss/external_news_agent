package com.example.be.domain.feedback;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static com.example.be.domain.feedback.FeedbackModels.*;

final class FeedbackAgentRequests {
    private FeedbackAgentRequests() { }
    static JsonNode review(ObjectMapper json,Job job,Feedback feedback,List<Policy> policies) {
        var item=feedback.input();
        return json.valueToTree(Map.of("idempotencyKey",key(job),"plan","FREE","topic",topic(item.topic()),
                "issue",Map.of("id",item.issue().id(),"title",clip(item.issue().title(),1000),"summary",clip(item.issue().summary(),5000)),
                "articles",item.articles().stream().limit(10).map(FeedbackAgentRequests::article).toList(),
                "feedback",Map.of("category",feedback.category().name(),"comment",feedback.comment(),"allowPersonalization",feedback.allowPersonalization()),
                "activePolicies",policies.stream().filter(p->p.topicId()==item.topic().id() && p.status().equals("ACTIVE")).limit(20).map(FeedbackAgentRequests::policy).toList()));
    }
    static JsonNode evaluate(ObjectMapper json,Job job,FeedbackWorkService.EvaluationInput input) {
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
