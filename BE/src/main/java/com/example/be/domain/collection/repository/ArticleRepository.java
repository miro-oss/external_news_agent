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

}
