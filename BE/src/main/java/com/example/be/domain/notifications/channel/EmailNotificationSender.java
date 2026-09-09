package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationSender implements NotificationSender {

    private static final Pattern SMTP_STATUS = Pattern.compile("^\\s*([245][0-5][0-9])(?:[ -]+([245]\\.[0-9]{1,3}\\.[0-9]{1,3}))?(?=\\s|$)");

    private final NotificationProperties properties;

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    @Override
    public boolean isConfigured(NotificationChannel channel) {
        Map<String, Object> config = channel.getConfig();
        return StringUtils.hasText(text(config, "host"))
                && integer(config, "port", 0) > 0
                && StringUtils.hasText(text(config, "from"));
    }

    @Override
    public boolean isOnboarded(NotificationChannel channel, String address) {
        return true;
    }

    @Override
    public String send(NotificationChannel channel, String address, String subject, String body) {
        try (DeliverySession deliverySession = openSession(channel)) {
            return deliverySession.send(address, subject, body);
        }
    }

    @Override
    public DeliverySession openSession(NotificationChannel channel) {
        Map<String, Object> config = channel.getConfig();
        String host = text(config, "host");
        int port = integer(config, "port", 25);
        String from = text(config, "from");
        boolean ssl = bool(config, "ssl", false);
        boolean startTls = bool(config, "startTls", false);
        String username = properties.getSmtp().getUsername();
        String password = properties.getSmtp().getPassword();

        Properties mail = new Properties();
        mail.setProperty("mail.smtp.host", host);
        mail.setProperty("mail.smtp.port", String.valueOf(port));
        mail.setProperty("mail.smtp.auth", String.valueOf(StringUtils.hasText(username)));
        mail.setProperty("mail.smtp.ssl.enable", String.valueOf(ssl));
        mail.setProperty("mail.smtp.starttls.enable", String.valueOf(startTls));
        mail.setProperty("mail.smtp.connectiontimeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        mail.setProperty("mail.smtp.timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        mail.setProperty("mail.smtp.writetimeout", String.valueOf(properties.getReadTimeout().toMillis()));

        Authenticator authenticator = StringUtils.hasText(username)
                ? new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
                : null;
        Session session = Session.getInstance(mail, authenticator);
        Transport acquiredTransport = null;
        try {
            acquiredTransport = session.getTransport("smtp");
            if (StringUtils.hasText(username)) {
                acquiredTransport.connect(host, port, username, password);
            } else {
                acquiredTransport.connect();
            }
            Transport transport = acquiredTransport;
            return new DeliverySession() {
                @Override
                public String send(String address, String subject, String body) {
                    try {
                        MimeMessage message = new MimeMessage(session);
                        message.setFrom(new InternetAddress(from));
                        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(address, false));
                        message.setSubject(subject, "UTF-8");
                        message.setContent(body, "text/html; charset=UTF-8");
                        message.saveChanges();
                        transport.sendMessage(message, message.getAllRecipients());
                        return message.getMessageID();
                    } catch (MessagingException exception) {
                        logFailure(channel, "SEND", port, ssl, startTls, username, password, exception);
                        if (exception instanceof jakarta.mail.SendFailedException failed
                                && (failed.getValidSentAddresses() == null || failed.getValidSentAddresses().length == 0)) {
                            throw new NotificationTransportException("메일 주소가 거절되었습니다. 수신 주소와 발신 주소를 확인해 주세요.", true);
                        }
                        throw new NotificationTransportException("메일 전송 결과를 확인하지 못했습니다. 발송 이력과 수신함을 확인해 주세요.");
                    }
                }

                @Override
                public void close() {
                    closeQuietly(transport);
                }
            };
        } catch (MessagingException exception) {
            closeQuietly(acquiredTransport);
            logFailure(channel, "CONNECT", port, ssl, startTls, username, password, exception);
            if (exception instanceof jakarta.mail.AuthenticationFailedException) {
                throw new NotificationTransportException("메일 서버 인증에 실패했습니다. 관리자에게 메일 연결 설정 확인을 요청해 주세요.", true);
            }
            throw new NotificationTransportException("메일 서버에 연결하지 못했습니다. 메일 연결 설정과 서버 상태를 확인해 주세요.", true);
        }
    }

    private void closeQuietly(Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (MessagingException ignored) {
            // 연결 정리 실패가 원래 인증 오류나 이미 확정된 발송 결과를 덮어쓰지 않게 한다.
        }
    }

    private void logFailure(NotificationChannel channel, String stage, int port, boolean ssl, boolean startTls,
                            String username, String password, MessagingException exception) {
        // SMTP 원문에는 계정·주소·자격 증명이 포함될 수 있으므로 상태 코드만 허용한다.
        String status = "UNAVAILABLE";
        if (exception.getMessage() != null) {
            Matcher matcher = SMTP_STATUS.matcher(exception.getMessage());
            if (matcher.find()) {
                status = matcher.group(1) + (matcher.group(2) == null ? "" : " " + matcher.group(2));
            }
        }
        log.warn("Email transport failure: channelId={}, stage={}, port={}, ssl={}, startTls={}, "
                        + "usernameConfigured={}, passwordConfigured={}, errorType={}, smtpStatus={}",
                channel.getId(), stage, port, ssl, startTls, StringUtils.hasText(username),
                StringUtils.hasText(password), exception.getClass().getSimpleName(), status);
    }

    private String text(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int integer(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean bool(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && ("true".equalsIgnoreCase(text.trim())
                || "false".equalsIgnoreCase(text.trim()))) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }
}
