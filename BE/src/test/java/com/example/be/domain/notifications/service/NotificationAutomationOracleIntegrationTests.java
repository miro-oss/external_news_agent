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
import static org.mockito.Mockito.*;

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
    @Autowired NotificationManagementService management;
    @Autowired CollectionRunDeliveryService runDelivery;
    @Autowired RunDeliverySnapshotStore runSnapshots;
    @Autowired RecipientReportSubscriptionService subscriptions;
    @Autowired RunDeliverySettingsService runSettings;

    @Test
    void aggregateSubscriptionWorksWithoutTopicsAndUnsubscribeCancelsQueuedDelivery() {
        var first = recipient(true); var other = recipient(true);
        var saved = subscriptions.update(first.getId(), new RecipientReportSubscriptionService.SettingsUpdate(null, true, null));
        assertTrue(saved.topics().isEmpty()); assertTrue(saved.aggregates().weekly());
        assertFalse(saved.aggregates().daily()); assertFalse(subscriptions.get(other.getId()).aggregates().weekly());
        var weekly = aggregate(ReportScope.WEEKLY, List.of(), 30);
        automation.enqueueCompletedReport(weekly);
        assertEquals(List.of(first.getId()), jdbc.queryForList("SELECT recipient_id FROM report_notification_outbox WHERE report_id=?", Long.class, weekly.getId()));
        var queued = work(weekly.getId(), first);
        assertTrue(outbox.destinationStillActive(queued));
        assertEquals("Y", jdbc.queryForObject("SELECT aggregate_yn FROM report_notification_outbox WHERE id=?", String.class, queued.id()));
        subscriptions.update(first.getId(), new RecipientReportSubscriptionService.SettingsUpdate(null, false, null));
        assertFalse(outbox.destinationStillActive(queued));
        assertEquals(1, outbox.deliveries(weekly.getId()).size());
    }

    @Test
    void topicUnsubscribeChangesOnlyRunWhileAggregateChoicesRemainIndependent() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        var policy = new ReportNotificationAutomationService.Policy(true, true, true, true, List.of(), List.of(recipient.getId()), List.of(2L));
        automation.savePolicy(topic.getId(), policy);
        subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(true, true, null));
        var saved = subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(null, null,
                List.of(new RecipientReportSubscriptionService.TopicChoice(topic.getId(), false))));
        assertTrue(saved.aggregates().daily()); assertTrue(saved.aggregates().weekly());
        assertEquals(List.of("RUN"), saved.topics().getFirst().excludedScopes());
        assertEquals(policy, automation.policy(topic.getId()));
        var runReport = reports.reserve(run.getId(), now());
        reports.complete(runReport.reportId(), new ReportDocument("주제 보고서", "요약", "fallback"), now());
        assertTrue(outbox.deliveries(runReport.reportId()).isEmpty());
        var weekly = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 31);
        automation.enqueueCompletedReport(weekly);
        automation.enqueueCompletedReport(em.find(NewsReport.class, weekly.getSourceReportIds().getFirst()));
        assertEquals(1, outbox.deliveries(weekly.getId()).size());
        assertEquals(1, outbox.deliveries(weekly.getSourceReportIds().getFirst()).size());
    }

    @Test
    void aggregateOffOverridesCapturedRunTargetsAndPreviouslyQueuedTopicConsent() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        var selection = new com.example.be.domain.collection.dto.req.CollectionRunReqDTO.Delivery();
        selection.setMode("ONCE"); selection.setEnabled(true); selection.setDaily(true);
        selection.setRecipientIds(List.of(recipient.getId())); selection.setChannelIds(List.of(2L));
        runDelivery.save(run.getId(), List.of(topic.getId()), runDelivery.prepare(selection));
        var before = aggregate(ReportScope.DAILY, List.of(run.getId()), 32);
        automation.enqueueCompletedReport(before);
        var queued = work(before.getId(), recipient);
        assertTrue(outbox.destinationStillActive(queued));
        subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(false, null, null));
        assertFalse(outbox.destinationStillActive(queued));
        var after = aggregate(ReportScope.DAILY, List.of(run.getId()), 33);
        automation.enqueueCompletedReport(after);
        assertTrue(outbox.deliveries(after.getId()).isEmpty());
        assertTrue(runSnapshots.find(run.getId()).orElseThrow().daily());
    }

    @Test
    void partialAggregateUpdatePreservesOtherScopeAndInvalidTopicDoesNotPartiallySave() {
        var recipient = recipient(true);
        subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(true, true, null));
        var saved = subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(null, false, null));
        assertTrue(saved.aggregates().daily()); assertFalse(saved.aggregates().weekly());
        assertThrows(com.example.be.global.apiPayload.exception.GeneralException.class,
                () -> subscriptions.update(recipient.getId(), new RecipientReportSubscriptionService.SettingsUpdate(false, true,
                        List.of(new RecipientReportSubscriptionService.TopicChoice(Long.MAX_VALUE, false)))));
        var unchanged = subscriptions.get(recipient.getId());
        assertTrue(unchanged.aggregates().daily()); assertFalse(unchanged.aggregates().weekly());
    }

    @Test
    void personalWeeklyOptInQueuesOnlyThatGroupMemberAndResetCancelsPendingConsent() {
        var topic = topic(); var run = run(topic); var first = recipient(true); var second = recipient(true);
        var group = group(first, second);
        var policy = new ReportNotificationAutomationService.Policy(true, true, true,
                List.of(group.getId()), List.of(first.getId()), List.of(2L));
        automation.savePolicy(topic.getId(), policy);
        subscriptions.save(first.getId(), topic.getId(), preference(List.of(), List.of("WEEKLY")));
        em.clear();

        var weekly = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        assertEquals(List.of(first.getId()), jdbc.queryForList(
                "SELECT recipient_id FROM report_notification_outbox WHERE report_id=?", Long.class, weekly.getId()));
        assertEquals(policy, automation.policy(topic.getId()));
        var firstRow = subscriptions.get(first.getId()).topics().getFirst();
        assertEquals(List.of("WEEKLY"), firstRow.includedScopes());
        assertEquals(List.of("RUN", "DAILY"), firstRow.configuredScopes());
        assertTrue(subscriptions.get(second.getId()).topics().getFirst().includedScopes().isEmpty());
        assertEquals("[]", jdbc.queryForObject("SELECT source_topic_ids FROM report_notification_outbox WHERE report_id=?", String.class, weekly.getId()));
        assertEquals("[" + topic.getId() + "]", jdbc.queryForObject("SELECT personal_topic_ids FROM report_notification_outbox WHERE report_id=?", String.class, weekly.getId()));
        var queued = work(weekly.getId(), first);
        assertTrue(outbox.destinationStillActive(queued));
        subscriptions.save(first.getId(), topic.getId(), preference(List.of(), List.of()));
        assertFalse(outbox.destinationStillActive(queued));
        assertEquals(1, outbox.deliveries(weekly.getId()).size());
    }

    @Test
    void oldClientsPreserveOtherOptInsButCanUnsubscribeFromANewPersonalScope() {
        var topic = topic(); var recipient = recipient(true);
        subscriptions.save(recipient.getId(), topic.getId(), preference(List.of(), List.of("RUN", "WEEKLY")));
        var preserved = subscriptions.save(recipient.getId(), topic.getId(), excluded("DAILY"));
        assertEquals(List.of("RUN", "WEEKLY"), preserved.includedScopes());
        var removed = subscriptions.save(recipient.getId(), topic.getId(), excluded("WEEKLY"));
        assertEquals(List.of("RUN"), removed.includedScopes());
        assertEquals(List.of("WEEKLY"), removed.excludedScopes());
        em.clear();
        var retained = subscriptions.get(recipient.getId()).topics().getFirst();
        assertEquals(List.of("RUN"), retained.includedScopes());
        assertFalse(retained.enabled());
        assertTrue(retained.configuredScopes().isEmpty());
    }

    @Test
    void personalQueuedOriginsRecheckPolicyChannelAndGroupMembership() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true); var group = group(recipient);
        var policy = new ReportNotificationAutomationService.Policy(true, true, false,
                List.of(group.getId()), List.of(), List.of(2L));
        automation.savePolicy(topic.getId(), policy);
        subscriptions.save(recipient.getId(), topic.getId(), preference(List.of(), List.of("WEEKLY")));
        var weekly = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        var queued = work(weekly.getId(), recipient);
        assertTrue(outbox.destinationStillActive(queued));
        jdbc.update("UPDATE topic_delivery_policies SET enabled_yn='N' WHERE topic_id=?", topic.getId());
        assertFalse(outbox.destinationStillActive(queued));
        jdbc.update("UPDATE topic_delivery_policies SET enabled_yn='Y',channel_ids='[1]' WHERE topic_id=?", topic.getId());
        assertFalse(outbox.destinationStillActive(queued));
        jdbc.update("UPDATE topic_delivery_policies SET channel_ids='[2]' WHERE topic_id=?", topic.getId());
        jdbc.update("UPDATE notification_groups SET active_yn='N' WHERE id=?", group.getId());
        assertFalse(outbox.destinationStillActive(queued));
        jdbc.update("UPDATE notification_groups SET active_yn='Y' WHERE id=?", group.getId());
        assertTrue(outbox.destinationStillActive(queued));
        jdbc.update("DELETE FROM notification_group_members WHERE group_id=? AND recipient_id=?", group.getId(), recipient.getId());
        assertFalse(outbox.destinationStillActive(queued));
    }

    @Test
    void personalOptInCannotBypassDisabledPolicyOrMissingTargetAndChannel() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        subscriptions.save(recipient.getId(), topic.getId(), preference(List.of(), List.of("WEEKLY")));
        automation.savePolicy(topic.getId(), new ReportNotificationAutomationService.Policy(false, true, false,
                List.of(), List.of(recipient.getId()), List.of(2L)));
        var disabled = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 0);
        automation.enqueueCompletedReport(disabled);
        assertTrue(outbox.deliveries(disabled.getId()).isEmpty());
        var other = recipient(true);
        automation.savePolicy(topic.getId(), new ReportNotificationAutomationService.Policy(true, true, false,
                List.of(), List.of(other.getId()), List.of(2L)));
        var untargeted = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 1);
        automation.enqueueCompletedReport(untargeted);
        assertTrue(outbox.deliveries(untargeted.getId()).isEmpty());
        automation.savePolicy(topic.getId(), new ReportNotificationAutomationService.Policy(true, true, false,
                List.of(), List.of(recipient.getId()), List.of(1L)));
        var noChannel = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 2);
        automation.enqueueCompletedReport(noChannel);
        assertTrue(outbox.deliveries(noChannel.getId()).isEmpty());
    }

    @Test
    void personalRunAndDailyOptInsApplyToRecurringPoliciesButNeverExpandExplicitRunConsent() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        automation.savePolicy(topic.getId(), new ReportNotificationAutomationService.Policy(true, false, false, true,
                List.of(), List.of(recipient.getId()), List.of(2L)));
        subscriptions.save(recipient.getId(), topic.getId(), preference(List.of(), List.of("RUN", "DAILY")));
        var reserved = reports.reserve(run.getId(), now());
        reports.complete(reserved.reportId(), new ReportDocument("개인 실행 알림", "요약", "fallback"), now());
        assertEquals(1, outbox.deliveries(reserved.reportId()).size());
        var daily = aggregate(ReportScope.DAILY, List.of(run.getId()), 0);
        automation.enqueueCompletedReport(daily);
        assertEquals(1, outbox.deliveries(daily.getId()).size());

        var explicitRun = run(topic);
        runSnapshots.save(explicitRun.getId(), new RunDeliverySnapshotStore.Snapshot("ONCE", false, false, false, List.of()));
        var explicitReport = reports.reserve(explicitRun.getId(), now());
        reports.complete(explicitReport.reportId(), new ReportDocument("명시적으로 알림 중지", "요약", "fallback"), now());
        assertTrue(outbox.deliveries(explicitReport.reportId()).isEmpty());
        var explicitDaily = aggregate(ReportScope.DAILY, List.of(explicitRun.getId()), 1);
        automation.enqueueCompletedReport(explicitDaily);
        assertTrue(outbox.deliveries(explicitDaily.getId()).isEmpty());
    }

    @Test
    void unrelatedNewOptInsDoNotReviveAnOldQueueAndOriginalSharedConsentRemainsIndependent() {
        var personalTopic = topic(); var sharedTopic = topic(); var unrelated = topic();
        var personalRun = run(personalTopic); var sharedRun = run(sharedTopic); var unrelatedRun = run(unrelated);
        var recipient = recipient(true);
        var withoutWeekly = new ReportNotificationAutomationService.Policy(true, true, false,
                List.of(), List.of(recipient.getId()), List.of(2L));
        automation.savePolicy(personalTopic.getId(), withoutWeekly);
        automation.savePolicy(unrelated.getId(), withoutWeekly);
        automation.savePolicy(sharedTopic.getId(), new ReportNotificationAutomationService.Policy(true, false, false, true,
                List.of(), List.of(recipient.getId()), List.of(2L)));
        subscriptions.save(recipient.getId(), personalTopic.getId(), preference(List.of(), List.of("WEEKLY")));
        var weekly = aggregate(ReportScope.WEEKLY, List.of(personalRun.getId(), sharedRun.getId(), unrelatedRun.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        var queued = work(weekly.getId(), recipient);
        subscriptions.save(recipient.getId(), personalTopic.getId(), preference(List.of(), List.of()));
        assertTrue(outbox.destinationStillActive(queued));
        subscriptions.save(recipient.getId(), sharedTopic.getId(), excluded("WEEKLY"));
        subscriptions.save(recipient.getId(), unrelated.getId(), preference(List.of(), List.of("WEEKLY")));
        assertFalse(outbox.destinationStillActive(queued));
    }

    @Test
    void weeklyRequiresItsOwnCurrentPolicyAndIgnoresOneTimeRunOverrides() {
        var weeklyTopic = topic(); var dailyTopic = topic();
        var weeklyRun = run(weeklyTopic); var dailyRun = run(dailyTopic); var recipient = recipient(true);
        var group = group(recipient);
        automation.savePolicy(weeklyTopic.getId(), new ReportNotificationAutomationService.Policy(true, false, false, true,
                List.of(group.getId()), List.of(recipient.getId()), List.of(2L)));
        automation.savePolicy(dailyTopic.getId(), new ReportNotificationAutomationService.Policy(true, true, true,
                List.of(), List.of(recipient.getId()), List.of(2L)));
        runSnapshots.save(weeklyRun.getId(), new RunDeliverySnapshotStore.Snapshot("ONCE", false, false, false, List.of()));
        runSnapshots.save(dailyRun.getId(), new RunDeliverySnapshotStore.Snapshot("ONCE", true, true, true,
                List.of(new RunDeliverySnapshotStore.Target(recipient.getId(), recipient.getName(), 2L,
                        recipient.getDestinations().getFirst().getAddress()))));

        var weekly = aggregate(ReportScope.WEEKLY, List.of(weeklyRun.getId(), dailyRun.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        automation.enqueueCompletedReport(weekly);
        assertEquals(1, outbox.deliveries(weekly.getId()).size());
        assertEquals("[" + weeklyTopic.getId() + "]", jdbc.queryForObject(
                "SELECT source_topic_ids FROM report_notification_outbox WHERE report_id=?", String.class, weekly.getId()));
        assertFalse(automation.policy(dailyTopic.getId()).weekly());

        var onlyLegacy = aggregate(ReportScope.WEEKLY, List.of(dailyRun.getId()), 1);
        automation.enqueueCompletedReport(onlyLegacy);
        assertTrue(outbox.deliveries(onlyLegacy.getId()).isEmpty());
    }

    @Test
    void recipientRowsDeduplicateDirectGroupsAndShowOnlyUsableChannels() {
        var recipient = recipient(true); var activeGroup = group(recipient); var inactiveGroup = group(recipient);
        var direct = topic(); var disabled = topic(); var groupOnly = topic(); var retained = topic();
        recipient.getDestinations().add(RecipientDestination.builder().recipient(recipient)
                .channel(em.getReference(NotificationChannel.class, 1L)).address("123456")
                .use(true).onboarded(false).build());
        automation.savePolicy(direct.getId(), new ReportNotificationAutomationService.Policy(true, true, true, true,
                List.of(activeGroup.getId()), List.of(recipient.getId()), List.of(1L, 2L)));
        automation.savePolicy(disabled.getId(), new ReportNotificationAutomationService.Policy(false, false, false, true,
                List.of(), List.of(recipient.getId()), List.of(2L)));
        automation.savePolicy(groupOnly.getId(), new ReportNotificationAutomationService.Policy(true, false, false, true,
                List.of(inactiveGroup.getId()), List.of(), List.of(2L)));
        subscriptions.save(recipient.getId(), retained.getId(), excluded("WEEKLY"));
        inactiveGroup.update(inactiveGroup.getName(), inactiveGroup.getPerspective(), false);
        em.flush(); em.clear();

        var rows = subscriptions.get(recipient.getId()).topics();
        assertEquals(4, rows.size());
        var row = rows.stream().filter(value -> value.topicId().equals(direct.getId())).findFirst().orElseThrow();
        assertTrue(row.direct());
        assertEquals(List.of(activeGroup.getName()), row.groupNames());
        assertEquals(List.of("RUN", "DAILY", "WEEKLY"), row.configuredScopes());
        assertEquals(List.of("EMAIL"), row.channelTypes());
        assertFalse(rows.stream().filter(value -> value.topicId().equals(disabled.getId())).findFirst().orElseThrow().enabled());
        assertTrue(rows.stream().filter(value -> value.topicId().equals(groupOnly.getId())).findFirst().orElseThrow().channelTypes().isEmpty());
        var retainedRow = rows.stream().filter(value -> value.topicId().equals(retained.getId())).findFirst().orElseThrow();
        assertFalse(retainedRow.enabled());
        assertTrue(retainedRow.configuredScopes().isEmpty());
        assertEquals(List.of("WEEKLY"), retainedRow.excludedScopes());
    }

    @Test
    void personalUnsubscribeOverridesGroupAndDirectPathsWithoutChangingOtherRecipientsOrPolicy() {
        var topic = topic(); var run = run(topic); var first = recipient(true); var second = recipient(true);
        var group = group(first, second);
        var policy = new ReportNotificationAutomationService.Policy(true, true, true, true,
                List.of(group.getId()), List.of(first.getId()), List.of(2L));
        automation.savePolicy(topic.getId(), policy);
        subscriptions.save(first.getId(), topic.getId(), excluded("WEEKLY"));
        automation.savePolicy(topic.getId(), policy); // Saving shared defaults never resets the exclusion.
        var weekly = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        assertEquals(List.of(second.getId()), jdbc.queryForList(
                "SELECT recipient_id FROM report_notification_outbox WHERE report_id=?", Long.class, weekly.getId()));
        assertEquals(policy, automation.policy(topic.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_group_members WHERE group_id=?", Integer.class, group.getId()));

        subscriptions.save(first.getId(), topic.getId(), excluded());
        assertEquals(1, outbox.deliveries(weekly.getId()).size()); // Restoring settings never replays old reports.
        var next = aggregate(ReportScope.WEEKLY, List.of(run.getId()), 1);
        automation.enqueueCompletedReport(next);
        assertEquals(2, outbox.deliveries(next.getId()).size());
        var firstWork = work(next.getId(), first);
        assertTrue(outbox.destinationStillActive(firstWork));
        subscriptions.save(first.getId(), topic.getId(), excluded("WEEKLY"));
        assertFalse(outbox.destinationStillActive(firstWork));
        assertTrue(outbox.destinationStillActive(work(next.getId(), second)));
    }

    @Test
    void aggregateConsentPreservesAllOriginallyEligibleTopicsAndDoesNotAdoptRestoredOrNewConsent() {
        var first = topic(); var second = topic(); var unrelated = topic();
        var firstRun = run(first); var secondRun = run(second); var unrelatedRun = run(unrelated); var recipient = recipient(true);
        var policy = new ReportNotificationAutomationService.Policy(true, false, true, true, List.of(), List.of(recipient.getId()), List.of(2L));
        automation.savePolicy(first.getId(), policy);
        automation.savePolicy(second.getId(), policy);
        subscriptions.save(recipient.getId(), second.getId(), excluded("WEEKLY"));
        var weekly = aggregate(ReportScope.WEEKLY, List.of(firstRun.getId(), secondRun.getId(), unrelatedRun.getId()), 0);
        automation.enqueueCompletedReport(weekly);
        var queued = work(weekly.getId(), recipient);
        assertTrue(outbox.destinationStillActive(queued));
        subscriptions.save(recipient.getId(), first.getId(), excluded("WEEKLY"));
        subscriptions.save(recipient.getId(), second.getId(), excluded());
        automation.savePolicy(unrelated.getId(), policy);
        assertFalse(outbox.destinationStillActive(queued));

        subscriptions.save(recipient.getId(), first.getId(), excluded());
        var daily = aggregate(ReportScope.DAILY, List.of(firstRun.getId(), secondRun.getId()), 1);
        automation.enqueueCompletedReport(daily);
        var shared = work(daily.getId(), recipient);
        subscriptions.save(recipient.getId(), first.getId(), excluded("DAILY"));
        assertTrue(outbox.destinationStillActive(shared));
        subscriptions.save(recipient.getId(), second.getId(), excluded("DAILY"));
        assertFalse(outbox.destinationStillActive(shared));
    }

    @Test
    void queuedExplicitRunAndLegacyWorkHonorUnsubscribeWithoutChangingCapturedTargets() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        var selection = new com.example.be.domain.collection.dto.req.CollectionRunReqDTO.Delivery();
        selection.setMode("ONCE"); selection.setEnabled(true); selection.setRecipientIds(List.of(recipient.getId()));
        selection.setChannelIds(List.of(2L));
        runDelivery.save(run.getId(), List.of(topic.getId()), runDelivery.prepare(selection));
        var reserved = reports.reserve(run.getId(), now());
        reports.complete(reserved.reportId(), new ReportDocument("실행 보고서", "## 요약\n보관된 내용", "fallback"), now());
        var queued = work(reserved.reportId(), recipient);
        assertTrue(outbox.destinationStillActive(queued));
        subscriptions.save(recipient.getId(), topic.getId(), excluded("RUN"));
        assertFalse(outbox.destinationStillActive(queued));
        assertEquals(1, runSnapshots.find(run.getId()).orElseThrow().targets().size());
        jdbc.update("UPDATE report_notification_outbox SET source_topic_ids=NULL,personal_topic_ids=NULL WHERE id=?", queued.id());
        assertFalse(outbox.destinationStillActive(queued));
        subscriptions.save(recipient.getId(), topic.getId(), excluded());
        assertTrue(outbox.destinationStillActive(queued));
    }

    @Test
    void weeklyOnlyCollectionProducesEditableDisabledRunSettingsAndKeepsWeeklyPolicy() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        var selection = new com.example.be.domain.collection.dto.req.CollectionRunReqDTO.Delivery();
        selection.setMode("TOPIC"); selection.setEnabled(true); selection.setRun(false); selection.setDaily(false); selection.setWeekly(true);
        selection.setRecipientIds(List.of(recipient.getId())); selection.setChannelIds(List.of(2L));
        runDelivery.save(run.getId(), List.of(topic.getId()), runDelivery.prepare(selection));
        jdbc.update("UPDATE news_collection_runs SET status='PENDING' WHERE id=?", run.getId());
        em.clear();
        var settings = runSettings.get(run.getId());
        assertFalse(settings.enabled()); assertFalse(settings.run()); assertFalse(settings.daily()); assertTrue(settings.editable());
        var edit = new com.example.be.domain.notifications.dto.req.NotificationReqDTO.RunDeliverySettings();
        edit.setEnabled(false); edit.setRun(false); edit.setDaily(false); edit.setRecipientIds(settings.recipientIds()); edit.setChannelIds(settings.channelIds());
        runSettings.save(run.getId(), edit);
        assertTrue(automation.policy(topic.getId()).enabled());
        assertTrue(automation.policy(topic.getId()).weekly());
    }

    private RecipientReportSubscriptionService.Exclusions excluded(String... scopes) {
        return new RecipientReportSubscriptionService.Exclusions(List.of(scopes));
    }

    private RecipientReportSubscriptionService.Exclusions preference(List<String> excluded, List<String> included) {
        return new RecipientReportSubscriptionService.Exclusions(excluded, included);
    }

    private NotificationGroup group(NotificationRecipient... recipients) {
        var group = NotificationGroup.builder().name("보고서 구독 그룹 " + System.nanoTime()).active(true)
                .members(new ArrayList<>(List.of(recipients))).createdAt(now()).build();
        em.persist(group); em.flush(); return group;
    }

    private NewsReport aggregate(ReportScope scope, List<Long> runs, int offset) {
        var date = java.time.LocalDate.of(1998, 1, 5).plusWeeks(offset);
        var daily = scope == ReportScope.WEEKLY ? aggregate(ReportScope.DAILY, runs, offset) : null;
        var report = NewsReport.builder().title("통합 보고서").markdownBody("## 요약\n저장된 보고서").modelName("fallback")
                .reportStatus(ReportStatus.FALLBACK).reportScope(scope).reportDate(date)
                .reportEndDate(scope == ReportScope.WEEKLY ? date.plusDays(6) : null)
                .weeklyInput(daily == null ? null : new WeeklyReportInput(date, date.plusDays(6),
                        List.of(WeeklyReportInput.DailySource.from(daily)), date.plusDays(1).datesUntil(date.plusDays(7)).toList()))
                .sourceReportIds(daily == null ? List.of() : List.of(daily.getId()))
                .sourceReportDates(daily == null ? List.of() : List.of(date))
                .sourceRunIds(runs).generatedAt(now()).build();
        em.persist(report); em.flush(); return report;
    }

    private ReportDeliveryOutboxStore.Work work(Long reportId, NotificationRecipient recipient) {
        return jdbc.queryForObject("SELECT * FROM report_notification_outbox WHERE report_id=? AND recipient_id=?",
                (rs, n) -> new ReportDeliveryOutboxStore.Work(rs.getLong("id"), reportId, recipient.getId(), rs.getLong("channel_id"),
                        rs.getString("batch_id"), rs.getString("recipient_name"), rs.getString("address"),
                        rs.getString("subject"), rs.getString("body"), rs.getInt("attempt_count")), reportId, recipient.getId());
    }

    @Test
    void oneTimeDeliverySurvivesReloadAndLaterPolicyAndGroupEdits() {
        var topic = topic(); var run = run(topic);
        var original = recipient(true); var replacement = recipient(true);
        var group = NotificationGroup.builder().name("접수 스냅샷 그룹 " + System.nanoTime()).active(true)
                .members(new ArrayList<>(List.of(original))).createdAt(now()).build();
        em.persist(group); em.flush();
        var selection = new com.example.be.domain.collection.dto.req.CollectionRunReqDTO.Delivery();
        selection.setMode("ONCE"); selection.setEnabled(true); selection.setGroupIds(List.of(group.getId()));
        selection.setChannelIds(List.of(2L));
        runDelivery.save(run.getId(), List.of(topic.getId()), runDelivery.prepare(selection));
        String originalAddress = original.getDestinations().getFirst().getAddress();
        group.getMembers().clear(); group.getMembers().add(replacement);
        automation.savePolicy(topic.getId(), new ReportNotificationAutomationService.Policy(true, true, true,
                List.of(), List.of(replacement.getId()), List.of(2L)));
        em.flush(); em.clear();

        var reloaded = new RunDeliverySnapshotStore(jdbc).find(run.getId()).orElseThrow();
        assertEquals(List.of(original.getId()), reloaded.targets().stream().map(RunDeliverySnapshotStore.Target::recipientId).toList());
        assertEquals(originalAddress, reloaded.targets().getFirst().address());
        var reserved = reports.reserve(run.getId(), now());
        reports.complete(reserved.reportId(), new ReportDocument("한 번만 전달", "## 핵심 요약\n접수 당시 선택", "fallback"), now());
        assertEquals(1, outbox.deliveries(reserved.reportId()).size());
        assertEquals(original.getId(), jdbc.queryForObject("SELECT recipient_id FROM report_notification_outbox WHERE report_id=?", Long.class, reserved.reportId()));
        assertEquals(originalAddress, jdbc.queryForObject("SELECT address FROM report_notification_outbox WHERE report_id=?", String.class, reserved.reportId()));
    }

    @Test
    void explicitRunOffDoesNotChangeTopicPolicyAndSuppressesBothReportScopes() {
        var topic = topic(); var run = run(topic); var recipient = recipient(true);
        var existing = new ReportNotificationAutomationService.Policy(true, true, true, List.of(), List.of(recipient.getId()), List.of(2L));
        automation.savePolicy(topic.getId(), existing);
        var selection = new com.example.be.domain.collection.dto.req.CollectionRunReqDTO.Delivery();
        selection.setMode("ONCE"); selection.setEnabled(false);
        runDelivery.save(run.getId(), List.of(topic.getId()), runDelivery.prepare(selection));
        em.flush(); em.clear();
        assertEquals(existing, automation.policy(topic.getId()));
        assertFalse(runSnapshots.find(run.getId()).orElseThrow().enabled());
        var reserved = reports.reserve(run.getId(), now());
        reports.complete(reserved.reportId(), new ReportDocument("전달 안 함", "## 요약\n저장만 합니다.", "fallback"), now());
        assertTrue(outbox.deliveries(reserved.reportId()).isEmpty());
        var daily = NewsReport.builder().title("일일 통합").markdownBody("요약").modelName("fallback")
                .reportStatus(ReportStatus.FALLBACK).reportScope(ReportScope.DAILY)
                .reportDate(java.time.LocalDate.of(1997, 2, 5)).sourceRunIds(List.of(run.getId())).generatedAt(now()).build();
        em.persist(daily); em.flush(); automation.enqueueCompletedReport(daily);
        assertTrue(outbox.deliveries(daily.getId()).isEmpty());
    }

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

    @Test
    void pollingConnectsValidNonceDespiteTelegramClockSkewAndReleasesLease() {
        var recipient = recipient(false);
        var link = connections.createLink(recipient.getId(), "example_bot");
        var adapter = mock(com.example.be.domain.notifications.channel.TelegramConnectionAdapter.class);
        var poller = new TelegramConnectionPoller(adapter, connections);
        long offset = jdbc.queryForObject("SELECT next_update_id FROM telegram_update_cursor WHERE id=1", Long.class);
        var message = update(link, 505).message();
        var start = new Update(offset + 5, new Message(message.text(), message.chat(), message.from(),
                Instant.now().minusSeconds(30).getEpochSecond()));
        when(adapter.configured()).thenReturn(true);
        when(adapter.updates(offset)).thenReturn(List.of(start));

        poller.poll();

        assertEquals("CONNECTED", connections.status(recipient.getId()).status());
        assertEquals(offset + 6, jdbc.queryForObject("SELECT next_update_id FROM telegram_update_cursor WHERE id=1", Long.class));
        assertNull(jdbc.queryForObject("SELECT lease_until FROM telegram_update_cursor WHERE id=1", LocalDateTime.class));
        connections.advance(offset);
        assertEquals(offset + 6, jdbc.queryForObject("SELECT next_update_id FROM telegram_update_cursor WHERE id=1", Long.class));
        poller.poll();
        verify(adapter, times(1)).updates(offset);
        verifyNoMoreInteractions(ignoreStubs(adapter));
    }

    @Test
    void emailEditPersistsNewAddressWithoutLosingTelegramAndDuplicateKeepsOriginal() {
        var recipient = recipient(true);
        var oldEmail = recipient.getDestinations().getFirst().getAddress();
        recipient.update(recipient.getName(), null, oldEmail, null, true);
        var telegramAddress = "synthetic-" + System.nanoTime();
        var destinations = new ArrayList<>(recipient.getDestinations());
        destinations.add(RecipientDestination.builder().channel(em.getReference(NotificationChannel.class, 1L))
                .address(telegramAddress).use(true).onboarded(true).build());
        recipient.replaceDestinations(destinations);
        em.flush();
        em.clear();

        var newEmail = "edited-" + System.nanoTime() + "@invalid.test";
        var request = new com.example.be.domain.notifications.dto.req.NotificationReqDTO.DestinationsUpdate();
        request.setDestinations(List.of(emailEditDestination(1L, telegramAddress), emailEditDestination(2L, newEmail)));
        management.replaceDestinations(recipient.getId(), request);
        var basic = new com.example.be.domain.notifications.dto.req.NotificationReqDTO.RecipientUpdate();
        basic.setEmail(newEmail);
        management.updateRecipient(recipient.getId(), basic);
        em.flush();
        em.clear();

        assertEquals(newEmail, jdbc.queryForObject("SELECT email FROM notification_recipients WHERE id=?", String.class, recipient.getId()));
        assertEquals(newEmail, jdbc.queryForObject("SELECT address FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id=2", String.class, recipient.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id=1 AND address=? AND use_yn='Y' AND onboarded_yn='Y'", Integer.class, recipient.getId(), telegramAddress));

        var other = recipient(true);
        request.setDestinations(List.of(emailEditDestination(1L, telegramAddress),
                emailEditDestination(2L, other.getDestinations().getFirst().getAddress())));
        var failure = assertThrows(com.example.be.domain.notifications.exception.NotificationException.class,
                () -> management.replaceDestinations(recipient.getId(), request));
        assertEquals("RECIPIENT409", failure.getCode().getCode());
        assertEquals(newEmail, jdbc.queryForObject("SELECT address FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id=2", String.class, recipient.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipient_destinations WHERE recipient_id=?", Integer.class, recipient.getId()));
    }

    private com.example.be.domain.notifications.dto.req.NotificationReqDTO.DestinationInput emailEditDestination(Long channelId, String address) {
        var input = new com.example.be.domain.notifications.dto.req.NotificationReqDTO.DestinationInput();
        input.setChannelId(channelId);
        input.setAddress(address);
        input.setUse(true);
        return input;
    }

    private Topic topic() {
        var value=Topic.builder().name("알림 테스트 "+System.nanoTime()).active(true).batchSize(10).intervalMinutes(1440).build();
        em.persist(value); em.flush(); return value;
    }
    private CollectionRun run(Topic topic) {
        var source=Source.builder().sourceKind(Source.KIND_FEED).name("알림 소스 "+System.nanoTime())
                .urlTemplate("https://example.invalid/feed/" + System.nanoTime()).language("ko").active(true).build();em.persist(source);
        var run=CollectionRun.builder().status(RunStatus.SUCCESS).triggerType(TriggerType.MANUAL).startedAt(now()).build();
        run.addItem(CollectionRunItem.builder().topic(topic).source(source).status(RunItemStatus.SUCCESS).build());
        em.persist(run);
        // 보고서 생성 조건을 충족하는 신규 기사 관측을 저장한다.
        var article = Article.builder().topic(topic).source(source)
                .urlHash(java.util.UUID.randomUUID().toString().replace("-", "").repeat(2))
                .canonicalUrl("https://example.invalid/news/" + System.nanoTime()).title("신규 알림 기사")
                .fetchStatus(FetchStatus.METADATA_ONLY).collectedAt(now()).build();
        em.persist(article);
        em.persist(CollectionRunArticle.observe(run, article, topic, source, ChangeType.NEW, now()));
        em.flush();return run;
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
