package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.ArticleBody;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleBodyRepository extends JpaRepository<ArticleBody, String> {
}
