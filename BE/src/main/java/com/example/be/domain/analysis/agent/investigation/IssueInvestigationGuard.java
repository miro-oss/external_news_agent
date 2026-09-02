package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class IssueInvestigationGuard {

    private final AgentRunJdbcRepository agentRunRepository;

    public Decision evaluate(Long runId,
                             InvestigationContext context,
                             AgentExploreResponse.Proposal proposal) {
        if (proposal == null || !StringUtils.hasText(proposal.action())
                || !StringUtils.hasText(proposal.reason())) {
            return Decision.reject("조사 제안의 필수 필드가 없습니다.", null);
        }
        return switch (proposal.action()) {
            case "SEARCH_MORE" -> search(runId, context, proposal);
            case "READ_FULLTEXT" -> context.metadataOnlyArticleIds().contains(proposal.articleId())
                    ? Decision.accept(null)
                    : Decision.reject("이 이슈의 본문 미확보 기사가 아닙니다.", null);
            case "COMPARE_HISTORY" -> history(context, proposal);
            case "CONCLUDE" -> Decision.accept(null);
            default -> Decision.reject("허용되지 않은 조사 행동입니다: " + proposal.action(), null);
        };
    }

    private Decision search(Long runId,
                            InvestigationContext context,
                            AgentExploreResponse.Proposal proposal) {
        if (!context.sourceIdsByKey().containsKey(proposal.sourceKey())) {
            return Decision.reject("허용 소스가 아닙니다: " + proposal.sourceKey(), null);
        }
        if (!StringUtils.hasText(proposal.query())) {
            return Decision.reject("검색어가 비어 있습니다.", null);
        }
        String queryHash = InvestigationQueryNormalizer.hash(proposal.query());
        if (agentRunRepository.existsInvestigationQueryHash(runId, queryHash)) {
            return Decision.reject("같은 실행에서 이미 수행한 검색입니다.", queryHash);
        }
        return Decision.accept(queryHash);
    }

    private Decision history(InvestigationContext context,
                             AgentExploreResponse.Proposal proposal) {
        if (proposal.days() == null || proposal.days() < 1 || proposal.days() > 365
                || proposal.entities() == null || proposal.entities().isEmpty()) {
            return Decision.reject("과거 비교 범위가 올바르지 않습니다.", null);
        }
        Set<String> allowed = new HashSet<>(context.entities());
        if (!allowed.containsAll(proposal.entities())) {
            return Decision.reject("이슈에 없는 엔티티를 과거 비교에 사용할 수 없습니다.", null);
        }
        return Decision.accept(null);
    }

    public record Decision(boolean accepted, String rejectionReason, String queryHash) {

        static Decision accept(String queryHash) {
            return new Decision(true, null, queryHash);
        }

        static Decision reject(String reason, String queryHash) {
            return new Decision(false, reason, queryHash);
        }
    }
}
