package com.example.be.domain.analysis.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news.analysis")
public class AnalysisSelectionProperties implements InitializingBean {

    private int issueLimitPerRun = 30;

    @Override
    public void afterPropertiesSet() {
        if (issueLimitPerRun <= 0) {
            throw new IllegalStateException("news.analysis.issue-limit-per-run은 1 이상이어야 합니다.");
        }
    }

    public int getIssueLimitPerRun() {
        return issueLimitPerRun;
    }

    public void setIssueLimitPerRun(int issueLimitPerRun) {
        this.issueLimitPerRun = issueLimitPerRun;
    }
}
