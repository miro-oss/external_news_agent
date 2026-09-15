package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.repository.ArticleBodyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

/** 전문은 한 번 저장하고, 기사마다 해당 불변 본문을 참조하게 한다. */
@Service
@RequiredArgsConstructor
public class ArticleBodyStorage {

    private static final String INSERT_IF_ABSENT = """
            BEGIN
                INSERT INTO news_article_bodies (body_hash, body) VALUES (?, ?);
            EXCEPTION
                WHEN DUP_VAL_ON_INDEX THEN NULL;
            END;
            """;
    private static final String INSERT_EMPTY_IF_ABSENT = """
            BEGIN
                INSERT INTO news_article_bodies (body_hash, body) VALUES (?, EMPTY_CLOB());
            EXCEPTION
                WHEN DUP_VAL_ON_INDEX THEN NULL;
            END;
            """;

    private final ArticleBodyRepository bodyRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public ArticleBody intern(String body) {
        ArticleBody candidate = ArticleBody.of(body);
        if (candidate == null) {
            return null;
        }

        ArticleBody stored = bodyRepository.findById(candidate.getBodyHash()).orElse(null);
        if (stored == null) {
            // Oracle의 UNIQUE 충돌 대기를 사용한다. 같은 트랜잭션에서 예외를 처리하므로
            // 경쟁자가 먼저 저장해도 Spring 트랜잭션이 rollback-only로 오염되지 않는다.
            jdbcTemplate.update(body.isEmpty() ? INSERT_EMPTY_IF_ABSENT : INSERT_IF_ABSENT, statement -> {
                statement.setString(1, candidate.getBodyHash());
                // 빈 문자열은 Oracle이 NULL로 처리하고 0자 CLOB 스트림도 허용하지 않으므로
                // EMPTY_CLOB() SQL을 쓴다. 나머지 전문은 문자열 길이에 관계없이 CLOB으로 보낸다.
                if (!body.isEmpty()) {
                    statement.setClob(2, new StringReader(body), (long) body.length());
                }
            });
            stored = bodyRepository.findById(candidate.getBodyHash())
                    .orElseThrow(() -> new IllegalStateException("공유 기사 본문을 저장하지 못했습니다."));
        }

        // 해시 일치만으로 다른 전문을 합치지 않는다. 오류에도 원문을 노출하지 않는다.
        if (!body.equals(stored.getBody())) {
            throw new IllegalStateException("기사 본문 해시 충돌을 감지했습니다.");
        }
        return stored;
    }
}
