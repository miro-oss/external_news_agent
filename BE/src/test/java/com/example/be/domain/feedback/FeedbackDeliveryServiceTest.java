package com.example.be.domain.feedback;

import com.example.be.domain.reports.entity.NewsReport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import static com.example.be.domain.feedback.FeedbackModels.*;
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
        when(factory.capture(eq(report),anyList())).thenReturn(new Snapshot(5,"보고서",now,List.of(item)));
        when(store.policies(7)).thenReturn(List.of(policy));
        // Jackson's standalone mapper does not install java.time modules; use the application's record input via mocked parser.
        var mapped=mock(ObjectMapper.class);when(mapped.readValue("input",FeedbackWorkService.EvaluationInput.class)).thenReturn(new FeedbackWorkService.EvaluationInput(item.topic(),List.of(item),List.of(policy)));
        service=new FeedbackDeliveryService(store,factory,work,mapped);ReflectionTestUtils.setField(service,"schedulingEnabled",false);
        when(store.evaluations(5,7)).thenReturn(List.of(new Job(8,"EVALUATE",null,7,5,2,"input",null,"PENDING",null,0)));
        var result=service.prepare(report,7,List.of());assertEquals(Set.of(),result.suppressedFindingIds());assertNotNull(result.token());
        verifyNoInteractions(work);verify(store).capability(anyString(),eq(7L),any(),any());
    }
}
