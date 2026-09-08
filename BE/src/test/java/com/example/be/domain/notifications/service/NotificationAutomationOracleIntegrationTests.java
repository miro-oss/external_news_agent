package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.*;
import com.example.be.domain.notifications.channel.TelegramConnectionAdapter.*;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"news.agent.enabled=false","news.notifications.automation-enabled=false","news.notifications.telegram.connections-enabled=false"})
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named="news.integration.db",matches="true")
class NotificationAutomationOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportNotificationAutomationService automation;
    @Autowired ReportPersistenceService reports;
    @Autowired ReportDeliveryOutboxStore outbox;
    @Autowired TelegramConnectionService connections;

    @Test void reportCompletionQueuesOnceAcrossGroupAndIndividualThenRetriesOnlyFailure() {
        var topic=topic(); var run=run(topic);
        var recipient=recipient(true);
        var group=NotificationGroup.builder().name("자동전달 테스트 "+System.nanoTime()).active(true)
                .members(new ArrayList<>(List.of(recipient))).createdAt(now()).build();
        em.persist(group); em.flush();
        automation.savePolicy(topic.getId(),new ReportNotificationAutomationService.Policy(true,true,false,
                List.of(group.getId()),List.of(recipient.getId()),List.of(2L)));
        var reserved=reports.reserve(run.getId(),now());
        reports.complete(reserved.reportId(),new ReportDocument("테스트 요약","## 핵심 요약\n- 저장된 보고서 요약","fallback"),now());
        reports.complete(reserved.reportId(),new ReportDocument("중복 이벤트","중복","fallback"),now());
        assertEquals(1,outbox.deliveries(reserved.reportId()).size());
        assertEquals("PENDING",outbox.deliveries(reserved.reportId()).getFirst().status());
        var work=outbox.claim().stream().filter(w->w.reportId().equals(reserved.reportId())).findFirst().orElseThrow();
        assertTrue(work.body().contains("저장된 보고서 요약"));
        assertTrue(outbox.destinationStillActive(work));
        outbox.finish(work,"EMAIL","SENT","fake-message-id",null,false);
        assertEquals(0,outbox.retryFailed(reserved.reportId()));
        assertTrue(outbox.claim().stream().noneMatch(w->w.reportId().equals(reserved.reportId())));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_logs WHERE report_id=? AND status='SENT'",Integer.class,reserved.reportId()));
    }

    @Test
    void disabledPolicyPreservesSelectionsAfterTargetsBecomeInactive() {
        var topic = topic();
        var recipient = recipient(true);
        var group = NotificationGroup.builder().name("중지 정책 테스트 " + System.nanoTime()).active(true)
                .members(new ArrayList<>(List.of(recipient))).createdAt(now()).build();
        em.persist(group);
        em.flush();
        var enabled = new ReportNotificationAutomationService.Policy(true, true, false,
                List.of(group.getId()), List.of(recipient.getId()), List.of(2L));
        automation.savePolicy(topic.getId(), enabled);

        group.update(group.getName(), group.getPerspective(), false);
        recipient.softDelete(now());
        em.flush();
        var disabled = new ReportNotificationAutomationService.Policy(false, true, false,
                enabled.groupIds(), enabled.recipientIds(), enabled.channelIds());
        automation.savePolicy(topic.getId(), disabled);
        assertEquals(disabled, automation.policy(topic.getId()));
        assertThrows(com.example.be.global.apiPayload.exception.GeneralException.class,
                () -> new ReportNotificationAutomationService.Policy(false, true, false,
                        List.of(-1L), List.of(), List.of()));
    }

    @Test void dailyPolicyDoesNotQueueRunAndUnknownIsNotResent() {
        var topic=topic(); var run=run(topic);var recipient=recipient(true);
        automation.savePolicy(topic.getId(),new ReportNotificationAutomationService.Policy(true,false,true,List.of(),List.of(recipient.getId()),List.of(2L)));
        var reserved=reports.reserve(run.getId(),now());
        reports.complete(reserved.reportId(),new ReportDocument("실행","요약","fallback"),now());
        assertTrue(outbox.deliveries(reserved.reportId()).isEmpty());
        var daily=NewsReport.builder().title("일일 통합").markdownBody("## 요약\n일일 요약").modelName("fallback")
                .reportStatus(ReportStatus.FALLBACK).reportScope(ReportScope.DAILY)
                .reportDate(java.time.LocalDate.of(1997,2,4)).sourceRunIds(List.of(run.getId())).generatedAt(now()).build();
        em.persist(daily);em.flush();automation.enqueueCompletedReport(daily);
        var work=outbox.claim().stream().filter(w->w.reportId().equals(daily.getId())).findFirst().orElseThrow();
        outbox.finish(work,"EMAIL","UNKNOWN",null,"결과 미확인",false);
        assertEquals(0,outbox.retryFailed(daily.getId()));
        assertEquals("UNKNOWN",outbox.deliveries(daily.getId()).getFirst().status());
    }

    @Test void replacementExpiryReplayAndDisconnectCannotRebindTelegram() {
        var recipient=recipient(false);
        var first=connections.createLink(recipient.getId(),"example_bot");
        var replacement=connections.createLink(recipient.getId(),"example_bot");
        connections.accept(update(first,101));
        assertEquals("WAITING",connections.status(recipient.getId()).status());
        connections.accept(update(replacement,101));
        assertEquals("CONNECTED",connections.status(recipient.getId()).status());
        connections.accept(update(replacement,202));
        assertEquals("101",jdbc.queryForObject("SELECT address FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id=1",String.class,recipient.getId()));
        connections.disconnect(recipient.getId());
        connections.accept(update(replacement,202));
        assertEquals("DISCONNECTED",connections.status(recipient.getId()).status());
        var expired=connections.createLink(recipient.getId(),"example_bot");
        jdbc.update("UPDATE telegram_connection_tokens SET expires_at=? WHERE recipient_id=? AND consumed_at IS NULL",now().minusMinutes(1),recipient.getId());
        connections.accept(update(expired,303));
        assertEquals("EXPIRED",connections.status(recipient.getId()).status());
    }

    @Test void expiredClaimRecordsUncertainResultAndLeavesWaitingRecipientsPending() {
        var topic=topic(); var run=run(topic);
        var first=recipient(true); var second=recipient(true);
        automation.savePolicy(topic.getId(),new ReportNotificationAutomationService.Policy(true,true,false,
                List.of(),List.of(first.getId(),second.getId()),List.of(2L)));
        var reserved=reports.reserve(run.getId(),now());
        reports.complete(reserved.reportId(),new ReportDocument("요약","## 요약\n짧은 보고서","fallback"),now());
        var claimed=outbox.claim();
        assertEquals(1,claimed.size());
        var interrupted=claimed.getFirst();
        assertEquals(1,outbox.deliveries(reserved.reportId()).stream().filter(d->d.status().equals("PENDING")).count());
        jdbc.update("UPDATE report_notification_outbox SET processing_at=? WHERE id=?",now().minusMinutes(6),interrupted.id());

        var next=outbox.claim();
        assertEquals(1,next.size());
        assertNotEquals(interrupted.id(),next.getFirst().id());
        assertEquals("UNKNOWN",outbox.deliveries(reserved.reportId()).stream().filter(d->d.id().equals(interrupted.id())).findFirst().orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_logs WHERE delivery_batch_id=? AND status='FAILED' AND error_message IS NOT NULL",Integer.class,interrupted.batchId()));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM notification_delivery_batches WHERE id=?",LocalDateTime.class,interrupted.batchId()));
        assertEquals(0,outbox.retryFailed(reserved.reportId()));
    }

    @Test void telegramCannotStealAnotherRecipientsChat() {
        var first=recipient(false); var second=recipient(false);
        connections.accept(update(connections.createLink(first.getId(),"example_bot"),404));
        connections.accept(update(connections.createLink(second.getId(),"example_bot"),404));
        assertEquals("CONNECTED",connections.status(first.getId()).status());
        assertNotEquals("CONNECTED",connections.status(second.getId()).status());
        assertEquals(first.getId(),jdbc.queryForObject("SELECT recipient_id FROM notification_recipient_destinations WHERE channel_id=1 AND address='404'",Long.class));
    }

    private Topic topic() {
        var value=Topic.builder().name("알림 테스트 "+System.nanoTime()).active(true).batchSize(10).intervalMinutes(1440).build();
        em.persist(value); em.flush(); return value;
    }
    private CollectionRun run(Topic topic) {
        var source=Source.builder().sourceKind(Source.KIND_FEED).name("알림 소스 "+System.nanoTime())
                .urlTemplate("https://example.invalid/feed").language("ko").active(true).build();em.persist(source);
        var run=CollectionRun.builder().status(RunStatus.SUCCESS).triggerType(TriggerType.MANUAL).startedAt(now()).build();
        run.addItem(CollectionRunItem.builder().topic(topic).source(source).status(RunItemStatus.SUCCESS).build());
        em.persist(run);em.flush();return run;
    }
    private NotificationRecipient recipient(boolean withEmail) {
        var recipient=NotificationRecipient.builder().name("테스트 수신자 "+System.nanoTime()).active(true).build();
        if(withEmail) recipient.replaceDestinations(List.of(RecipientDestination.builder().channel(em.getReference(NotificationChannel.class,2L))
                .address("recipient-"+System.nanoTime()+"@invalid.test").use(true).onboarded(true).build()));
        em.persist(recipient);em.flush();return recipient;
    }
    private Update update(TelegramConnectionService.Link link,long chatId) {
        String token=link.url().substring(link.url().indexOf("start=")+6);
        return new Update(chatId,new Message("/start "+token,new Chat(chatId,"private"),new User(chatId,false),Instant.now().getEpochSecond()));
    }
    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
