package com.example.be.domain.sources.repository;

import com.example.be.domain.sources.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, Long> {
}
