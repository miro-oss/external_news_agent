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
import java.util.List;

public interface TopicKeywordProposalRepository extends JpaRepository<TopicKeywordProposal, Long> {

    @EntityGraph(attributePaths = {"topic"})
    @Query(value = """
            SELECT proposal
            FROM TopicKeywordProposal proposal
            WHERE proposal.topic.active = true
              AND (:status IS NULL OR proposal.status = :status)
            ORDER BY proposal.createdAt DESC, proposal.id DESC
            """, countQuery = """
            SELECT COUNT(proposal)
            FROM TopicKeywordProposal proposal
            WHERE proposal.topic.active = true
              AND (:status IS NULL OR proposal.status = :status)
            """)
    Page<TopicKeywordProposal> findActiveTopicPageByStatus(@Param("status") TopicKeywordProposalStatus status,
                                                         Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT proposal FROM TopicKeywordProposal proposal JOIN FETCH proposal.topic WHERE proposal.id = :id")
    Optional<TopicKeywordProposal> findWithTopicById(@Param("id") Long id);

    @Query("SELECT proposal.topic.id FROM TopicKeywordProposal proposal WHERE proposal.id = :id")
    Optional<Long> findTopicIdById(@Param("id") Long id);

    @Query("""
            SELECT proposal FROM TopicKeywordProposal proposal
            WHERE proposal.topic.id = :topicId AND proposal.id <> :proposalId
              AND proposal.status = com.example.be.domain.topics.entity.TopicKeywordProposalStatus.APPROVED
            """)
    List<TopicKeywordProposal> findOtherApprovedByTopicId(@Param("topicId") Long topicId,
                                                        @Param("proposalId") Long proposalId);

    boolean existsByTopic_IdAndStatus(Long topicId, TopicKeywordProposalStatus status);
}
