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
    private Duration reportTimeout = Duration.ofSeconds(120);
    private AgentPlan defaultPlan = AgentPlan.FREE;
    private boolean allowRunOverride = true;
    private final Quota quota = new Quota();

    @Override
    public void afterPropertiesSet() {
        if (enabled && !StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "news.agent.enabled=true이면 AGENT_SHARED_SECRET을 설정해야 합니다.");
        }
        if (quota.freeRunArticleLimit <= 0
                || quota.freeDailyCalls <= 0
                || quota.paidMonthlyCredits <= 0
                || quota.paidDailyCredits <= 0
                || quota.paidRunArticleLimit <= 0
                || quota.paidDailyReportReserve < 0
                || quota.paidDailyReportReserve >= quota.paidDailyCredits
                || quota.paidCreditsPerRequest == null
                || quota.paidCreditsPerRequest.signum() <= 0) {
            throw new IllegalStateException("news.agent.quota 설정값이 올바르지 않습니다.");
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

    public Duration getReportTimeout() {
        return reportTimeout;
    }

    public void setReportTimeout(Duration reportTimeout) {
        this.reportTimeout = reportTimeout;
    }

    public AgentPlan getDefaultPlan() {
        return defaultPlan;
    }

    public void setDefaultPlan(AgentPlan defaultPlan) {
        this.defaultPlan = defaultPlan;
    }

    public boolean isAllowRunOverride() {
        return allowRunOverride;
    }

    public void setAllowRunOverride(boolean allowRunOverride) {
        this.allowRunOverride = allowRunOverride;
    }

    public Quota getQuota() {
        return quota;
    }

    public static class Quota {

        private int freeRunArticleLimit = 30;
        private int freeDailyCalls = 1500;
        private int paidMonthlyCredits = 3000;
        private int paidDailyCredits = 90;
        private int paidDailyReportReserve = 20;
        private int paidRunArticleLimit = 20;
        private java.math.BigDecimal paidCreditsPerRequest = java.math.BigDecimal.ONE;

        public int getFreeRunArticleLimit() {
            return freeRunArticleLimit;
        }

        public void setFreeRunArticleLimit(int freeRunArticleLimit) {
            this.freeRunArticleLimit = freeRunArticleLimit;
        }

        public int getFreeDailyCalls() {
            return freeDailyCalls;
        }

        public void setFreeDailyCalls(int freeDailyCalls) {
            this.freeDailyCalls = freeDailyCalls;
        }

        public int getPaidMonthlyCredits() {
            return paidMonthlyCredits;
        }

        public void setPaidMonthlyCredits(int paidMonthlyCredits) {
            this.paidMonthlyCredits = paidMonthlyCredits;
        }

        public int getPaidDailyCredits() {
            return paidDailyCredits;
        }

        public void setPaidDailyCredits(int paidDailyCredits) {
            this.paidDailyCredits = paidDailyCredits;
        }

        public int getPaidDailyReportReserve() {
            return paidDailyReportReserve;
        }

        public void setPaidDailyReportReserve(int paidDailyReportReserve) {
            this.paidDailyReportReserve = paidDailyReportReserve;
        }

        public int getPaidRunArticleLimit() {
            return paidRunArticleLimit;
        }

        public void setPaidRunArticleLimit(int paidRunArticleLimit) {
            this.paidRunArticleLimit = paidRunArticleLimit;
        }

        public java.math.BigDecimal getPaidCreditsPerRequest() {
            return paidCreditsPerRequest;
        }

        public void setPaidCreditsPerRequest(java.math.BigDecimal paidCreditsPerRequest) {
            this.paidCreditsPerRequest = paidCreditsPerRequest;
        }
    }
}
