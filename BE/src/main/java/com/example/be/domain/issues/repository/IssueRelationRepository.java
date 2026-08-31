package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueRelation;
import com.example.be.domain.issues.entity.IssueRelationType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueRelationRepository extends JpaRepository<IssueRelation, Long> {

    boolean existsByFromIssueIdAndToIssueIdAndRelationType(
            Long fromIssueId,
            Long toIssueId,
            IssueRelationType relationType);
}
