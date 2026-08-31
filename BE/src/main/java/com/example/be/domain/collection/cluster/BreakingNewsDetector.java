package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/** 제목 마커와 수집 메타데이터만으로 속보를 판정한다. LLM이나 외부 I/O를 사용하지 않는다. */
@Component
public class BreakingNewsDetector {

    private static final int SHORT_BODY_LIMIT = 300;
    private static final Duration RECENT_PUBLICATION_WINDOW = Duration.ofHours(2);
    private static final Pattern EXPLICIT_MARKER = Pattern.compile(
            "(?iu)\\[\\s*(?:속보|1보)\\s*]|\\b(?:breaking(?:\\s+news)?|urgent)\\b\\s*[:\\-]?\\s*");

    public boolean isBreaking(ClusterArticle article) {
        return hasExplicitMarker(article.title())
                || isRecentShortFullText(article.fetchStatus(), article.body(),
                article.publishedAt(), article.observedAt());
    }

    public boolean hasExplicitMarker(String title) {
        return title != null && EXPLICIT_MARKER.matcher(title).find();
    }

    public String coreTitle(String title) {
        if (title == null) {
            return "";
        }
        return EXPLICIT_MARKER.matcher(title).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    public boolean isRecentShortFullText(FetchStatus fetchStatus,
                                         String body,
                                         OffsetDateTime publishedAt,
                                         OffsetDateTime observedAt) {
        if (fetchStatus != FetchStatus.FULLTEXT
                || body == null
                || body.strip().length() >= SHORT_BODY_LIMIT
                || publishedAt == null
                || observedAt == null) {
            return false;
        }
        Duration age = Duration.between(publishedAt, observedAt);
        return !age.isNegative() && age.compareTo(RECENT_PUBLICATION_WINDOW) <= 0;
    }
}
