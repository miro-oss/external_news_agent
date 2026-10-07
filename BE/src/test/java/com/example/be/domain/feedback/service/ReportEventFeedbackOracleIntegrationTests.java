package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.agent.service.FeedbackAgentGateway;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"news.agent.enabled=false","news.notifications.automation-enabled=false","news.feedback.enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="news.integration.db",matches="true")
class ReportEventFeedbackOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ReportEventFeedbackService service;
    @Autowired FeedbackService deliveredFeedback;
    @Autowired FeedbackStore store;
    @Autowired FeedbackWorker worker;
    @Autowired FeedbackLearningService learning;
    @Autowired ObjectMapper json;
    @MockitoBean ReportEventSnapshotFactory snapshots;
    @MockitoBean FeedbackAgentGateway gateway;
    private final List<Long> reports=new ArrayList<>();
    private final Map<Long,List<Item>> inputs=new ConcurrentHashMap<>();

    @BeforeEach void snapshotLookup() {
        when(snapshots.capture(any(),any())).thenAnswer(call->inputs.getOrDefault(((NewsReport)call.getArgument(0)).getId(),List.of()));
    }
    @AfterEach void clean() {
        for(long id:reports) {
            jdbc.update("DELETE FROM news_feedback_jobs WHERE report_id=?",id);
            jdbc.update("DELETE FROM news_feedback WHERE report_id=?",id);
            jdbc.update("DELETE FROM news_reports WHERE id=?",id);
        }
    }

    @Test void contextHasNoWritesAndHidesPendingAndDeletedReports() {
        long report=report(List.of(item('a',2,null)));
        var context=service.context(report);
        assertEquals(List.of(501L,502L),context.events().getFirst().sourceFindingIds());
        assertTrue(context.feedback().isEmpty());
        assertEquals(0,count("news_feedback",report));assertEquals(0,count("news_feedback_jobs",report));
        assertEquals(0,count("news_feedback_capabilities",report));verifyNoInteractions(gateway);
        inputs.put(report,List.of());assertTrue(service.context(report).events().isEmpty());
        jdbc.update("UPDATE news_reports SET report_status='PENDING' WHERE id=?",report);
        assertThrows(ReportException.class,()->service.context(report));
        assertThrows(ReportException.class,()->service.submit(report,request('a',"key","SUMMARY_ERROR")));
        jdbc.update("UPDATE news_reports SET report_status='GENERATED',deleted_at=? WHERE id=?",now(),report);
        assertThrows(ReportException.class,()->service.context(report));
    }

    @Test void batchReviewStatesKeepReportIsolationOrderAndLeaveFrozenInputsOnSingleReportReads() {
        long first=report(List.of(item('a',2,null),item('b',2,null)));
        long second=report(List.of(item('a',2,null)));
        long untouched=report(List.of(item('a',2,null)));
        var firstReview=service.submit(first,request('a',"first-batch","SUMMARY_ERROR"));
        var secondReview=service.submit(second,request('a',"second-batch","SUMMARY_ERROR"));
        var laterReview=service.submit(first,request('b',"later-batch","WRONG_CLUSTER"));
        service.submit(untouched,request('a',"outside-batch","SUMMARY_ERROR"));
        store.reviewed(store.byId(firstReview.id()).orElseThrow(),"CONFIRMED_ERROR","오류 확인",result("CONFIRMED_ERROR"),now());
        clearInvocations(snapshots);

        var states=store.eventReviewStates(List.of(second,first,first));

        assertEquals(List.of(firstReview.id(),secondReview.id(),laterReview.id()),states.stream().map(Feedback::id).toList());
        assertEquals(List.of(first,second,first),states.stream().map(Feedback::reportId).toList());
        assertEquals(List.of(Status.COMPLETED,Status.PENDING,Status.PENDING),states.stream().map(Feedback::status).toList());
        assertEquals("CONFIRMED_ERROR",states.getFirst().verdict());
        assertEquals("a".repeat(64),states.getFirst().eventKey());
        assertEquals("a".repeat(64),states.get(1).eventKey());
        assertTrue(states.stream().allMatch(feedback->feedback.input()==null && feedback.diagnosis()==null));
        assertTrue(store.eventReviewStates(List.of()).isEmpty());
        var complete=store.eventFeedback(first);
        assertEquals(List.of(firstReview.id(),laterReview.id()),complete.stream().map(Feedback::id).toList());
        assertEquals(inputs.get(first).getFirst(),complete.getFirst().input());
        assertEquals("오류 확인",complete.getFirst().diagnosis());
        verifyNoInteractions(snapshots,gateway);
    }

    @Test void concurrentRetriesReserveAllKeysAndSeparateEventsWorkWithOracleNullOwnership() throws Exception {
        long report=report(List.of(item('a',2,null),item('b',2,null)));
        var latch=new CountDownLatch(1);
        long first;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{latch.await();return service.submit(report,request('a',"first","SUMMARY_ERROR"));});
            var b=pool.submit(()->{latch.await();return service.submit(report,request('a',"alias","SUMMARY_ERROR"));});
            latch.countDown();first=a.get(10,TimeUnit.SECONDS).id();assertEquals(first,b.get(10,TimeUnit.SECONDS).id());
        }
        assertEquals(GeneralErrorCode.CONFLICT,assertThrows(GeneralException.class,()->service.submit(report,request('b',"alias","SUMMARY_ERROR"))).getCode());
        var second=service.submit(report,request('b',"second","SUMMARY_ERROR"));assertNotEquals(first,second.id());
        assertEquals(2,count("news_feedback",report));assertEquals(2,count("news_feedback_jobs",report));
        var saved=store.byId(first).orElseThrow();assertNull(saved.recipientId());assertNull(saved.capabilityId());assertNull(saved.itemId());
        inputs.put(report,List.of());
        assertEquals(first,service.submit(report,request('a',"retry-after-revision","SUMMARY_ERROR")).id());
        assertThrows(GeneralException.class,()->service.submit(report,request('c',"retry-after-revision","SUMMARY_ERROR")));
        assertThrows(GeneralException.class,()->service.submit(report,request('c',"new-stale-key","SUMMARY_ERROR")));
        assertThrows(GeneralException.class,()->service.submit(report,new EventSubmitRequest("a".repeat(64),"SUMMARY_ERROR","다른 의견","first")));
        assertEquals(0,count("news_feedback_capabilities",report));verifyNoInteractions(gateway);
    }

    @Test void reviewUsesFrozenWholeEventAndExportsOriginalPerSourceTopicInputs() {
        long report=report(List.of(item('a',2,null)));
        var submitted=service.submit(report,request('a',"review","SUMMARY_ERROR"));
        inputs.put(report,List.of(item('b',1,null)));
        when(gateway.review(isNull(),eq(report),any())).thenReturn(result("CONFIRMED_ERROR"));
        worker.process(job(submitted.id()));
        var saved=store.byId(submitted.id()).orElseThrow();assertEquals(Status.COMPLETED,saved.status());
        assertEquals("CONFIRMED_ERROR",saved.verdict());assertEquals(2,saved.input().event().sources().size());
        var request=ArgumentCaptor.forClass(JsonNode.class);verify(gateway).review(isNull(),eq(report),request.capture());
        var payload=request.getValue();assertEquals("a".repeat(64),payload.path("event").path("key").asString());
        assertEquals(2,payload.path("articles").size());assertEquals(2,payload.path("topics").size());
        assertEquals(502,payload.path("event").path("sourceFindingIds").get(1).asLong());
        assertTrue(payload.path("issue").isMissingNode());assertTrue(payload.path("topic").isMissingNode());
        assertTrue(payload.path("activePolicies").isEmpty());assertFalse(payload.path("feedback").path("allowPersonalization").asBoolean());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_policies WHERE source_feedback_id=?",Integer.class,submitted.id()));
        var cases=json.valueToTree(deliveredFeedback.export(submitted.id()-1,1)).path("cases");assertEquals(2,cases.size());
        var exported=cases.get(0);assertEquals(report,exported.path("sourceReportId").asLong());
        assertEquals("a".repeat(64),exported.path("sourceEventKey").asString());assertEquals(501,exported.path("sourceFindingId").asLong());
        assertTrue(exported.path("sourceIssueId").isNull());assertTrue(exported.path("goldLabel").isNull());assertTrue(exported.path("split").isNull());
        assertEquals("USER_FEEDBACK",exported.path("labelSource").asString());
        assertEquals("검색1",exported.path("request").path("topic").path("queryText").asString());
        assertEquals("필수1",exported.path("request").path("topic").path("requiredKeywords").get(0).asString());
        assertEquals("선택1",exported.path("request").path("topic").path("optionalKeywords").get(0).asString());
    }

    @Test void reviewedEventAutomaticallyBecomesScopedInputForTheNextAnalysisAcrossRestarts() {
        var original=item('a',2,null);
        long report=report(List.of(original));
        var scopes=original.event().sources().stream().flatMap(source->source.collectionTopics().stream()).toList();
        var submitted=service.submit(report,request('a',"learn-next-run","SUMMARY_ERROR"));
        var beforeReview=now().minusSeconds(1);
        assertTrue(learning.forSnapshots(scopes,now(),Set.of(Category.SUMMARY_ERROR)).isEmpty());
        when(gateway.review(isNull(),eq(report),any())).thenReturn(json.readTree("""
                {"verdict":"CONFIRMED_ERROR","diagnosis":"원문에서 확인한 범위로 요약한다.",
                 "evidence":[{"articleId":401,"quote":"원문 근거 1"},{"articleId":402,"quote":"원문 근거 2"}],
                 "proposedPolicy":null,"meta":{"provider":"openai","truncated":false,"mock":false}}
                """));

        worker.process(job(submitted.id()));

        assertTrue(learning.forSnapshots(scopes,beforeReview,Set.of(Category.SUMMARY_ERROR)).isEmpty());
        var examples=learning.forSnapshots(scopes,now().plusSeconds(1),Set.of(Category.SUMMARY_ERROR));
        assertEquals(List.of(701L,702L),examples.stream().map(example->example.topicId()).toList());
        assertTrue(examples.stream().allMatch(example->example.feedbackId()==submitted.id()));
        assertEquals(List.of(401L),examples.getFirst().evidence().stream().map(evidence->evidence.articleId()).toList());
        assertEquals(List.of(402L),examples.getLast().evidence().stream().map(evidence->evidence.articleId()).toList());
        assertEquals(examples,new FeedbackLearningService(jdbc,json)
                .forSnapshots(scopes,now().plusSeconds(1),Set.of(Category.SUMMARY_ERROR)));
        assertTrue(learning.forSnapshots(scopes,now().plusSeconds(1),Set.of(Category.TOPIC_MISMATCH)).isEmpty());
        var prior=scopes.getFirst();
        var changed=new CollectionTopicSnapshot(prior.topicId(),prior.topicName(),"바뀐 검색 범위",
                prior.requiredKeywords(),prior.optionalKeywords(),prior.excludedKeywords(),100,1440);
        assertTrue(learning.forSnapshots(List.of(changed),now().plusSeconds(1),Set.of(Category.SUMMARY_ERROR)).isEmpty());
        jdbc.update("UPDATE news_reports SET deleted_at=? WHERE id=?",now(),report);
        assertTrue(learning.forSnapshots(scopes,now().plusSeconds(1),Set.of(Category.SUMMARY_ERROR)).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_policies WHERE source_feedback_id=?",
                Integer.class,submitted.id()));
    }

    @Test void confirmedErrorDisappearsAcrossReloadsWithoutChangingOtherEventsOrSavedEvidence() {
        // These events intentionally share finding IDs: one incorrect event does not invalidate its siblings.
        var originals=IntStream.range(0,6).mapToObj(i->item((char)('a'+i),2,null)).toList();
        long report=report(originals);
        var rejected=service.submit(report,request('a',"rejected","SUMMARY_ERROR"));
        var notConfirmed=service.submit(report,request('b',"not-confirmed","WRONG_CLUSTER"));
        var pending=service.submit(report,request('c',"pending","TOPIC_MISMATCH"));
        var preference=service.submit(report,request('d',"preference","PREFERENCE"));
        var insufficient=service.submit(report,request('e',"insufficient","SUMMARY_ERROR"));
        var processing=service.submit(report,request('f',"processing","OTHER"));
        assertEquals(6,service.context(report).events().size());

        when(gateway.review(isNull(),eq(report),any())).thenReturn(result("CONFIRMED_ERROR"));
        worker.process(job(rejected.id()));
        store.reviewed(store.byId(notConfirmed.id()).orElseThrow(),"NOT_CONFIRMED","오류를 확인하지 못했습니다.",result("NOT_CONFIRMED"),now());
        // Even an unexpected error verdict on personal preference must never suppress a common event.
        store.reviewed(store.byId(preference.id()).orElseThrow(),"CONFIRMED_ERROR","관심에 대한 의견입니다.",result("CONFIRMED_ERROR"),now());
        store.reviewed(store.byId(insufficient.id()).orElseThrow(),"INSUFFICIENT_EVIDENCE","검토 근거가 부족합니다.",result("INSUFFICIENT_EVIDENCE"),now());
        jdbc.update("UPDATE news_feedback SET status='PROCESSING',verdict='CONFIRMED_ERROR' WHERE id=?",processing.id());
        var frozenInput=store.byId(rejected.id()).orElseThrow().input();
        var storedReport=reportSnapshot(report);
        int feedbackCount=count("news_feedback",report),jobCount=count("news_feedback_jobs",report);
        int requestCount=count("news_feedback_event_requests",report);
        clearInvocations(gateway);

        var refreshed=service.context(report);
        assertEquals(List.of('b','c','d','e','f').stream().map(key->String.valueOf(key).repeat(64)).toList(),
                refreshed.events().stream().map(event->event.eventKey()).toList());
        assertEquals(List.of(0,1,2,3,4),refreshed.events().stream().map(event->event.eventIndex()).toList());
        assertTrue(refreshed.events().stream().allMatch(event->event.sourceFindingIds().equals(List.of(501L,502L))));
        assertEquals(6,refreshed.feedback().size());
        assertEquals("CONFIRMED_ERROR",refreshed.feedback().stream().filter(feedback->feedback.id()==rejected.id()).findFirst().orElseThrow().verdict());
        assertEquals("PENDING",refreshed.feedback().stream().filter(feedback->feedback.id()==pending.id()).findFirst().orElseThrow().status());
        for(int reload=0;reload<3;reload++)assertEquals(refreshed,service.context(report));
        assertEquals(feedbackCount,count("news_feedback",report));assertEquals(jobCount,count("news_feedback_jobs",report));
        assertEquals(requestCount,count("news_feedback_event_requests",report));assertEquals(0,count("news_feedback_capabilities",report));
        assertEquals(storedReport,reportSnapshot(report));assertEquals(frozenInput,store.byId(rejected.id()).orElseThrow().input());
        assertEquals(originals,inputs.get(report));verifyNoInteractions(gateway);

        var acceptedRetry=service.submit(report,request('a',"retry-hidden-event","SUMMARY_ERROR"));
        assertEquals(rejected.id(),acceptedRetry.id());assertEquals("COMPLETED",acceptedRetry.status());
        assertEquals("CONFIRMED_ERROR",acceptedRetry.verdict());
        assertEquals(acceptedRetry,service.submit(report,request('a',"retry-hidden-event","SUMMARY_ERROR")));
        assertEquals(feedbackCount,count("news_feedback",report));assertEquals(jobCount,count("news_feedback_jobs",report));
        assertEquals(requestCount+1,count("news_feedback_event_requests",report));
        assertEquals(refreshed,service.context(report));assertEquals(storedReport,reportSnapshot(report));
        assertEquals(frozenInput,store.byId(rejected.id()).orElseThrow().input());verifyNoInteractions(gateway);
    }

    @Test void errorReviewOfOlderEventRevisionDoesNotSuppressTheCurrentEvent() {
        var original=item('a',2,null);
        long report=report(List.of(original));
        var submitted=service.submit(report,request('a',"old-revision","SUMMARY_ERROR"));
        store.reviewed(store.byId(submitted.id()).orElseThrow(),"CONFIRMED_ERROR","이전 이벤트의 오류를 확인했습니다.",result("CONFIRMED_ERROR"),now());
        assertTrue(service.context(report).events().isEmpty());

        var event=original.event();
        var revisedEvent=new EventContext("b".repeat(64),event.index(),event.title(),event.summary(),event.significance(),
                event.sourceFindingIds(),event.topics(),event.sources(),event.unavailableReason());
        var revised=new Item(null,null,null,original.articles(),null,null,null,null,"report-v1","fixture",null,revisedEvent);
        inputs.put(report,List.of(revised));
        var current=service.context(report);
        assertEquals(1,current.events().size());assertEquals("b".repeat(64),current.events().getFirst().eventKey());
        assertEquals(0,current.events().getFirst().eventIndex());
        assertEquals("a".repeat(64),current.feedback().getFirst().eventKey());
        assertEquals(original,store.byId(submitted.id()).orElseThrow().input());
        assertEquals(current,service.context(report));verifyNoInteractions(gateway);
    }

    @Test void localPreferencesProduceDiagnosisWithoutActivatingOrExportingPersonalPolicies() {
        long report=report(List.of(item('a',1,null)));
        var submitted=service.submit(report,request('a',"preference","PREFERENCE"));
        when(gateway.review(isNull(),eq(report),any())).thenReturn(result("PREFERENCE"));
        worker.process(job(submitted.id()));
        var saved=store.byId(submitted.id()).orElseThrow();assertEquals(Status.COMPLETED,saved.status());
        assertTrue(saved.diagnosis().contains("개인 전달 기준은 변경하지 않았습니다"));
        assertFalse(saved.allowPersonalization());assertNull(saved.recipientId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_policies WHERE source_feedback_id=?",Integer.class,submitted.id()));
        assertTrue(json.valueToTree(deliveredFeedback.export(submitted.id()-1,1)).path("cases").isEmpty());
    }

    @Test void incompleteOrOversizedEventsCompleteWithoutProviderAndKeepAllFrozenSources() {
        var many=item('a',11,null);var missing=item('b',2,"당시 수집 주제 조건이 누락되었습니다.");
        var base=item('c',1,null);var source=base.event().sources().getFirst();
        var huge=new Article(source.article().id(),"기사","본문".repeat(6000),source.article().url());
        var oversized=new Item(null,null,null,List.of(huge),null,null,null,null,"report-v1","fixture",null,base.event());
        long report=report(List.of(many,missing,oversized));
        for(char key:new char[]{'a','b','c'}) {
            var submitted=service.submit(report,request(key,"key-"+key,"SUMMARY_ERROR"));worker.process(job(submitted.id()));
            var saved=store.byId(submitted.id()).orElseThrow();assertEquals(Status.COMPLETED,saved.status());
            assertEquals("INSUFFICIENT_EVIDENCE",saved.verdict());
            if(key=='a')assertEquals(11,saved.input().event().sources().size());
        }
        verifyNoInteractions(gateway);
    }

    @Test void databaseKeepsDeliveryAndEvaluationOwnershipMandatory() {
        long report=report(List.of());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
                INSERT INTO news_feedback(report_id,item_id,category,user_comment,allow_personalization,idempotency_key,request_hash,input_json,created_at)
                VALUES(?,1,'OTHER','test','N','invalid',?,'{}',?)
                """,report,"a".repeat(64),now()));
        assertThrows(DataIntegrityViolationException.class,()->store.enqueue("invalid:"+report,"EVALUATE",null,null,report,null,Map.of(),now()));
        assertEquals(0,count("news_feedback",report));assertEquals(0,count("news_feedback_jobs",report));
    }

    @Test void incompleteSourceKeepsKnownContextButIsExcludedFromEvaluationCandidates() {
        var base=item('a',2,null);var known=base.event().sources().getFirst();
        var incomplete=new EventSource(known.findingId(),known.runId(),known.article(),known.topics(),known.collectionTopics(),
                known.analysisInputHash(),known.promptVersion(),known.model(),known.runStartedAt(),known.analysisSummary(),false);
        var event=base.event();
        var frozen=new EventContext(event.key(),event.index(),event.title(),event.summary(),event.significance(),event.sourceFindingIds(),
                event.topics(),List.of(incomplete,event.sources().get(1)),"일부 근거의 당시 수집 주제 조건이 없어 검토를 보류했습니다.");
        var input=new Item(null,null,null,base.articles(),null,null,null,null,"report-v1","fixture",null,frozen);
        long report=report(List.of(input));var submitted=service.submit(report,request('a',"partial","SUMMARY_ERROR"));
        worker.process(job(submitted.id()));
        var saved=store.byId(submitted.id()).orElseThrow();assertEquals("INSUFFICIENT_EVIDENCE",saved.verdict());
        assertFalse(saved.input().event().sources().getFirst().contextComplete());
        assertEquals(known.collectionTopics(),saved.input().event().sources().getFirst().collectionTopics());
        var cases=json.valueToTree(deliveredFeedback.export(submitted.id()-1,1)).path("cases");
        assertEquals(1,cases.size());assertEquals(502,cases.get(0).path("sourceFindingId").asLong());
        verifyNoInteractions(gateway);
    }

    private long report(List<Item> items) {
        return new TransactionTemplate(manager).execute(tx->{
            var report=NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2189,1,1).plusDays(reports.size()))
                    .reportStatus(ReportStatus.GENERATED).title("이벤트 보고서").markdownBody("보고서").modelName("fixture").generatedAt(now()).build();
            report.recordStructuredContent(new ReportContent(items.stream().map(item->item.event().summary()).limit(3).toList(),
                    items.stream().map(item->new ReportContent.ImportantEvent(item.event().title(),item.event().summary(),
                            item.event().significance(),item.event().sourceFindingIds())).toList(),List.of(),List.of()));
            em.persist(report);em.flush();reports.add(report.getId());inputs.put(report.getId(),items);return report.getId();
        });
    }
    private Item item(char key,int count,String reason) {
        var sources=IntStream.rangeClosed(1,count).mapToObj(i->{
            var scope=new CollectionTopicSnapshot(700L+i,"주제"+i,"검색"+i,List.of("필수"+i),List.of("선택"+i),List.of("제외"+i),100,1440);
            var topic=new Topic(scope.topicId(),scope.topicName(),List.of("필수"+i,"선택"+i),scope.excludedKeywords());
            return new EventSource(500L+i,600L+i,new Article(400L+i,"기사"+i,"원문 근거 "+i,"https://example.test/"+i),List.of(topic),List.of(scope),"hash"+i,"analysis-v1","fixture",now(),"분석"+i);
        }).toList();
        var event=new EventContext(String.valueOf(key).repeat(64),key-'a',"이벤트"+key,"모든 기사를 묶은 요약","중요한 이유",
                sources.stream().map(EventSource::findingId).toList(),sources.stream().flatMap(s->s.topics().stream()).toList(),sources,reason);
        return new Item(null,null,null,sources.stream().map(EventSource::article).toList(),null,null,null,null,"report-v1","fixture",null,event);
    }
    private EventSubmitRequest request(char key,String idempotency,String category) { return new EventSubmitRequest(String.valueOf(key).repeat(64),category,"  원문과 다릅니다.  ",idempotency); }
    private JsonNode result(String verdict) { return json.readTree("""
            {"verdict":"%s","diagnosis":"전체 원문을 확인했습니다.","evidence":[{"articleId":401,"quote":"원문 근거"}],"proposedPolicy":null,"meta":{"truncated":false,"mock":false}}
            """.formatted(verdict)); }
    private long job(long feedbackId) { return jdbc.queryForObject("SELECT id FROM news_feedback_jobs WHERE feedback_id=?",Long.class,feedbackId); }
    private List<String> reportSnapshot(long reportId) {
        return jdbc.queryForObject("SELECT markdown_body,structured_content,report_reflected_finding_ids FROM news_reports WHERE id=?",
                (row,index)->List.of(row.getString("markdown_body"),row.getString("structured_content"),row.getString("report_reflected_finding_ids")),reportId);
    }
    private int count(String table,long reportId) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE report_id=?",Integer.class,reportId); }
    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
