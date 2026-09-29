package com.example.be.domain.feedback;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Validate exact frozen citations and permitted IDs again at the persistence boundary. */
public final class FeedbackResultValidator {
    private FeedbackResultValidator() { }
    public static void review(JsonNode request,JsonNode result) {
        common(result);
        if(!Set.of("PREFERENCE","CONFIRMED_ERROR","NOT_CONFIRMED","INSUFFICIENT_EVIDENCE").contains(result.path("verdict").asString())
                || !text(result.path("diagnosis"),2000))throw invalid();
        evidence(request,result.path("evidence"),10);
        if(!"INSUFFICIENT_EVIDENCE".equals(result.path("verdict").asString()) && result.path("evidence").isEmpty())throw invalid();
        var policy=result.path("proposedPolicy");
        if(!policy.isMissingNode() && !policy.isNull()) {
            if(!"PREFERENCE".equals(request.path("feedback").path("category").asString())
                    || !request.path("feedback").path("allowPersonalization").asBoolean()
                    || !"PREFERENCE".equals(result.path("verdict").asString())
                    || !text(policy.path("instruction"),500) || !text(policy.path("reason"),500))throw invalid();
        }
    }
    public static void evaluate(JsonNode request,JsonNode result) {
        common(result);
        Set<Long> articles=new HashSet<>();request.path("articles").forEach(a->articles.add(a.path("id").asLong()));
        Set<Long> policies=new HashSet<>();request.path("policies").forEach(p->policies.add(p.path("id").asLong()));
        Set<Long> seen=new HashSet<>();
        if(!result.path("decisions").isArray() || result.path("decisions").size()!=articles.size())throw invalid();
        for(var d:result.path("decisions")) {
            long id=d.path("articleId").asLong();
            if(!articles.contains(id) || !seen.add(id) || !Set.of("KEEP","SUPPRESS","UNCERTAIN").contains(d.path("status").asString())
                    || !d.path("policyIds").isArray() || !text(d.path("reason"),500))throw invalid();
            Set<Long> references=new HashSet<>();
            for(var p:d.path("policyIds"))if(!policies.contains(p.asLong()) || !references.add(p.asLong()))throw invalid();
            evidence(request,d.path("evidence"),3);
            if(java.util.stream.StreamSupport.stream(d.path("evidence").spliterator(),false).anyMatch(e->e.path("articleId").asLong()!=id))throw invalid();
            if("SUPPRESS".equals(d.path("status").asString()) && (references.isEmpty() || d.path("evidence").isEmpty()
                    || java.util.stream.StreamSupport.stream(d.path("evidence").spliterator(),false).anyMatch(e->e.path("articleId").asLong()!=id)))throw invalid();
        }
    }
    private static void common(JsonNode result) {
        if(result==null || !result.isObject() || !result.path("meta").isObject() || result.path("meta").path("truncated").asBoolean(true))throw invalid();
    }
    private static void evidence(JsonNode request,JsonNode evidence,int max) {
        if(!evidence.isArray() || evidence.size()>max)throw invalid();
        Map<Long,JsonNode> sources=new HashMap<>();request.path("articles").forEach(a->sources.put(a.path("id").asLong(),a));
        for(var e:evidence) {
            var source=sources.get(e.path("articleId").asLong());
            if(source==null || !text(e.path("quote"),300) || (!source.path("content").asString("").contains(e.path("quote").asString())
                    && !source.path("title").asString("").contains(e.path("quote").asString())))throw invalid();
        }
    }
    private static boolean text(JsonNode node,int max) { return node.isString() && !node.asString().isBlank() && node.asString().length()<=max; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("피드백 재검토 응답이 저장된 근거·범위와 일치하지 않습니다."); }
}
