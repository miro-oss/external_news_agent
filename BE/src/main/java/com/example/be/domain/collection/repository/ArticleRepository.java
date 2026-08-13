package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    /**
     * 중복 판정의 유일한 경로다. url_hash에 UNIQUE가 걸려 있어 주제와 무관하게 한 건만 존재한다.
     */
    Optional<Article> findByUrlHash(String urlHash);

    /**
     * 수집 한 배치에서 받은 URL들이 이미 있는지 한 번에 확인한다. 기사마다 조회하면 배치 크기만큼 쿼리가 나간다.
     */
    List<Article> findByUrlHashIn(Collection<String> urlHashes);

    boolean existsByUrlHash(String urlHash);

    /**
     * 이번 실행에서 관측했지만 아직 본문을 못 받은 기사. 소스를 함께 가져오는 이유는 본문 추출이
     * 트랜잭션 밖에서 돌면서 crawl_policy와 robots 설정을 봐야 하기 때문이다.
     *
     * <p>관측 테이블과 조인해 DISTINCT를 걸면 Oracle이 <b>ORA-22848</b>로 거부한다 — 결과 행에
     * CLOB(body·summary)이 들어 있고 CLOB은 비교 키가 될 수 없다. 서브쿼리로 id만 좁힌다.
     */
    @Query("""
            SELECT article
            FROM Article article
            JOIN FETCH article.source
            WHERE article.fetchStatus = com.example.be.domain.collection.entity.FetchStatus.METADATA_ONLY
              AND article.id IN (
                  SELECT observation.article.id
                  FROM CollectionRunArticle observation
                  WHERE observation.run.id = :runId
              )
            ORDER BY article.id ASC
            """)
    List<Article> findMetadataOnlyByRunId(@Param("runId") Long runId);
}
