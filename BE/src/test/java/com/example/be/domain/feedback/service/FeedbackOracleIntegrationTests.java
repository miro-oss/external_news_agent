package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.agent.service.FeedbackAgentGateway;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.reports.comparison.ReportComparisonWorker;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.domain.reports.entity.*;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"news.agent.enabled=false","news.notifications.automation-enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="news.integration.db",matches="true")
class FeedbackOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired FeedbackStore store;
    @Autowired FeedbackService service;
    @Autowired FeedbackWorkService work;
    @Autowired FeedbackJobTransactions transactions;
    @Autowired FeedbackWorker worker;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean FeedbackAgentGateway gateway;
    @MockitoBean ReportComparisonWorker comparisons;
    private final List<Long> recipients=new ArrayList<>(),reports=new ArrayList<>(),topics=new ArrayList<>();

    @AfterEach void clean() {
        for(long id:recipients){jdbc.update("DELETE FROM news_feedback_jobs WHERE recipient_id=?",id);jdbc.update("DELETE FROM news_feedback_policies WHERE recipient_id=?",id);jdbc.update("DELETE FROM news_feedback WHERE recipient_id=?",id);jdbc.update("DELETE FROM news_feedback_capabilities WHERE recipient_id=?",id);jdbc.update("DELETE FROM notification_recipients WHERE id=?",id);}
        for(long id:reports)jdbc.update("DELETE FROM news_reports WHERE id=?",id);
        for(long id:topics)jdbc.update("DELETE FROM news_topics WHERE id=?",id);
    }
    @Test void capabilityIsHashedFrozenExpiredAndDuplicateSubmissionAcrossLinksCallsOneJob()throws Exception {
        var f=fixture();String token=link(f);String second=link(f);
        assertFalse(jdbc.queryForObject("SELECT token_hash FROM news_feedback_capabilities WHERE recipient_id=? FETCH FIRST 1 ROW ONLY",String.class,f.recipient()).contains(token));
        assertEquals("당시 보고서",service.context(new TokenRequest(token)).reportTitle());
        var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{latch.await();return service.submit(request(token,"same"));});
            var b=pool.submit(()->{latch.await();return service.submit(request(second,"other"));});
            latch.countDown();assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_jobs WHERE recipient_id=?",Integer.class,f.recipient()));
        assertThrows(GeneralException.class,()->service.submit(new SubmitRequest(token,11L,"PREFERENCE","다른 기준",true,"same")));
        jdbc.update("UPDATE news_reports SET title='새 제목' WHERE id=?",f.report().getId());
        assertEquals("당시 보고서",service.context(new TokenRequest(token)).reportTitle());
        jdbc.update("UPDATE news_feedback_capabilities SET expires_at=? WHERE recipient_id=?",now().minusSeconds(1),f.recipient());
        var expired=assertThrows(GeneralException.class,()->service.context(new TokenRequest(token)));
        var invalid=assertThrows(GeneralException.class,()->service.context(new TokenRequest(FeedbackTokens.create())));
        assertEquals(expired.getCode(),invalid.getCode());
    }
    @Test void reviewCreatesScopedPolicyNextRunSuppressionCanBeRevokedThroughEmptyDelivery() {
        var f=fixture();String token=link(f);
        var feedback=service.submit(request(token,"preference"));
        when(gateway.review(isNull(),anyLong(),any())).thenReturn(json.readTree("""
                {"verdict":"PREFERENCE","diagnosis":"주가 전망을 개인 보고서에서 제외합니다.","evidence":[{"articleId":21,"quote":"주가 전망"}],"proposedPolicy":{"instruction":"주가 전망 중심의 기사는 제외한다.","reason":"사용자의 명시적 선호"},"meta":{"truncated":false,"mock":false}}
                """));
        worker.process(jobFor(feedback.id()));
        var policy=store.policies(f.recipient()).getFirst();assertEquals("ACTIVE",policy.status());
        assertTrue(service.export(0,100).get("cases") instanceof List<?> list && list.stream().noneMatch(v->v.toString().contains("feedback-"+feedback.id()+"-")));
        // The original run predates the preference and therefore has no model work.
        work.ensure(f.snapshot(),f.recipient());
        assertTrue(store.evaluations(f.report().getId(),f.recipient()).isEmpty());
        var next=next(f,policy.createdAt().plusSeconds(1));work.ensure(next.snapshot(),f.recipient());
        var evaluation=store.evaluations(next.report().getId(),f.recipient()).getFirst();
        when(gateway.evaluate(isNull(),anyLong(),any())).thenReturn(json.readTree("""
                {"decisions":[{"articleId":21,"status":"SUPPRESS","policyIds":[%d],"evidence":[{"articleId":21,"quote":"주가 전망"}],"reason":"명시한 개인 선호"}],"meta":{"truncated":false,"mock":false}}
                """.formatted(policy.id())));
        worker.process(evaluation.id());
        var factory=mock(FeedbackSnapshotFactory.class);when(factory.capture(eq(next.report()),anyList())).thenReturn(next.snapshot());
        var delivery=new FeedbackDeliveryService(store,factory,work,json);
        var filtered=delivery.prepare(next.report(),f.recipient(),List.of());
        assertEquals(Set.of(11L),filtered.suppressedFindingIds());assertNotNull(filtered.token());
        var context=service.context(new TokenRequest(filtered.token()));assertTrue(context.items().isEmpty());assertEquals(policy.id(),context.policies().getFirst().id());
        service.revoke(policy.id(),new RevokeRequest(filtered.token(),policy.version()));
        assertTrue(delivery.prepare(next.report(),f.recipient(),List.of()).suppressedFindingIds().isEmpty());
        verify(gateway,times(1)).review(any(),anyLong(),any());verify(gateway,times(1)).evaluate(any(),anyLong(),any());
    }
    @Test void singleClaimLeaseFencingAndStalePolicyBaselinePreventLateActivation()throws Exception {
        var f=fixture();var submitted=service.submit(request(link(f),"lease"));long id=jobFor(submitted.id());
        var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{latch.await();return transactions.claim(id);});var b=pool.submit(()->{latch.await();return transactions.claim(id);});
            latch.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS).isPresent(),b.get(10,TimeUnit.SECONDS).isPresent());
        }
        var claimed=jdbc.queryForObject("SELECT claim_key FROM news_feedback_jobs WHERE id=?",String.class,id);
        var job=new Job(id,"REVIEW",submitted.id(),f.recipient(),f.report().getId(),f.topic(),"{}",null,"PROCESSING",claimed,1);
        jdbc.update("UPDATE news_feedback_jobs SET started_at=? WHERE id=?",now().minusHours(1),id);transactions.expire();
        var response=json.readTree("""
                {"verdict":"PREFERENCE","diagnosis":"선호","proposedPolicy":{"instruction":"주가 제외","reason":"선호"},"meta":{"mock":false}}
                """);
        transactions.review(job,store.byId(submitted.id()).orElseThrow(),List.of(),response);
        assertEquals("FAILED",service.context(new TokenRequest(link(f))).feedback().getFirst().status());assertTrue(store.policies(f.recipient()).isEmpty());
    }
    @Test void requestedDeliveryAloneQueuesEvaluationAndPendingResponseDoesNotRollBackJob() {
        var f=fixture();var unused=fixture();
        var other=new Fixture(unused.recipient(),f.topic(),f.report(),f.snapshot());
        when(gateway.review(isNull(),anyLong(),any())).thenReturn(json.readTree("""
                {"verdict":"PREFERENCE","diagnosis":"주가 전망 제외 선호입니다.","evidence":[{"articleId":21,"quote":"주가 전망"}],"proposedPolicy":{"instruction":"주가 전망 중심의 기사는 제외한다.","reason":"사용자의 명시적 선호"},"meta":{"truncated":false,"mock":false}}
                """));
        for(var recipient:List.of(f,other)) {
            var feedback=service.submit(request(link(recipient),"preference"));
            worker.process(jobFor(feedback.id()));
        }
        var policy=store.policies(f.recipient()).getFirst();
        var latest=store.policies(other.recipient()).getFirst().createdAt();
        var next=next(f,latest.plusSeconds(1));
        var transaction=new TransactionTemplate(manager);

        transaction.executeWithoutResult(tx->events.publishEvent(new ReportCompleted(next.report().getId())));
        // Completion and scheduler recovery must not evaluate every policy owner's new report.
        new FeedbackWorker(store,transactions,gateway,json).poll();
        assertTrue(store.evaluations(next.report().getId(),f.recipient()).isEmpty());
        assertTrue(store.evaluations(next.report().getId(),other.recipient()).isEmpty());
        verify(gateway,never()).evaluate(any(),anyLong(),any());

        var factory=mock(FeedbackSnapshotFactory.class);
        when(factory.capture(eq(next.report()),anyList())).thenReturn(next.snapshot());
        var delivery=new FeedbackDeliveryService(store,factory,work,json);
        var pending=assertThrows(GeneralException.class,()->transaction.execute(tx->delivery.prepare(next.report(),f.recipient(),List.of())));
        assertEquals(GeneralErrorCode.CONFLICT,pending.getCode());
        // ensure() commits independently, although the caller's delivery transaction rolled back.
        var jobs=store.evaluations(next.report().getId(),f.recipient());
        assertEquals(1,jobs.size());assertEquals("PENDING",jobs.getFirst().status());
        assertTrue(store.evaluations(next.report().getId(),other.recipient()).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_capabilities WHERE report_id=?",Integer.class,next.report().getId()));
        verify(gateway,never()).evaluate(any(),anyLong(),any());

        when(gateway.evaluate(isNull(),anyLong(),any())).thenReturn(json.readTree("""
                {"decisions":[{"articleId":21,"status":"SUPPRESS","policyIds":[%d],"evidence":[{"articleId":21,"quote":"주가 전망"}],"reason":"명시한 개인 선호"}],"meta":{"truncated":false,"mock":false}}
                """.formatted(policy.id())));
        worker.process(jobs.getFirst().id());
        var delivered=transaction.execute(tx->delivery.prepare(next.report(),f.recipient(),List.of()));
        assertNotNull(delivered);assertEquals(Set.of(11L),delivered.suppressedFindingIds());assertNotNull(delivered.token());
        assertEquals(1,store.evaluations(next.report().getId(),f.recipient()).size());
        assertTrue(store.evaluations(next.report().getId(),other.recipient()).isEmpty());
        verify(gateway,times(1)).evaluate(any(),anyLong(),any());
    }
    private long jobFor(long feedbackId){return jdbc.queryForObject("SELECT id FROM news_feedback_jobs WHERE feedback_id=?",Long.class,feedbackId);}
    private SubmitRequest request(String token,String key){return new SubmitRequest(token,11L,"PREFERENCE","주가 전망은 제외해 주세요.",true,key);}
    private String link(Fixture f){String token=FeedbackTokens.create();store.capability(FeedbackTokens.hash(token),f.recipient(),f.snapshot(),now());return token;}
    private Fixture fixture(){return new TransactionTemplate(manager).execute(tx->{
        var topic=com.example.be.domain.topics.entity.Topic.builder().name("피드백 주제 "+UUID.randomUUID()).batchSize(100).intervalMinutes(1440).active(true).requiredKeywords(List.of()).optionalKeywords(List.of("HBM")).excludedKeywords(List.of()).build();em.persist(topic);em.flush();topics.add(topic.getId());
        var recipient=NotificationRecipient.builder().name("피드백 수신자").email("fixture@example.test").active(true).build();em.persist(recipient);em.flush();recipients.add(recipient.getId());
        var report=report(now());return make(recipient.getId(),topic.getId(),report,now().minusHours(1));});}
    private Fixture next(Fixture f,LocalDateTime start){return new TransactionTemplate(manager).execute(tx->make(f.recipient(),f.topic(),report(start.plusMinutes(2)),start));}
    private NewsReport report(LocalDateTime generated){var report=NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2194,1,1).plusDays(reports.size())).reportStatus(ReportStatus.GENERATED).title("당시 보고서").markdownBody("당시 내용").modelName("fixture").generatedAt(generated).build();em.persist(report);em.flush();reports.add(report.getId());return report;}
    private Fixture make(long recipient,long topic,NewsReport report,LocalDateTime start){var item=new Item(11,new Topic(topic,"HBM",List.of("HBM"),List.of()),new Issue(31,"당시 이슈","주가 전망"),List.of(new Article(21,"기사","주가 전망을 발표했다. "+"본문 ".repeat(5000),"https://example.test/news/21")),"hash","analysis-v1","model",start,"report-v1","model");return new Fixture(recipient,topic,report,new Snapshot(report.getId(),report.getTitle(),report.getGeneratedAt(),List.of(item)));}
    private record Fixture(long recipient,long topic,NewsReport report,Snapshot snapshot){}
    private static LocalDateTime now(){return LocalDateTime.now(ApiTimeZone.ZONE);}
}
