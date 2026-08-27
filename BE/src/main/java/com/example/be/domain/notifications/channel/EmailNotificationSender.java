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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Properties;

@Component
@RequiredArgsConstructor
public class EmailNotificationSender implements NotificationSender {

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

        Authenticator authenticator = StringUtils.hasText(username)
                ? new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                }
                : null;
        Session session = Session.getInstance(mail, authenticator);
        try {
            Transport transport = session.getTransport("smtp");
            if (StringUtils.hasText(username)) {
                transport.connect(host, port, username, password);
            } else {
                transport.connect();
            }
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
                        throw new NotificationTransportException("메일 전송에 실패했습니다.", exception);
                    }
                }

                @Override
                public void close() {
                    try {
                        transport.close();
                    } catch (MessagingException ignored) {
                        // 발송 결과는 이미 수신되었으므로 연결 종료 실패로 성공 기록을 되돌리지 않는다.
                    }
                }
            };
        } catch (MessagingException exception) {
            throw new NotificationTransportException("메일 서버 연결에 실패했습니다.", exception);
        }
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
