package com.example.be.domain.feedback.service;

import com.example.be.domain.feedback.util.FeedbackTokens;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackValidationTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void tokensAreUnpredictableUrlSafeAndOnlyDigestsAreStored() {
        String a=FeedbackTokens.create(),b=FeedbackTokens.create();
        assertTrue(FeedbackTokens.valid(a));assertNotEquals(a,b);assertEquals(64,FeedbackTokens.hash(a).length());
        assertFalse(FeedbackTokens.valid(a+"x"));assertFalse(FeedbackTokens.valid("../../secret"));
    }
    @Test void exactCitationsAndPolicyScopeAreRequiredBeforeSuppression() {
        var request=json.readTree("""
                {"articles":[{"id":1,"content":"회사는 주가 전망을 발표했다."}],"policies":[{"id":9,"instruction":"주가 전망 제외"}]}
                """);
        var valid=json.readTree("""
                {"decisions":[{"articleId":1,"status":"SUPPRESS","policyIds":[9],"evidence":[{"articleId":1,"quote":"주가 전망"}],"reason":"개인 기준"}],"meta":{"truncated":false}}
                """);
        assertDoesNotThrow(()->FeedbackResultValidator.evaluate(request,valid));
        assertThrows(IllegalArgumentException.class,()->FeedbackResultValidator.evaluate(request,json.readTree(valid.toString().replace("주가 전망","HBM 생산"))));
        assertThrows(IllegalArgumentException.class,()->FeedbackResultValidator.evaluate(request,json.readTree(valid.toString().replace("[9]","[99]"))));
        assertThrows(IllegalArgumentException.class,()->FeedbackResultValidator.evaluate(request,json.readTree(valid.toString().replace("\"articleId\":1","\"articleId\":2"))));
    }
    @Test void factualFeedbackNeverCreatesPersonalPolicyAndTruncatedResultsFailClosed() {
        var request=json.readTree("""
                {"articles":[{"id":1,"content":"본문 근거"}],"feedback":{"category":"SUMMARY_ERROR","allowPersonalization":false}}
                """);
        var result=json.readTree("""
                {"verdict":"CONFIRMED_ERROR","diagnosis":"요약이 원문과 다릅니다.","evidence":[{"articleId":1,"quote":"본문 근거"}],"proposedPolicy":{"instruction":"전체 제외","reason":"제보"},"meta":{"truncated":false}}
                """);
        assertThrows(IllegalArgumentException.class,()->FeedbackResultValidator.review(request,result));
        assertThrows(IllegalArgumentException.class,()->FeedbackResultValidator.review(request,json.readTree(result.toString().replace("false","true"))));
    }
    @Test void policiesApplyOnlyToLaterRunsAndStaleScopeFingerprintChanges() {
        var t=LocalDateTime.of(2026,9,29,10,0);
        var policy=new Policy(1,4,2,"주제","주가 제외",1,"ACTIVE",t);
        var old=item(t.minusSeconds(1));var next=item(t.plusSeconds(1));
        assertTrue(FeedbackWorkService.eligible(List.of(policy),old,t.plusHours(1)).isEmpty());
        assertEquals(List.of(policy),FeedbackWorkService.eligible(List.of(policy),next,t.plusHours(1)));
        assertTrue(FeedbackWorkService.eligible(List.of(policy),next,t.minusSeconds(1)).isEmpty());
        assertNotEquals(FeedbackJobTransactions.fingerprint(List.of(policy),2),FeedbackJobTransactions.fingerprint(List.of(new Policy(1,4,2,"주제","주가 제외",2,"REVOKED",t)),2));
    }
    @Test void deliveredSnapshotDoesNotRetainRemovedCombinedEventOrItsSuppressedSource() {
        var now=LocalDateTime.now();var topic=new Topic(2,"HBM",List.of(),List.of());
        var first=new Article(10,"유효한 HBM 기사","HBM 생산 확대","https://example.test/10");
        var removed=new Article(20,"제외한 주가 기사","주가 전망","https://example.test/20");
        var kept=new Item(11,topic,new Issue(30,"결합 이벤트","HBM 생산과 주가 전망"),List.of(first,removed),"hash","v1","model",now,"v1","model","HBM 생산 확대");
        var dropped=new Item(12,topic,new Issue(30,"결합 이벤트","HBM 생산과 주가 전망"),List.of(removed,first),"hash2","v1","model",now,"v1","model","주가 전망");
        var report=com.example.be.domain.reports.entity.NewsReport.builder().id(1L).topicId(2L)
                .structuredContent(new com.example.be.domain.reports.entity.ReportContent(List.of("HBM 생산과 주가 전망"),
                        List.of(new com.example.be.domain.reports.entity.ReportContent.ImportantEvent("결합 이벤트","HBM 생산과 주가 전망","",List.of(11L,12L))),List.of(),List.of())).build();
        var visible=new com.example.be.domain.reports.service.ReportFindings.Visible(List.of(com.example.be.domain.analysis.entity.Finding.builder().id(11L).build()),true);
        var rebound=FeedbackSnapshotFactory.delivered(report,visible,new Snapshot(1,"보고서",now,List.of(kept,dropped)),List.of(kept));
        assertEquals("HBM 생산 확대",rebound.items().getFirst().issue().summary());
        assertEquals(List.of(first),rebound.items().getFirst().articles());
        assertEquals(List.of(2L),rebound.topicIds());
    }
    private Item item(LocalDateTime start) {return new Item(1,new Topic(2,"주제",List.of(),List.of()),new Issue(3,"기사","분석"),List.of(new Article(4,"기사","본문","https://example.com")),"hash","v1","model",start,"report-v1","report-model");}
}
