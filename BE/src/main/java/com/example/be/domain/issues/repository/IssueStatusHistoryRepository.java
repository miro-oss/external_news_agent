package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueStatusHistoryRepository extends JpaRepository<IssueStatusHistory, Long> {
}
