package com.example.be.domain.insights.repository;

import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.entity.NewsInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NewsInsightRepository extends JpaRepository<NewsInsight, Long> {

    List<NewsInsight> findByTargetTypeAndTargetIdAndInputHashAndPromptVersionAndAudienceIn(
            AgentTargetType targetType,
            Long targetId,
            String inputHash,
            String promptVersion,
            Collection<Audience> audiences);

    Optional<NewsInsight> findFirstByTargetTypeAndTargetIdAndAudienceOrderByCreatedAtDescIdDesc(
            AgentTargetType targetType,
            Long targetId,
            Audience audience);
}
