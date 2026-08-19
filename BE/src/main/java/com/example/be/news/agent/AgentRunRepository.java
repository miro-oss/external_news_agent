package com.example.be.news.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<AgentRun> findByIdempotencyKey(String idempotencyKey);
}
