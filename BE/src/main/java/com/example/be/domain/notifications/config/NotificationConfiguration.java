package com.example.be.domain.notifications.config;

import com.example.be.global.config.RestClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {

    private final RestClientFactory restClientFactory;

    @Bean("telegramRestClient")
    public RestClient telegramRestClient(NotificationProperties properties) {
        return restClientFactory.create(properties.getConnectTimeout(), properties.getReadTimeout())
                .baseUrl(properties.getTelegram().getBaseUrl())
                .build();
    }
}
