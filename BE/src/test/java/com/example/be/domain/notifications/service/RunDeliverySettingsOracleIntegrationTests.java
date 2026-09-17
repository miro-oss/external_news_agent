package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.reports.comparison.ReportComparisonWorker;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.global.apiPayload.exception.GeneralException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Separate committed Oracle transactions prove the shared run lock orders edit and completion. */
@SpringBootTest(properties = {"news.notifications.automation-enabled=false", "news.notifications.telegram.connections-enabled=false"})
@ActiveProfiles("local")
// This class has its own mocked comparison worker; no other integration class can
// reuse the context. Release its Oracle pool after the committed race fixtures.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class RunDeliverySettingsOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired CollectionRunRepository runs;
    @Autowired RunDeliverySettingsService settings;
    @Autowired RunDeliverySnapshotStore snapshots;
    @Autowired ReportPersistenceService reports;
    @MockitoBean ReportComparisonWorker comparisons;
    private Long runId;
    private Long reportId;
    private Long recipientId;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @BeforeEach
    void fixture() {
        transactions.executeWithoutResult(tx -> {
            var run = CollectionRun.builder().status(RunStatus.RUNNING).triggerType(TriggerType.MANUAL).startedAt(now()).build();
            em.persist(run);
            var recipient = NotificationRecipient.builder().name("알림 수정 경합 " + System.nanoTime()).active(true).build();
            recipient.replaceDestinations(List.of(RecipientDestination.builder()
                    .channel(em.getReference(NotificationChannel.class, 2L)).address("race-" + System.nanoTime() + "@invalid.test")
                    .use(true).onboarded(true).build()));
            em.persist(recipient);
            var report = NewsReport.builder().run(run).title("생성 중").markdownBody("생성 중").modelName("pending-report-v1")
                    .reportStatus(ReportStatus.PENDING).generatedAt(now()).build();
            em.persist(report); em.flush();
            run.attachReport(report.getId());
            runId = run.getId(); reportId = report.getId(); recipientId = recipient.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        transactions.executeWithoutResult(tx -> {
            if (reportId != null) {
                jdbc.update("DELETE FROM report_notification_outbox WHERE report_id=?", reportId);
                jdbc.update("DELETE FROM notification_delivery_batches WHERE report_id=?", reportId);
                jdbc.update("UPDATE news_collection_runs SET report_id=NULL WHERE id=?", runId);
                jdbc.update("DELETE FROM news_reports WHERE id=?", reportId);
            }
            if (runId != null) {
                jdbc.update("DELETE FROM run_delivery_targets WHERE run_id=?", runId);
                jdbc.update("DELETE FROM run_delivery_settings WHERE run_id=?", runId);
                jdbc.update("DELETE FROM news_collection_runs WHERE id=?", runId);
            }
            if (recipientId != null) {
                jdbc.update("DELETE FROM notification_recipient_destinations WHERE recipient_id=?", recipientId);
                jdbc.update("DELETE FROM notification_recipients WHERE id=?", recipientId);
            }
        });
    }

    @Test
    void committedEditIsUsedWhenCompletionWaitsForTheEdit() throws Exception {
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1), completionStarted = new CountDownLatch(1);
        Future<?> edit = executor.submit(() -> transactions.executeWithoutResult(tx -> {
            settings.save(runId, enabled());
            written.countDown(); await(release);
        }));
        try {
            assertTrue(written.await(10, TimeUnit.SECONDS));
            Future<?> completion = executor.submit(() -> {
                completionStarted.countDown(); return reports.complete(reportId, document(), now());
            });
            assertTrue(completionStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> completion.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); edit.get(10, TimeUnit.SECONDS); completion.get(10, TimeUnit.SECONDS);
            assertEquals(1, outboxCount());
            assertEquals(recipientId, jdbc.queryForObject("SELECT recipient_id FROM report_notification_outbox WHERE report_id=?", Long.class, reportId));
            assertFalse(settings.get(runId).editable());
        } finally { release.countDown(); }
    }

    @Test
    void completionBeforeEditReturnsConflictAndCannotChangeQueuedDelivery() throws Exception {
        settings.save(runId, enabled());
        CountDownLatch completed = new CountDownLatch(1), release = new CountDownLatch(1), editStarted = new CountDownLatch(1);
        Future<?> completion = executor.submit(() -> transactions.executeWithoutResult(tx -> {
            reports.complete(reportId, document(), now());
            completed.countDown(); await(release);
        }));
        try {
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            Future<?> edit = executor.submit(() -> {
                editStarted.countDown(); var off = enabled(); off.setEnabled(false); return settings.save(runId, off);
            });
            assertTrue(editStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> edit.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); completion.get(10, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> edit.get(10, TimeUnit.SECONDS));
            assertInstanceOf(GeneralException.class, error.getCause());
            assertEquals("COMMON409", ((GeneralException) error.getCause()).getCode().getCode());
            assertTrue(settings.get(runId).enabled()); assertEquals(1, outboxCount());
        } finally { release.countDown(); }
    }

    @Test
    void selectionAndTargetsSurviveReloadAndReplacementWithoutChangingDailyScope() {
        var selection = enabled(); selection.setRun(false); selection.setDaily(true);
        settings.save(runId, selection);
        var snapshot = snapshots.find(runId).orElseThrow();
        assertEquals(List.of(recipientId), snapshot.recipientIds());
        assertEquals(List.of(2L), snapshot.channelIds());
        assertFalse(snapshot.run()); assertTrue(snapshot.daily());
        selection.setEnabled(false); settings.save(runId, selection);
        var disabled = snapshots.find(runId).orElseThrow();
        assertFalse(disabled.enabled()); assertEquals(snapshot.targets(), disabled.targets());
        selection.setEnabled(true); settings.save(runId, selection);
        assertEquals(snapshot.targets(), snapshots.find(runId).orElseThrow().targets());
        reports.complete(reportId, document(), now());
        assertEquals(0, outboxCount());
    }

    private int outboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM report_notification_outbox WHERE report_id=?", Integer.class, reportId); }
    private NotificationReqDTO.RunDeliverySettings enabled() {
        var value = new NotificationReqDTO.RunDeliverySettings();
        value.setEnabled(true); value.setRun(true); value.setDaily(false);
        value.setRecipientIds(List.of(recipientId)); value.setChannelIds(List.of(2L)); return value;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test transaction release"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
    private static ReportDocument document() { return new ReportDocument("테스트 보고서", "## 요약\n알림 설정 적용", "fallback"); }
    private static LocalDateTime now() { return LocalDateTime.now(com.example.be.global.config.ApiTimeZone.ZONE); }
}
