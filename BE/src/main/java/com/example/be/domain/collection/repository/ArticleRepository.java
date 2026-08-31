package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.Article;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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

    /** 본문 중복군 병합 시 로더 시간창 밖의 기사까지 승자 그룹으로 옮긴다. */
    List<Article> findByContentGroupIdIn(Collection<Long> contentGroupIds);

    boolean existsByUrlHash(String urlHash);

    /** 같은 기사의 분석 결과를 동시에 저장할 때 unique check와 insert를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT article FROM Article article WHERE article.id = :articleId")
    Optional<Article> findByIdForUpdate(@Param("articleId") Long articleId);

    /**
     * 이번 실행에서 관측했지만 아직 본문을 못 받은 기사. 소스를 함께 가져오는 이유는 본문 추출이
     * 트랜잭션 밖에서 돌면서 crawl_policy와 robots 설정을 봐야 하기 때문이다.
     *
     * <p><b>{@code FETCH_FAILED}도 대상이다.</b> 5xx나 네트워크 오류로 한 번 실패한 기사를 빼 버리면
     * 그 상태가 영구가 된다. 후보를 "이번 실행에서 관측한 기사"로 좁혀 두었으므로, 피드에서 빠지면
     * 재시도도 자연히 멈춘다.
     *
     * <p>{@code FULLTEXT_BLOCKED}는 넣지 않는다. 상대가 명시적으로 막은 것이라 다시 불러도 같은 답이다.
     *
     * <p>관측 테이블과 조인해 DISTINCT를 걸면 Oracle이 <b>ORA-22848</b>로 거부한다 — 결과 행에
     * CLOB(body·summary)이 들어 있고 CLOB은 비교 키가 될 수 없다. 서브쿼리로 id만 좁힌다.
     */
    @Query("""
            SELECT article
            FROM Article article
            JOIN FETCH article.source
            WHERE article.fetchStatus IN (
                    com.example.be.domain.collection.entity.FetchStatus.METADATA_ONLY,
                    com.example.be.domain.collection.entity.FetchStatus.FETCH_FAILED
              )
              AND article.id IN (
                  SELECT observation.article.id
                  FROM CollectionRunArticle observation
                  WHERE observation.run.id = :runId
              )
            ORDER BY article.id ASC
            """)
    List<Article> findFullTextTargetsByRunId(@Param("runId") Long runId);
}
