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

    /**
     * SEARCH 소스 <b>한 곳당</b> 요청할 건수다. FEED 소스는 발행된 만큼 들어오므로 이 값과 무관하다.
     *
     * <p>10이었다. 뉴스 검색은 중복과 광고성 기사 비중이 높아 dedup과 키워드 필터를 거치면 3~5건만
     * 남았고, 보고서 한 편의 근거로 삼기 얇았다.
     *
     * <p>올려도 LLM 비용은 늘지 않는다. 검색 API는 건수 파라미터만 바뀌는 같은 1회 호출이고, 분석
     * 건수에는 실행당 상한이 따로 걸려 있다(agent.quota.free-run-article-limit /
     * paid-run-article-limit). 천장은 그대로인 채 프리필터가 고를 후보만 넓어진다.
     *
     * <p>하필 20인 이유는 커넥터 중 천장이 가장 낮은 Tavily(max_results 20)에 맞췄기 때문이다.
     * 더 올려도 거기서 잘리므로 "요청한 만큼 온다"가 깨진다.
     */
    public static final int DEFAULT_BATCH_SIZE = 20;
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
