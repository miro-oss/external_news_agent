package com.example.be.domain.settings.entity;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.Audience;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_settings")
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppSetting {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_plan", nullable = false, length = 10)
    private AgentPlan llmPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_exhausted_action", nullable = false, length = 20)
    private PaidExhaustedAction paidExhaustedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_audience", nullable = false, length = 30)
    private Audience defaultAudience;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void update(AgentPlan plan,
                       PaidExhaustedAction exhaustedAction,
                       LocalDateTime updatedAt) {
        this.llmPlan = plan;
        this.paidExhaustedAction = exhaustedAction;
        this.updatedAt = updatedAt;
    }

    public void updateAudience(Audience audience, LocalDateTime updatedAt) {
        this.defaultAudience = audience;
        this.updatedAt = updatedAt;
    }
}
