package com.example.be.domain.topics.repository;

import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface TopicKeywordProposalRepository extends JpaRepository<TopicKeywordProposal, Long> {

    @EntityGraph(attributePaths = {"topic"})
    @Query("""
            SELECT proposal
            FROM TopicKeywordProposal proposal
            WHERE (:status IS NULL OR proposal.status = :status)
            ORDER BY proposal.createdAt DESC, proposal.id DESC
            """)
    Page<TopicKeywordProposal> findPageByStatus(@Param("status") TopicKeywordProposalStatus status,
                                                Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT proposal FROM TopicKeywordProposal proposal JOIN FETCH proposal.topic WHERE proposal.id = :id")
    Optional<TopicKeywordProposal> findWithTopicById(@Param("id") Long id);

    boolean existsByTopic_IdAndStatus(Long topicId, TopicKeywordProposalStatus status);
}
