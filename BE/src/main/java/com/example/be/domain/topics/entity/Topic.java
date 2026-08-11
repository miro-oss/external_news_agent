package com.example.be.domain.topics.entity;

import com.example.be.domain.sources.entity.Source;
import com.example.be.global.converter.StringListJsonConverter;
import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "news_topics")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Topic {

    public static final int DEFAULT_BATCH_SIZE = 10;
    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 100;
    public static final int MIN_INTERVAL_MINUTES = 10;
    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_QUERY_TEXT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "query_text", length = MAX_QUERY_TEXT_LENGTH)
    private String queryText;

    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "required_keywords")
    private List<String> requiredKeywords;

    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "optional_keywords")
    private List<String> optionalKeywords;

    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "excluded_keywords")
    private List<String> excludedKeywords;

    @Column(name = "batch_size", nullable = false)
    private int batchSize;

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    @Column(name = "last_collected_at")
    private LocalDateTime lastCollectedAt;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "news_topic_sources",
            joinColumns = @JoinColumn(name = "topic_id"),
            inverseJoinColumns = @JoinColumn(name = "source_id")
    )
    private List<Source> sources = new ArrayList<>();

    public void update(String name,
                       String queryText,
                       List<String> requiredKeywords,
                       List<String> optionalKeywords,
                       List<String> excludedKeywords,
                       int batchSize,
                       int intervalMinutes,
                       boolean active) {
        this.name = name;
        this.queryText = queryText;
        this.requiredKeywords = requiredKeywords;
        this.optionalKeywords = optionalKeywords;
        this.excludedKeywords = excludedKeywords;
        this.batchSize = batchSize;
        this.intervalMinutes = intervalMinutes;
        this.active = active;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public void replaceSources(List<Source> sources) {
        this.sources.clear();
        this.sources.addAll(sources);
    }

    public int getLinkedSourceCount() {
        return sources.size();
    }
}
