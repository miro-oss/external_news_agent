package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.notifications.repository.*;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction participation, with no connection to an application database. */
class ReportCompletionTransactionTest {
    enum StaleTarget { MISSING_GROUP, INACTIVE_GROUP, MISSING_CHANNEL, INACTIVE_CHANNEL, MISSING_RECIPIENT }

    static Stream<org.junit.jupiter.params.provider.Arguments> staleTargets() {
        return Stream.of(StaleTarget.values()).flatMap(target -> Stream.of(false, true)
                .map(recovered -> org.junit.jupiter.params.provider.Arguments.of(target, recovered)));
    }

    @ParameterizedTest
    @MethodSource("staleTargets")
    void staleTargetCannotRollBackDailyCompletionOrItsValidDelivery(StaleTarget stale, boolean recovered)
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);

        var channels = mock(NotificationChannelRepository.class);
        var groups = mock(NotificationGroupRepository.class);
        var recipients = mock(NotificationRecipientRepository.class);
        var management = transactional(new NotificationManagementService(channels, recipients,
                mock(RecipientDestinationRepository.class), groups, mock(NotificationSenderRegistry.class)), transactions);
        var email = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL).active(true).build();
        var recipient = NotificationRecipient.builder().id(7L).name("유효 수신자").active(true)
                .destinations(List.of(RecipientDestination.builder().channel(email).use(true).onboarded(true)
                        .address("valid@example.invalid").build())).build();
        var group = NotificationGroup.builder().id(81L).name("유효 그룹").active(true)
                .members(List.of(recipient)).build();
        when(groups.findById(81L)).thenReturn(Optional.of(group));
        when(channels.findById(2L)).thenReturn(Optional.of(email));
        when(recipients.findById(7L)).thenReturn(Optional.of(recipient));
        if (stale == StaleTarget.INACTIVE_GROUP) {
            when(groups.findById(41L)).thenReturn(Optional.of(NotificationGroup.builder().id(41L).active(false).build()));
        }
        if (stale == StaleTarget.INACTIVE_CHANNEL) {
            when(channels.findById(3L)).thenReturn(Optional.of(NotificationChannel.builder().id(3L).active(false).build()));
        }

        var jdbc = mock(JdbcTemplate.class);
        var topics = mock(TopicRepository.class);
        var snapshots = mock(RunDeliverySnapshotStore.class);
        var outbox = mock(ReportDeliveryOutboxStore.class);
        var renderer = mock(NotificationRenderer.class);
        var reports = mock(NewsReportRepository.class);
        var plans = new NotificationDeliveryPlanService(reports, channels, groups, management, renderer,
                new com.example.be.domain.notifications.config.NotificationProperties());
        var subscriptions = mock(ReportSubscriptionStore.class);
        when(subscriptions.allowedTopics(anyLong(), any(), anyList())).thenAnswer(invocation -> invocation.getArgument(2));
        var automation = transactional(new ReportNotificationAutomationService(jdbc, topics, management,
                plans, renderer, snapshots, outbox, groups, channels, recipients, subscriptions), transactions);
        when(snapshots.find(42L)).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(42L))).thenReturn(List.of(1L));
        when(topics.existsById(1L)).thenReturn(true);
        List<Long> groupIds = stale == StaleTarget.MISSING_GROUP || stale == StaleTarget.INACTIVE_GROUP
                ? List.of(41L, 81L) : List.of(81L);
        List<Long> channelIds = stale == StaleTarget.MISSING_CHANNEL || stale == StaleTarget.INACTIVE_CHANNEL
                ? List.of(3L, 2L) : List.of(2L);
        List<Long> recipientIds = stale == StaleTarget.MISSING_RECIPIENT ? List.of(8L) : List.of();
        var policy = new ReportNotificationAutomationService.Policy(true, false, true,
                groupIds, recipientIds, channelIds);
        doReturn(List.of(policy)).when(jdbc).query(startsWith("SELECT * FROM topic_delivery_policies"),
                any(RowMapper.class), eq(1L));
        var report = NewsReport.builder().id(117L).reportScope(ReportScope.DAILY)
                .reportDate(LocalDate.of(2026, 9, 18)).sourceRunIds(List.of(42L))
                .reportStatus(ReportStatus.PENDING).build();
        when(reports.findByIdForUpdate(117L)).thenReturn(Optional.of(report));
        when(renderer.render(report, email)).thenReturn(new RenderedNotification("보고서", null, List.of("요약")));
        var persistence = transactional(new ReportPersistenceService(mock(CollectionRunRepository.class), reports,
                automation, mock(CollectionRunArticleRepository.class), mock(ApplicationEventPublisher.class)), transactions);
        var document = new ReportDocument("일일 통합 보고서", "## 요약\n근거 요약", "fallback");

        assertDoesNotThrow(() -> {
            if (recovered) persistence.completeRecovered(117L, document, LocalDateTime.now());
            else persistence.complete(117L, document, LocalDateTime.now());
        });

        assertEquals(ReportStatus.FALLBACK, report.getReportStatus());
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(jdbc).update(startsWith("INSERT INTO report_notification_outbox"), eq(117L), eq(7L), eq(2L),
                anyString(), eq("유효 수신자"), eq("valid@example.invalid"), eq("보고서"), eq("요약"), any(), eq("[1]"));
        verify(renderer, times(1)).render(report, email);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager transactions) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        // Spring's class proxies also apply @Transactional to package-visible service methods.
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource(false));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        return (T) proxy.getProxy();
    }
}
