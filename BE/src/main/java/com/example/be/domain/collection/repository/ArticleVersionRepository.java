package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.ArticleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArticleVersionRepository extends JpaRepository<ArticleVersion, Long> {

    List<ArticleVersion> findByArticleIdOrderByVersionNoAsc(Long articleId);

    /**
     * 다음 버전 번호를 정하려면 마지막 버전을 알아야 한다.
     */
    Optional<ArticleVersion> findFirstByArticleIdOrderByVersionNoDesc(Long articleId);
}
