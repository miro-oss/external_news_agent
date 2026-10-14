package com.example.be.domain.dart;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "news.dart")
public class DartProperties implements InitializingBean {
    private boolean enabled = true;
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    /** Missing credentials disable the integration without preventing application startup. */
    public boolean isEnabled() { return enabled && apiKey != null && !apiKey.isBlank(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration timeout) { this.connectTimeout = timeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration timeout) { this.readTimeout = timeout; }

    @Override public void afterPropertiesSet() {
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("DART HTTP timeout must be positive and at most 60 seconds");
        }
    }
    private boolean validTimeout(Duration value) {
        return value != null && !value.isNegative() && !value.isZero()
                && value.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
