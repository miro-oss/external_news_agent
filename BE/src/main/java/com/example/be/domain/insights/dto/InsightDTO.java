package com.example.be.domain.insights.dto;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.entity.InsightFact;
import com.example.be.domain.insights.entity.InsightImplication;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class InsightDTO {

    private InsightDTO() {
    }

    @Schema(name = "InsightCreateRequest")
    public record CreateRequest(String targetType,
                                Long targetId,
                                List<String> audiences) {
    }

    @Schema(name = "InsightResult")
    public record Result(boolean cached,
                         String targetType,
                         Long targetId,
                         String inputHash,
                         String promptVersion,
                         List<AudienceInsight> insights) {
    }

    public record AudienceInsight(Audience audience,
                                  String headline,
                                  List<InsightFact> facts,
                                  List<InsightImplication> implications,
                                  List<String> watchNext,
                                  BigDecimal confidence,
                                  String llmProvider,
                                  String llmModel,
                                  int relatedArticleCount,
                                  OffsetDateTime createdAt) {
    }
}
