package com.example.be.domain.feedback.service;

import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.reports.entity.NewsReport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackDeliveryServiceTest {
    @Test void disabledSchedulingKeepsPendingArticlesWithoutCreatingJobsOrBlockingDelivery() {
        var store=mock(FeedbackStore.class);var factory=mock(FeedbackSnapshotFactory.class);var work=mock(FeedbackWorkService.class);var json=new ObjectMapper();
        var service=new FeedbackDeliveryService(store,factory,work,json);ReflectionTestUtils.setField(service,"schedulingEnabled",false);
        var now=LocalDateTime.now();var report=NewsReport.builder().id(5L).build();
        var item=new Item(1,new Topic(2,"주제",List.of(),List.of()),new Issue(3,"제목","요약"),List.of(new Article(4,"제목","본문","https://example.test")),"hash","v1","model",now,"v1","model");
        var policy=new Policy(6,7,2,"주제","제외",1,"ACTIVE",now.minusDays(1));
        when(factory.capture(eq(report),anyList(),any())).thenReturn(new Snapshot(5,"보고서",now,List.of(item)));
        when(store.policies(7)).thenReturn(List.of(policy));
        // Jackson's standalone mapper does not install java.time modules; use the application's record input via mocked parser.
        var mapped=mock(ObjectMapper.class);when(mapped.readValue("input",EvaluationInput.class)).thenReturn(new EvaluationInput(item.topic(),List.of(item),List.of(policy)));
        service=new FeedbackDeliveryService(store,factory,work,mapped);ReflectionTestUtils.setField(service,"schedulingEnabled",false);
        when(store.evaluations(5,7)).thenReturn(List.of(new Job(8,"EVALUATE",null,7L,5,2L,"input",null,"PENDING",null,0)));
        var result=service.prepare(report,7,List.of());assertEquals(Set.of(),result.suppressedFindingIds());assertNotNull(result.token());
        verifyNoInteractions(work);verify(store).capability(anyString(),eq(7L),any(),any());
    }

    @Test void newCapabilityUsesProjectedSharedEventWithoutChangingStoredReportOrFrozenEvidence() {
        var store=mock(FeedbackStore.class);var factory=mock(FeedbackSnapshotFactory.class);var work=mock(FeedbackWorkService.class);
        var service=new FeedbackDeliveryService(store,factory,work,new ObjectMapper());
        var now=LocalDateTime.of(2026,10,1,12,0);
        var bad=new com.example.be.domain.reports.entity.ReportContent.ImportantEvent("잘못된 사건","잘못된 요약","",List.of(11L));
        var good=new com.example.be.domain.reports.entity.ReportContent.ImportantEvent("정상 사건","정상 요약","",List.of(11L));
        var stored=new com.example.be.domain.reports.entity.ReportContent(List.of("잘못된 요약"),List.of(bad,good),List.of(),List.of());
        var report=NewsReport.builder().id(5L).title("보고서").generatedAt(now).structuredContent(stored).build();
        var finding=com.example.be.domain.analysis.entity.Finding.builder().id(11L).build();
        var reading=new com.example.be.domain.reports.service.ReportReadingContent("정상 요약",
                new com.example.be.domain.reports.entity.ReportContent(List.of("정상 요약"),List.of(good),List.of(),List.of()));
        var view=new com.example.be.domain.reports.service.ReportEventFeedbackProjection.View(List.of(finding),reading,true);
        var article=new Article(21,"기사","본문","https://example.test/21");
        var item=new Item(11,new Topic(2,"주제",List.of(),List.of()),new Issue(3,"잘못된 사건","잘못된 요약"),List.of(article),"hash","v1","model",now,"v1","model","원래 분석");
        var frozen=new Snapshot(5,"보고서",now,List.of(item));
        when(factory.capture(report,view.findings(),reading)).thenReturn(frozen);

        var result=service.prepare(report,7,view);

        assertTrue(result.suppressedFindingIds().isEmpty());assertNotNull(result.token());
        verify(work).ensure(frozen,7);
        var captured=org.mockito.ArgumentCaptor.forClass(Snapshot.class);
        verify(store).capability(anyString(),eq(7L),captured.capture(),any());
        assertEquals("정상 사건",captured.getValue().items().getFirst().issue().title());
        assertEquals("정상 요약",captured.getValue().items().getFirst().issue().summary());
        assertEquals(List.of(article),captured.getValue().items().getFirst().articles());
        assertSame(stored,report.getStructuredContent());
        assertEquals("잘못된 요약",frozen.items().getFirst().issue().summary());
    }

    @Test void personalSuppressionOmitsOrphanSharedEvidenceFromNewCapability() {
        var store=mock(FeedbackStore.class);var factory=mock(FeedbackSnapshotFactory.class);var work=mock(FeedbackWorkService.class);
        var json=mock(ObjectMapper.class);var service=new FeedbackDeliveryService(store,factory,work,json);
        var now=LocalDateTime.of(2026,10,1,12,0);var topic=new Topic(2,"주제",List.of(),List.of());
        var shared=new Article(22,"공유 기사","공유 본문","https://example.test/22");
        var removed=new Article(23,"개인 기준 제외 기사","제외 본문","https://example.test/23");
        var first=new Item(12,topic,new Issue(3,"정상 결합 사건","정상 결합 요약"),List.of(shared,removed),"hash12","v1","model",now,"v1","model","잘못된 사건을 반복할 수 있는 분석");
        var second=new Item(13,topic,first.issue(),List.of(removed,shared),"hash13","v1","model",now,"v1","model","개인 기준 제외 분석");
        var event=new com.example.be.domain.reports.entity.ReportContent.ImportantEvent("정상 결합 사건","정상 결합 요약","",List.of(12L,13L));
        var content=new com.example.be.domain.reports.entity.ReportContent(List.of("정상 결합 요약"),List.of(event),List.of(),List.of());
        var report=NewsReport.builder().id(5L).title("보고서").generatedAt(now).structuredContent(content).build();
        var findings=List.of(com.example.be.domain.analysis.entity.Finding.builder().id(12L).build(),
                com.example.be.domain.analysis.entity.Finding.builder().id(13L).build());
        var view=new com.example.be.domain.reports.service.ReportEventFeedbackProjection.View(findings,
                new com.example.be.domain.reports.service.ReportReadingContent("정상 결합 요약",content),true);
        var snapshot=new Snapshot(5,"보고서",now,List.of(first,second));
        when(factory.capture(report,findings,view.readingContent())).thenReturn(snapshot);
        var policy=new Policy(9,7,2,"주제","개인 기준",1,"ACTIVE",now.minusDays(1));
        when(store.policies(7)).thenReturn(List.of(policy));
        when(store.evaluations(5,7)).thenReturn(List.of(new Job(8,"EVALUATE",null,7L,5,2L,"input","result","COMPLETED",null,0)));
        when(json.readValue("input",EvaluationInput.class)).thenReturn(new EvaluationInput(topic,List.of(first,second),List.of(policy)));
        when(json.readTree("result")).thenReturn(new ObjectMapper().readTree("""
                {"decisions":[{"articleId":23,"status":"SUPPRESS","policyIds":[9]}]}
                """));

        var result=service.prepare(report,7,view);

        assertEquals(Set.of(13L),result.suppressedFindingIds());
        var captured=org.mockito.ArgumentCaptor.forClass(Snapshot.class);
        verify(store).capability(anyString(),eq(7L),captured.capture(),any());
        assertTrue(captured.getValue().items().isEmpty());
        assertEquals(List.of(2L),captured.getValue().topicIds(),"empty delivery keeps policy revocation access");
        assertEquals(2,snapshot.items().size());
    }
}
