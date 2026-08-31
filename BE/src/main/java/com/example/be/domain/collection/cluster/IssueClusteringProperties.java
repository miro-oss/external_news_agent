package com.example.be.domain.collection.cluster;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "news.clustering")
public class IssueClusteringProperties implements InitializingBean {

    private double titleJaccardThreshold = 0.50;
    private Duration entityTimeWindow = Duration.ofHours(48);
    private Duration breakingTimeWindow = Duration.ofHours(6);
    private int entityOverlapThreshold = 2;
    private int simhashHammingThreshold = 3;

    @Override
    public void afterPropertiesSet() {
        if (!Double.isFinite(titleJaccardThreshold)
                || titleJaccardThreshold <= 0
                || titleJaccardThreshold > 1
                || entityTimeWindow == null
                || entityTimeWindow.isZero()
                || entityTimeWindow.isNegative()
                || breakingTimeWindow == null
                || breakingTimeWindow.isZero()
                || breakingTimeWindow.isNegative()
                || entityOverlapThreshold <= 0
                || simhashHammingThreshold < 0
                || simhashHammingThreshold > 64) {
            throw new IllegalStateException("news.clustering 설정값이 올바르지 않습니다.");
        }
    }

    public double getTitleJaccardThreshold() {
        return titleJaccardThreshold;
    }

    public void setTitleJaccardThreshold(double titleJaccardThreshold) {
        this.titleJaccardThreshold = titleJaccardThreshold;
    }

    public Duration getEntityTimeWindow() {
        return entityTimeWindow;
    }

    public void setEntityTimeWindow(Duration entityTimeWindow) {
        this.entityTimeWindow = entityTimeWindow;
    }

    public Duration getBreakingTimeWindow() {
        return breakingTimeWindow;
    }

    public void setBreakingTimeWindow(Duration breakingTimeWindow) {
        this.breakingTimeWindow = breakingTimeWindow;
    }

    public int getEntityOverlapThreshold() {
        return entityOverlapThreshold;
    }

    public void setEntityOverlapThreshold(int entityOverlapThreshold) {
        this.entityOverlapThreshold = entityOverlapThreshold;
    }

    public int getSimhashHammingThreshold() {
        return simhashHammingThreshold;
    }

    public void setSimhashHammingThreshold(int simhashHammingThreshold) {
        this.simhashHammingThreshold = simhashHammingThreshold;
    }
}
