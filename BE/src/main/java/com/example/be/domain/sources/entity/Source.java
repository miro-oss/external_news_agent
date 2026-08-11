package com.example.be.domain.sources.entity;

import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

@Entity
@Table(name = "news_sources")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    public static final String KIND_FEED = "FEED";
    public static final String KIND_SEARCH = "SEARCH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_kind", nullable = false, length = 10)
    private String sourceKind;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "url_template", nullable = false, length = 1000)
    private String urlTemplate;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "language", length = 5)
    private String language;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "crawl_policy")
    private String crawlPolicy;

    @Column(name = "robots_status", length = 20)
    private String robotsStatus;

    @Column(name = "robots_checked_at")
    private LocalDateTime robotsCheckedAt;

    @Column(name = "reliability_score")
    private BigDecimal reliabilityScore;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    public boolean isSearchKind() {
        return KIND_SEARCH.equals(sourceKind);
    }
}
