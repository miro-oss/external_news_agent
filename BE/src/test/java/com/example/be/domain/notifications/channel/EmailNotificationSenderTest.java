package com.example.be.domain.notifications.channel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.service.NotificationAttachment;
import jakarta.mail.Authenticator;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailNotificationSenderTest {

    private final NotificationProperties properties = new NotificationProperties();
    private final Transport transport = mock(Transport.class);
    private final AtomicReference<Properties> mailProperties = new AtomicReference<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(EmailNotificationSender.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockedStatic<Session> sessions;
    private EmailNotificationSender sender;

    @BeforeEach
    void setUp() {
        properties.getSmtp().setUsername("private-user@example.test");
        properties.getSmtp().setPassword("private-app-password");
        sender = new EmailNotificationSender(properties);
        sessions = mockStatic(Session.class);
        sessions.when(() -> Session.getInstance(any(Properties.class), nullable(Authenticator.class)))
                .thenAnswer(invocation -> {
                    mailProperties.set(invocation.getArgument(0));
                    Session session = spy((Session) invocation.callRealMethod());
                    doReturn(transport).when(session).getTransport("smtp");
                    return session;
                });
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        sessions.close();
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"535 5.7.8", "535 5.7.1"})
    void authenticationFailureClosesTransportAndLogsOnlyStatusCodes(String smtpStatus) throws Exception {
        doThrow(new AuthenticationFailedException(smtpStatus + " rejected private-user@example.test private-app-password"))
                .when(transport).connect(anyString(), anyInt(), anyString(), anyString());

        NotificationTransportException error = assertThrows(NotificationTransportException.class,
                () -> sender.openSession(channel(465, true, false)));

        assertEquals("메일 서버 인증에 실패했습니다. 관리자에게 메일 연결 설정 확인을 요청해 주세요.", error.getMessage());
        assertTrue(error.isDefinitelyRejected());
        verify(transport).close();
        verify(transport, never()).sendMessage(any(), any());
        assertSafeLog("stage=CONNECT", "smtpStatus=" + smtpStatus, "errorType=AuthenticationFailedException",
                "usernameConfigured=true", "passwordConfigured=true");
    }

    @Test
    void cleanupFailureDoesNotReplaceConnectionFailure() throws Exception {
        doThrow(new MessagingException("private-user@example.test private-app-password"))
                .when(transport).connect(anyString(), anyInt(), anyString(), anyString());
        doThrow(new MessagingException("private cleanup detail")).when(transport).close();

        NotificationTransportException error = assertThrows(NotificationTransportException.class,
                () -> sender.openSession(channel(587, false, true)));

        assertEquals("메일 서버에 연결하지 못했습니다. 메일 연결 설정과 서버 상태를 확인해 주세요.", error.getMessage());
        assertTrue(error.isDefinitelyRejected());
        verify(transport).close();
        assertSafeLog("stage=CONNECT", "smtpStatus=UNAVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({"465,true,false", "587,false,true"})
    void preservesConfiguredTlsModeAndClosesSuccessfulSession(int port, boolean ssl, boolean startTls) throws Exception {
        try (NotificationSender.DeliverySession ignored = sender.openSession(channel(port, ssl, startTls))) {
            verify(transport).connect("smtp.example.test", port, "private-user@example.test", "private-app-password");
            assertEquals(String.valueOf(ssl), mailProperties.get().getProperty("mail.smtp.ssl.enable"));
            assertEquals(String.valueOf(startTls), mailProperties.get().getProperty("mail.smtp.starttls.enable"));
            assertEquals(String.valueOf(startTls), mailProperties.get().getProperty("mail.smtp.starttls.required"));
            assertEquals("true", mailProperties.get().getProperty("mail.smtp.ssl.checkserveridentity"));
        }
        verify(transport).close();
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void sendFailureRemainsUncertainAndDoesNotLogMessageOrAddresses() throws Exception {
        doThrow(new MessagingException("451 4.3.0 private-user@example.test private-app-password secret-report-body"))
                .when(transport).sendMessage(any(Message.class), any());

        NotificationTransportException error = assertThrows(NotificationTransportException.class,
                () -> sender.send(channel(465, true, false), "private-recipient@example.test",
                        "private subject", "secret-report-body"));

        assertFalse(error.isDefinitelyRejected());
        verify(transport).close();
        assertSafeLog("stage=SEND", "smtpStatus=451 4.3.0");
    }

    @Test
    void successfulSendKeepsMessageIdWhenConnectionCloseFails() throws Exception {
        doThrow(new MessagingException("close failed")).when(transport).close();

        String messageId = sender.send(channel(465, true, false), "private-recipient@example.test",
                "private subject", "secret-report-body");

        assertNotNull(messageId);
        assertFalse(messageId.isBlank());
        verify(transport).sendMessage(any(Message.class), any());
        verify(transport).close();
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void repeatedNotificationsHaveIndependentThreadReferencesAndUnchangedContent() throws Exception {
        String subject = "[뉴스 보고서] 같은 리포트";
        String body = "<html><body><h2>주요 이슈 1</h2><p>반복 전달해도 보여야 할 본문</p></body></html>";
        var returnedIds = new ArrayList<String>();
        var channel = channel(465, true, false);
        try (var delivery = sender.openSession(channel)) {
            returnedIds.add(delivery.send("reader@example.test", subject, body));
            returnedIds.add(delivery.send("reader@example.test", subject, body));
        }
        returnedIds.add(sender.send(channel, "reader@example.test", subject, body));

        var messages = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(transport, times(3)).sendMessage(messages.capture(), any());
        var references = new HashSet<String>();
        for (int index = 0; index < messages.getAllValues().size(); index++) {
            var bytes = new ByteArrayOutputStream();
            messages.getAllValues().get(index).writeTo(bytes);
            var wire = new MimeMessage(Session.getInstance(new Properties(), null),
                    new ByteArrayInputStream(bytes.toByteArray()));
            String reference = wire.getHeader("References", null);
            assertNotNull(reference);
            assertTrue(reference.matches("<news-delivery-[0-9a-f-]{36}@notifications\\.invalid>"));
            assertTrue(references.add(reference), "Each delivery needs a different thread reference");
            assertFalse(returnedIds.contains(reference), "Must not refer to another delivered message");
            assertNull(wire.getHeader("In-Reply-To"));
            assertEquals(returnedIds.get(index), wire.getMessageID());
            assertEquals(subject, wire.getSubject());
            assertEquals(body, wire.getContent());
            assertTrue(wire.isMimeType("text/html"));
        }
        assertEquals(3, new HashSet<>(returnedIds).size());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void serializedEmailPreservesHtmlAndEachRecipientsPdfAttachment() throws Exception {
        String subject = "[뉴스 보고서] 본문과 PDF";
        String body = "<html><body><p>간결한 메일 요약과 한글</p></body></html>";
        byte[] firstPdf = new byte[]{'%', 'P', 'D', 'F', '-', 0, 13, 10, (byte) 0xff};
        byte[] secondPdf = new byte[]{'%', 'P', 'D', 'F', '-', 1, 13, 10, (byte) 0xfe};
        var attachments = List.of(
                new NotificationAttachment("news-report-17.pdf", "application/pdf", firstPdf),
                new NotificationAttachment("news-report-17.pdf", "application/pdf", secondPdf));
        var returnedIds = new ArrayList<String>();
        try (var delivery = sender.openSession(channel(465, true, false))) {
            returnedIds.add(delivery.send("first@example.test", subject, body, List.of(attachments.get(0))));
            returnedIds.add(delivery.send("second@example.test", subject, body, List.of(attachments.get(1))));
        }

        var messages = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(transport, times(2)).sendMessage(messages.capture(), any());
        for (int index = 0; index < messages.getAllValues().size(); index++) {
            var bytes = new ByteArrayOutputStream();
            messages.getAllValues().get(index).writeTo(bytes);
            var wire = new MimeMessage(Session.getInstance(new Properties(), null),
                    new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(subject, wire.getSubject());
            assertEquals(returnedIds.get(index), wire.getMessageID());
            assertTrue(wire.isMimeType("multipart/mixed"));
            var multipart = (Multipart) wire.getContent();
            assertEquals(2, multipart.getCount());
            var html = multipart.getBodyPart(0);
            assertTrue(html.isMimeType("text/html"));
            assertEquals(body, html.getContent());
            var pdf = multipart.getBodyPart(1);
            assertTrue(pdf.isMimeType("application/pdf"));
            assertEquals(Part.ATTACHMENT, pdf.getDisposition());
            assertEquals("news-report-17.pdf", pdf.getFileName());
            assertArrayEquals(index == 0 ? firstPdf : secondPdf, pdf.getInputStream().readAllBytes());
            assertEquals(index == 0 ? "first@example.test" : "second@example.test",
                    ((jakarta.mail.internet.InternetAddress) wire.getAllRecipients()[0]).getAddress());
        }
        assertNotEquals(returnedIds.get(0), returnedIds.get(1));
        verify(transport).close();
    }

    @Test
    void emptyAttachmentsKeepTheExistingHtmlMessageFormat() throws Exception {
        try (var delivery = sender.openSession(channel(465, true, false))) {
            delivery.send("reader@example.test", "제목", "<p>요약</p>", List.of());
        }
        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(transport).sendMessage(message.capture(), any());
        assertTrue(message.getValue().isMimeType("text/html"));
        assertEquals("<p>요약</p>", message.getValue().getContent());
    }

    @Test
    void attachmentBytesCannotBeChangedAfterPreparationOrThroughAnAccessor() {
        byte[] bytes = new byte[]{'%', 'P', 'D', 'F'};
        var attachment = new NotificationAttachment("report.pdf", "application/pdf", bytes);
        bytes[0] = 0;
        byte[] read = attachment.content();
        read[1] = 0;
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F'}, attachment.content());
    }

    private NotificationChannel channel(int port, boolean ssl, boolean startTls) {
        return NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL)
                .config(Map.of("host", "smtp.example.test", "port", port, "from", "private-user@example.test",
                        "ssl", ssl, "startTls", startTls))
                .maxLength(Integer.MAX_VALUE).active(true).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"victim@example.test,hidden@example.test", "team:victim@example.test;",
            "victim@example.test\r\nBcc:hidden@example.test", "victim@example.test\n", "not-an-email"})
    void refusesLegacyUnsafeRecipientsBeforeOpeningSmtp(String address) {
        var error = assertThrows(NotificationTransportException.class,
                () -> sender.send(channel(465, true, false), address, "보고서", "본문"));
        assertTrue(error.isDefinitelyRejected());
        verifyNoInteractions(transport);
        sessions.verifyNoInteractions();
    }

    @Test
    void pooledSessionAlsoRefusesLegacyRecipientExpansion() throws Exception {
        try (var delivery = sender.openSession(channel(465, true, false))) {
            var error = assertThrows(NotificationTransportException.class,
                    () -> delivery.send("victim@example.test,hidden@example.test", "보고서", "본문"));
            assertTrue(error.isDefinitelyRejected());
            verify(transport, never()).sendMessage(any(), any());
        }
    }

    @Test
    void refusesSubjectHeaderInjectionBeforeOpeningSmtp() {
        assertThrows(NotificationTransportException.class,
                () -> sender.send(channel(465, true, false), "victim@example.test",
                        "보고서\r\nBcc:hidden@example.test", "본문"));
        verifyNoInteractions(transport);
        sessions.verifyNoInteractions();
    }

    @Test
    void refusesUnsafeStoredSenderBeforeOpeningSmtp() {
        var channel = channel(465, true, false);
        channel.update(channel.getName(), Map.of("host", "smtp.example.test", "port", 465,
                "from", "sender@example.test\r\nBcc:hidden@example.test", "ssl", true), 10000, true);
        assertThrows(NotificationTransportException.class, () -> sender.openSession(channel));
        verifyNoInteractions(transport);
        sessions.verifyNoInteractions();
    }

    @Test
    void validSingleMailboxProducesExactlyOneEnvelopeRecipient() throws Exception {
        sender.send(channel(465, true, false), "받는 사람 <victim+news@example.test>", "보고서", "본문");
        var recipients = org.mockito.ArgumentCaptor.forClass(jakarta.mail.Address[].class);
        verify(transport).sendMessage(any(Message.class), recipients.capture());
        assertEquals(1, recipients.getValue().length);
        assertEquals("victim+news@example.test", ((jakarta.mail.internet.InternetAddress) recipients.getValue()[0]).getAddress());
    }

    private void assertSafeLog(String... expectedParts) {
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.getFirst();
        String message = event.getFormattedMessage();
        for (String part : expectedParts) {
            assertTrue(message.contains(part), message);
        }
        assertFalse(message.contains("private-user"));
        assertFalse(message.contains("private-app-password"));
        assertFalse(message.contains("private-recipient"));
        assertFalse(message.contains("secret-report-body"));
        assertFalse(message.contains("private cleanup detail"));
        assertNull(event.getThrowableProxy());
    }
}
