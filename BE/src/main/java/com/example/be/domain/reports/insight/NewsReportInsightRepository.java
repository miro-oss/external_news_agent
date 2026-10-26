package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface NewsReportInsightRepository extends JpaRepository<NewsReportInsight, Long> {
    List<NewsReportInsight> findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(
            Long reportId, String inputHash, String promptVersion, String rubricVersion, Collection<Audience> audiences);
}
