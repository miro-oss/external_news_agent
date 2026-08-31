package com.example.be.domain.collection.cluster;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 제목 맨 앞의 명시적 마커만으로 속보를 판정한다. LLM이나 외부 I/O를 사용하지 않는다. */
@Component
public class BreakingNewsDetector {

    private static final Pattern EXPLICIT_MARKER = Pattern.compile(
            "(?iu)^\\s*(?:\\[\\s*(?:속보|1보)\\s*]|"
                    + "(?:breaking(?:\\s+news)?|urgent)\\s*[:\\-])\\s*");

    public boolean isBreaking(ClusterArticle article) {
        return hasExplicitMarker(article.title());
    }

    public boolean hasExplicitMarker(String title) {
        return title != null && EXPLICIT_MARKER.matcher(title).find();
    }

    public String coreTitle(String title) {
        if (title == null) {
            return "";
        }
        return EXPLICIT_MARKER.matcher(title).replaceFirst("").trim();
    }
}
