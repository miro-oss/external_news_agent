package com.example.be.news.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "news.agent")
public class AgentProperties {

    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:8088";
    private String token = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration analyzeTimeout = Duration.ofSeconds(30);
    private String defaultPlan = "FREE";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getAnalyzeTimeout() {
        return analyzeTimeout;
    }

    public void setAnalyzeTimeout(Duration analyzeTimeout) {
        this.analyzeTimeout = analyzeTimeout;
    }

    public String getDefaultPlan() {
        return defaultPlan;
    }

    public void setDefaultPlan(String defaultPlan) {
        this.defaultPlan = defaultPlan;
    }
}
