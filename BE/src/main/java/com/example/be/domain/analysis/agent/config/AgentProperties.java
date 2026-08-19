package com.example.be.domain.analysis.agent.config;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "news.agent")
public class AgentProperties implements InitializingBean {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8088";
    private String token = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration analyzeTimeout = Duration.ofSeconds(30);
    private AgentPlan defaultPlan = AgentPlan.FREE;

    @Override
    public void afterPropertiesSet() {
        if (enabled && !StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "news.agent.enabled=true이면 AGENT_SHARED_SECRET을 설정해야 합니다.");
        }
    }

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

    public AgentPlan getDefaultPlan() {
        return defaultPlan;
    }

    public void setDefaultPlan(AgentPlan defaultPlan) {
        this.defaultPlan = defaultPlan;
    }
}
