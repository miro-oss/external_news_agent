package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
