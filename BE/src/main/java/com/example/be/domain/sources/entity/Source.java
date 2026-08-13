package com.example.be.domain.sources.entity;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "news_sources")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    public static final String KIND_FEED = "FEED";
    public static final String KIND_SEARCH = "SEARCH";

    public static final String ROBOTS_STATUS_ALLOWED = "allowed";
    public static final String ROBOTS_STATUS_DISALLOWED = "disallowed";
    public static final String ROBOTS_STATUS_UNKNOWN = "unknown";

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_URL_TEMPLATE_LENGTH = 1000;
    public static final int MAX_COUNTRY_LENGTH = 2;
    public static final int MAX_LANGUAGE_LENGTH = 5;

    public static final BigDecimal MIN_RELIABILITY_SCORE = BigDecimal.ZERO;
    public static final BigDecimal MAX_RELIABILITY_SCORE = BigDecimal.ONE;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_kind", nullable = false, length = 10)
    private String sourceKind;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "url_template", nullable = false, length = MAX_URL_TEMPLATE_LENGTH)
    private String urlTemplate;

    @Column(name = "country", length = MAX_COUNTRY_LENGTH)
    private String country;

    @Column(name = "language", length = MAX_LANGUAGE_LENGTH)
    private String language;

    @Convert(converter = CrawlPolicyConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "crawl_policy")
    private CrawlPolicy crawlPolicy;

    @Column(name = "robots_status", length = 20)
    private String robotsStatus;

    @Column(name = "robots_checked_at")
    private LocalDateTime robotsCheckedAt;

    @Column(name = "reliability_score")
    private BigDecimal reliabilityScore;

    /** 조건부 GET 상태. 다음 요청에 If-None-Match로 실어 보내고 304면 파싱을 건너뛴다(F6). */
    @Column(name = "etag", length = 200)
    private String etag;

    @Column(name = "last_modified", length = 100)
    private String lastModified;

    @Column(name = "last_fetched_at")
    private LocalDateTime lastFetchedAt;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    /**
     * 읽기 전용 역방향 매핑이다. 연결의 소유측은 news_topic_sources를 @JoinTable로 들고 있는 Topic이고,
     * 여기서는 소스 상세의 연결 주제 목록과 삭제 시 연결 검사에만 쓴다.
     */
    @Builder.Default
    @ManyToMany(mappedBy = "sources", fetch = FetchType.LAZY)
    private List<Topic> topics = new ArrayList<>();

    public boolean isSearchKind() {
        return KIND_SEARCH.equals(sourceKind);
    }

    /**
     * robotsStatus와 robotsCheckedAt은 서버가 robots.txt를 실제로 확인해서 채우는 값이라 여기서 바꾸지 않는다.
     * 재확인은 robots 재확인 API가 담당한다(M3).
     */
    public void update(String name,
                       String urlTemplate,
                       String country,
                       String language,
                       CrawlPolicy crawlPolicy,
                       BigDecimal reliabilityScore,
                       boolean active) {
        this.name = name;
        this.urlTemplate = urlTemplate;
        this.country = country;
        this.language = language;
        this.crawlPolicy = crawlPolicy;
        this.reliabilityScore = reliabilityScore;
        this.active = active;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /**
     * robots.txt를 실제로 확인한 결과를 남긴다. 요청으로 바꿀 수 없는 값이라 update()가 아니라 여기로 들어온다.
     */
    public void applyRobotsCheck(String robotsStatus, LocalDateTime checkedAt) {
        this.robotsStatus = robotsStatus;
        this.robotsCheckedAt = checkedAt;
    }

    /**
     * 다음 조건부 GET에 쓸 검증자. 상대가 주지 않으면 null이고, 그러면 다음 요청은 전체 조회가 된다.
     */
    public void applyFetchState(String etag, String lastModified, LocalDateTime fetchedAt) {
        this.etag = etag;
        this.lastModified = lastModified;
        this.lastFetchedAt = fetchedAt;
    }

    public boolean respectsRobots() {
        return crawlPolicy == null || !CrawlPolicy.ROBOTS_MODE_IGNORE.equals(crawlPolicy.robotsMode());
    }

    public boolean isRobotsDisallowed() {
        return ROBOTS_STATUS_DISALLOWED.equals(robotsStatus);
    }

    public int getLinkedTopicCount() {
        return topics.size();
    }
}
