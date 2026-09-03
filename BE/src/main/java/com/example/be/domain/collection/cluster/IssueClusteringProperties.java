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
    private int minArticleContentLength = 200;
    /**
     * 이 비율 이상의 기사에 나타나는 엔티티는 주제 어휘로 보고 교집합 계산에서 뺀다.
     *
     * <p>반도체 주제에서 {@code HBM}·{@code GPU}·{@code 삼성전자}는 거의 모든 기사에 나온다.
     * 두 기사가 그걸 2개 공유한다고 같은 사건은 아닌데, 교집합 규칙은 그걸 구분하지 못한다.
     * 실행 안에서 문서빈도를 세어 흔한 말을 빼면 남는 교집합만 사건 신호가 된다.
     */
    private double commonEntityDocumentRatio = 0.10;
    /** 문서빈도 컷을 적용할 최소 기사 수. 표본이 작으면 비율이 의미를 갖지 못한다. */
    private int commonEntityMinArticles = 20;

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
                || !Double.isFinite(commonEntityDocumentRatio)
                || commonEntityDocumentRatio <= 0
                || commonEntityDocumentRatio > 1
                || commonEntityMinArticles < 1
                || simhashHammingThreshold < 0
                || simhashHammingThreshold > 64
                || minArticleContentLength < 1) {
            throw new IllegalStateException("news.clustering 설정값이 올바르지 않습니다.");
        }
    }

    public double getCommonEntityDocumentRatio() {
        return commonEntityDocumentRatio;
    }

    public void setCommonEntityDocumentRatio(double commonEntityDocumentRatio) {
        this.commonEntityDocumentRatio = commonEntityDocumentRatio;
    }

    public int getCommonEntityMinArticles() {
        return commonEntityMinArticles;
    }

    public void setCommonEntityMinArticles(int commonEntityMinArticles) {
        this.commonEntityMinArticles = commonEntityMinArticles;
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

    public int getMinArticleContentLength() {
        return minArticleContentLength;
    }

    public void setMinArticleContentLength(int minArticleContentLength) {
        this.minArticleContentLength = minArticleContentLength;
    }
}
