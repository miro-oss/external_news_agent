package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingToneSnapshot;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.dto.res.IssueResDTO;
import com.example.be.domain.issues.entity.IssueArticle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class IssueToneCalculator {

    public IssueResDTO.ToneDistribution calculate(List<IssueArticle> memberships, List<FindingToneSnapshot> findings) {
        Map<Long, FindingToneSnapshot> latestByArticle = new HashMap<>();
        for (FindingToneSnapshot finding : findings) {
            latestByArticle.merge(finding.articleId(), finding, this::newer);
        }

        int analyzedArticleCount = 0;
        Map<ContentKey, FindingToneSnapshot> latestByContent = new HashMap<>();
        for (IssueArticle membership : memberships) {
            Article article = membership.getArticle();
            // 멤버에 대표 분석을 복제하지 않고 해당 기사에 저장된 분석만 사용한다.
            FindingToneSnapshot finding = latestByArticle.get(article.getId());
            if (finding == null) {
                continue;
            }
            if (AnalysisSource.isLlmDerived(finding.analysisSource())) {
                analyzedArticleCount++;
            }
            ContentKey key = article.getContentGroup() == null
                    ? new ContentKey(false, article.getId())
                    : new ContentKey(true, article.getContentGroup().getId());
            latestByContent.merge(key, finding, this::newer);
        }

        int optimistic = 0;
        int neutral = 0;
        int pessimistic = 0;
        for (FindingToneSnapshot finding : latestByContent.values()) {
            // 최신 분석이 STUB이거나 견해가 사라졌으면 과거의 견해로 되돌아가지 않는다.
            if (!AnalysisSource.isLlmDerived(finding.analysisSource())
                    || finding.sentiment() == null
                    || finding.effectiveKeyPoints().stream().noneMatch(this::isGroundedOpinion)) {
                continue;
            }
            switch (finding.sentiment()) {
                case POSITIVE -> optimistic++;
                case NEUTRAL -> neutral++;
                case NEGATIVE -> pessimistic++;
            }
        }

        int sampleCount = optimistic + neutral + pessimistic;
        return new IssueResDTO.ToneDistribution(
                analyzedArticleCount, sampleCount, optimistic, neutral, pessimistic,
                percent(optimistic, sampleCount), percent(neutral, sampleCount), percent(pessimistic, sampleCount));
    }

    private boolean isGroundedOpinion(FindingKeyPoint point) {
        // 비율에는 근거가 확인된 견해만 포함하므로 보고서에서 허용하는 weak은 제외한다.
        return FindingEvidencePolicy.isSupported(point)
                && "OPINION".equals(point.claimType())
                && "grounded".equals(point.groundedness())
                && StringUtils.hasText(point.attributedTo())
                && point.evidence().stream().allMatch(index -> index >= 0);
    }

    private FindingToneSnapshot newer(FindingToneSnapshot left, FindingToneSnapshot right) {
        return left.id() >= right.id() ? left : right;
    }

    private BigDecimal percent(int count, int total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private record ContentKey(boolean grouped, Long id) {
    }
}
