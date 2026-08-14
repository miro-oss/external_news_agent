package com.example.be.domain.collection.entity;

import com.example.be.domain.sources.entity.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 실행을 죽이지 않은 실패. 이게 하나라도 있으면 실행 상태가 PARTIAL이 된다.
 *
 * <p>예외를 삼키고 로그만 남기면 화면에서 "왜 기사가 적지?"를 설명할 수 없다. 사유를 남겨야 사용자가 원인을 본다.
 */
@Entity
@Table(name = "news_collection_run_warnings")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRunWarning {

    public static final String CODE_FULLTEXT_BLOCKED = "FULLTEXT_BLOCKED";
    public static final String CODE_ROBOTS_DISALLOWED = "ROBOTS_DISALLOWED";
    public static final String CODE_FEED_UNREADABLE = "FEED_UNREADABLE";
    public static final String CODE_RATE_LIMITED = "RATE_LIMITED";
    /** 검색 API 키가 없어 호출조차 하지 않았다. 소스 설정이나 환경변수 문제다. */
    public static final String CODE_PROVIDER_KEY_MISSING = "PROVIDER_KEY_MISSING";
    public static final String CODE_SEARCH_FAILED = "SEARCH_FAILED";
    /** 실행 도중 애플리케이션이 내려가 아무도 닫지 못한 실행. 다음 기동에서 reaper가 닫는다. */
    public static final String CODE_RUN_INTERRUPTED = "RUN_INTERRUPTED";
    /** 스레드풀이 작업을 거절해 시작조차 못 한 실행. */
    public static final String CODE_RUN_REJECTED = "RUN_REJECTED";

    public static final int MAX_CODE_LENGTH = 50;
    public static final int MAX_MESSAGE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    /** 소스와 무관한 경고도 있어 null이 가능하다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private Source source;

    @Column(name = "code", nullable = false, length = MAX_CODE_LENGTH)
    private String code;

    @Column(name = "message", length = MAX_MESSAGE_LENGTH)
    private String message;

    @Column(name = "article_count", nullable = false)
    private int articleCount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    void assignRun(CollectionRun run) {
        this.run = run;
    }
}
