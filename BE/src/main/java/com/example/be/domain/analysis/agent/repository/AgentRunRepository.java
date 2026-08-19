package com.example.be.domain.analysis.agent.repository;

import com.example.be.domain.analysis.agent.entity.AgentRun;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByIdempotencyKey(String idempotencyKey);
}
