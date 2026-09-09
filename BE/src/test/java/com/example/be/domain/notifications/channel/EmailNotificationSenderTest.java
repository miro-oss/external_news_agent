package com.example.be.domain.notifications.channel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import jakarta.mail.Authenticator;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

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

    @Test
    void authenticationFailureClosesTransportAndLogsOnlyStatusCodes() throws Exception {
        doThrow(new AuthenticationFailedException("535 5.7.8 rejected private-user@example.test private-app-password"))
                .when(transport).connect(anyString(), anyInt(), anyString(), anyString());

        NotificationTransportException error = assertThrows(NotificationTransportException.class,
                () -> sender.openSession(channel(465, true, false)));

        assertEquals("메일 서버 인증에 실패했습니다. 관리자에게 메일 연결 설정 확인을 요청해 주세요.", error.getMessage());
        assertTrue(error.isDefinitelyRejected());
        verify(transport).close();
        verify(transport, never()).sendMessage(any(), any());
        assertSafeLog("stage=CONNECT", "smtpStatus=535 5.7.8", "errorType=AuthenticationFailedException",
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

    private NotificationChannel channel(int port, boolean ssl, boolean startTls) {
        return NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL)
                .config(Map.of("host", "smtp.example.test", "port", port, "from", "private-user@example.test",
                        "ssl", ssl, "startTls", startTls))
                .maxLength(Integer.MAX_VALUE).active(true).build();
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
