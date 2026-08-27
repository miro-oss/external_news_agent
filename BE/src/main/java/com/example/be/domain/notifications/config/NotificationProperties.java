package com.example.be.domain.notifications.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "news.notifications")
public class NotificationProperties {

    private Telegram telegram = new Telegram();
    private Smtp smtp = new Smtp();
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    @Getter @Setter
    public static class Telegram {
        private String baseUrl = "https://api.telegram.org";
        private String botToken = "";
    }

    @Getter @Setter
    public static class Smtp {
        private String username = "";
        private String password = "";
    }
}
