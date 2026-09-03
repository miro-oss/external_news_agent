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
import java.util.Set;

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
     * <p>무료 기본 검색원인 NAVER는 요청당 100건까지 받을 수 있어 기본값을 100으로 둔다. 최대 300건은
     * 커넥터가 {@code start}를 옮겨 세 페이지로 수집한다. 분석 건수에는 실행당 별도 상한이 있어 후보를
     * 넓혀도 LLM 입력 상한은 그대로다.
     *
     * <p>provider별 요청 상한은 각 커넥터가 적용한다. 따라서 운영자가 TAVILY나 SERPAPI를 다시 켜더라도
     * provider 허용 범위를 넘는 요청을 보내지 않는다.
     */
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final Set<Integer> ALLOWED_INTERVAL_MINUTES = Set.of(60, 720, 1440);
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 300;
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

    /** 마지막 수집 실행의 시작 시각. 다음 자동 실행 시점 계산에 쓴다. */
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

    /** 마지막 자동 수집 시작 시각을 기준으로 다음 실행 시점을 계산한다. */
    public boolean isCollectionDueAt(LocalDateTime now) {
        return active
                && (lastCollectedAt == null || !lastCollectedAt.plusMinutes(intervalMinutes).isAfter(now));
    }

    public void recordCollectionStartedAt(LocalDateTime startedAt) {
        this.lastCollectedAt = startedAt;
    }

    public int getLinkedSourceCount() {
        return sources.size();
    }
}
