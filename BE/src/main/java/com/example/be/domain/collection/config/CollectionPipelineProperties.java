package com.example.be.domain.collection.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news.collection")
public class CollectionPipelineProperties implements InitializingBean {

    private int topicArticleLimit = 300;

    @Override
    public void afterPropertiesSet() {
        if (topicArticleLimit <= 0) {
            throw new IllegalStateException("news.collection.topic-article-limit는 1 이상이어야 합니다.");
        }
    }

    public int getTopicArticleLimit() {
        return topicArticleLimit;
    }

    public void setTopicArticleLimit(int topicArticleLimit) {
        this.topicArticleLimit = topicArticleLimit;
    }
}
