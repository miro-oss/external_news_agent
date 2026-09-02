package com.example.be.domain.analysis.agent.config;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "news.agent")
public class AgentProperties implements InitializingBean {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8088";
    private String token = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration analyzeTimeout = Duration.ofSeconds(90);
    private Duration insightTimeout = Duration.ofSeconds(60);
    private Duration reportTimeout = Duration.ofSeconds(120);
    private AgentPlan defaultPlan = AgentPlan.FREE;
    private boolean allowRunOverride = true;
    private String analysisPromptVersion =
            "analyze.ko.v6+perspective.ko.v1+sensitivity.ko.v2";
    private String insightPromptVersion = "insight.ko.v1+perspective.ko.v1";
    private String freeModel = "";
    private String paidModel = "";
    private final Quota quota = new Quota();
    private final Investigation investigation = new Investigation();

    @Override
    public void afterPropertiesSet() {
        if (enabled && !StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "news.agent.enabled=true이면 AGENT_SHARED_SECRET을 설정해야 합니다.");
        }
        if (quota.freeDailyCalls <= 0
                || quota.paidMonthlyCredits <= 0
                || quota.paidDailyCredits <= 0
                || quota.paidDailyReportReserve < 0
                || quota.paidDailyReportReserve >= quota.paidDailyCredits
                || quota.paidDailyInsightCap <= 0
                || quota.paidDailyInsightCap
                        > quota.paidDailyCredits - quota.paidDailyReportReserve
                || quota.paidCreditsPerRequest == null
                || quota.paidCreditsPerRequest.signum() <= 0
                || quota.paidMaxCreditsPerRequest == null
                || quota.paidMaxCreditsPerRequest.signum() <= 0
                || quota.paidCreditsPerRequest.compareTo(quota.paidMaxCreditsPerRequest) > 0
                || quota.paidMaxCreditsPerRequest.compareTo(BigDecimal.valueOf(quota.paidDailyCredits)) > 0
                || quota.paidMaxCreditsPerRequest.compareTo(BigDecimal.valueOf(quota.paidMonthlyCredits)) > 0
                || quota.reservationTtl == null
                || quota.reservationTtl.isNegative()
                || quota.reservationTtl.isZero()
                || investigation.candidateLimit <= 0
                || investigation.evidenceThreshold < 0
                || investigation.searchBatchSize <= 0
                || investigation.searchBatchSize > 100
                || investigation.dailyBudgetPercent == null
                || investigation.dailyBudgetPercent.signum() <= 0
                || investigation.dailyBudgetPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalStateException(
                    "news.agent.quota / news.agent.investigation 설정값이 올바르지 않습니다.");
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

    public Duration getInsightTimeout() {
        return insightTimeout;
    }

    public void setInsightTimeout(Duration insightTimeout) {
        this.insightTimeout = insightTimeout;
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

    public String getAnalysisPromptVersion() {
        return analysisPromptVersion;
    }

    public void setAnalysisPromptVersion(String analysisPromptVersion) {
        this.analysisPromptVersion = analysisPromptVersion;
    }

    public String getInsightPromptVersion() {
        return insightPromptVersion;
    }

    public void setInsightPromptVersion(String insightPromptVersion) {
        this.insightPromptVersion = insightPromptVersion;
    }

    public String getFreeModel() {
        return freeModel;
    }

    public void setFreeModel(String freeModel) {
        this.freeModel = freeModel;
    }

    public String getPaidModel() {
        return paidModel;
    }

    public void setPaidModel(String paidModel) {
        this.paidModel = paidModel;
    }

    public Quota getQuota() {
        return quota;
    }

    public Investigation getInvestigation() {
        return investigation;
    }

    public static class Investigation {

        private int candidateLimit = 5;
        private int evidenceThreshold = 3;
        private int searchBatchSize = 10;
        private BigDecimal dailyBudgetPercent = BigDecimal.valueOf(15);

        public int getCandidateLimit() {
            return candidateLimit;
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }

        public int getEvidenceThreshold() {
            return evidenceThreshold;
        }

        public void setEvidenceThreshold(int evidenceThreshold) {
            this.evidenceThreshold = evidenceThreshold;
        }

        public int getSearchBatchSize() {
            return searchBatchSize;
        }

        public void setSearchBatchSize(int searchBatchSize) {
            this.searchBatchSize = searchBatchSize;
        }

        public BigDecimal getDailyBudgetPercent() {
            return dailyBudgetPercent;
        }

        public void setDailyBudgetPercent(BigDecimal dailyBudgetPercent) {
            this.dailyBudgetPercent = dailyBudgetPercent;
        }
    }

    public static class Quota {

        private int freeDailyCalls = 1500;
        private int paidMonthlyCredits = 3000;
        private int paidDailyCredits = 90;
        private int paidDailyReportReserve = 20;
        private int paidDailyInsightCap = 15;
        private BigDecimal paidCreditsPerRequest = BigDecimal.ONE;
        private BigDecimal paidMaxCreditsPerRequest = BigDecimal.valueOf(5);
        private Duration reservationTtl = Duration.ofMinutes(15);

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

        public int getPaidDailyInsightCap() {
            return paidDailyInsightCap;
        }

        public void setPaidDailyInsightCap(int paidDailyInsightCap) {
            this.paidDailyInsightCap = paidDailyInsightCap;
        }

        public BigDecimal getPaidCreditsPerRequest() {
            return paidCreditsPerRequest;
        }

        public void setPaidCreditsPerRequest(BigDecimal paidCreditsPerRequest) {
            this.paidCreditsPerRequest = paidCreditsPerRequest;
        }

        public BigDecimal getPaidMaxCreditsPerRequest() {
            return paidMaxCreditsPerRequest;
        }

        public void setPaidMaxCreditsPerRequest(BigDecimal paidMaxCreditsPerRequest) {
            this.paidMaxCreditsPerRequest = paidMaxCreditsPerRequest;
        }

        public Duration getReservationTtl() {
            return reservationTtl;
        }

        public void setReservationTtl(Duration reservationTtl) {
            this.reservationTtl = reservationTtl;
        }
    }
}
